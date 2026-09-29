// 공통 API 클라이언트. 모든 서버 호출은 이 파일의 request() 를 거친다.
//
// 하는 일
//  1. Authorization 헤더 자동 첨부
//  2. 서버 에러 응답을 ApiError 로 변환
//  3. access 토큰이 만료(401 AUTH_EXPIRED_TOKEN)되면 재발급 후 원래 요청을 한 번 재시도
//  4. 제한 시간(15초) 안에 응답이 없으면 그만 기다리고 NETWORK_ERROR 로 실패 (api/timeout.ts)
//
// 가장 중요한 규칙: 재발급 요청은 동시에 하나만 나가야 한다.
// 서버는 이미 쓴 refresh 토큰이 다시 오면 탈취로 보고 그 회원의 모든 기기를 로그아웃시킨다 (docs/api.md 1.3).
// 화면 하나가 요청 3개를 동시에 보내고 셋 다 401 을 받았을 때 각자 재발급하면 바로 그 상황이 된다.
import { clearSession, getTokens, setTokens } from '../auth/session';
import { getApiBaseUrl } from './config';
import { ApiError, NETWORK_ERROR, parseErrorResponse } from './errors';
import { REQUEST_TIMEOUT_MS, TimeoutError, withTimeout } from './timeout';

export type RequestOptions = {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  // false 면 Authorization 헤더를 붙이지 않는다 (가입·로그인·재발급).
  // 공개 API 라도 잘못된 토큰이 붙어 있으면 서버가 401 로 거부할 수 있어서 아예 보내지 않는다
  auth?: boolean;
  // 요청에 덧붙일 헤더 (예: Idempotency-Key).
  // 토큰이 만료되어 재발급 후 다시 보낼 때도 같은 값이 실린다. 같은 요청의 재전송이므로 그래야 한다
  headers?: Record<string, string>;
  // HTTP 호출 한 번의 제한 시간(ms). 기본값은 REQUEST_TIMEOUT_MS(15초).
  // 재발급 후 다시 보낼 때는 처음부터 다시 잰다
  timeoutMs?: number;
};

// 재발급 응답에서 클라이언트가 쓰는 부분
type RefreshResponse = {
  accessToken: string;
  refreshToken: string;
};

const SESSION_EXPIRED = 'SESSION_EXPIRED'; // 앱이 만드는 코드: 저장된 토큰이 없어 재발급을 시도할 수 없음

// 진행 중인 재발급. 없으면 null.
// Promise 는 Java 의 CompletableFuture 와 같다. 여러 요청이 같은 Promise 를 기다리면
// 재발급은 한 번만 일어나고 결과는 모두가 받는다 (single-flight).
let refreshInFlight: Promise<void> | null = null;

// 세션이 끝났을 때(재발급 실패) 화면 쪽에 알리는 콜백. AuthContext 가 등록한다.
// 이 파일이 화면 코드를 직접 import 하지 않게 하려는 것이다 (Spring 의 이벤트 리스너와 비슷한 역할)
let onSessionExpired: (() => void) | null = null;
// 휴대폰 인증이 안 된 회원이 ACTIVE 전용 API 를 불렀을 때(403 SIGNUP_INCOMPLETE) 알리는 콜백
let onSignupIncomplete: (() => void) | null = null;

export function setSessionExpiredHandler(handler: (() => void) | null): void {
  onSessionExpired = handler;
}

export function setSignupIncompleteHandler(handler: (() => void) | null): void {
  onSignupIncomplete = handler;
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const useAuth = options.auth ?? true;
  // 이 요청이 실제로 들고 나간 토큰을 기억해 둔다. 401 을 받았을 때 "그 사이 누가 이미 교체했는지" 판단하는 기준이다
  const usedToken = useAuth ? (getTokens()?.accessToken ?? null) : null;

  try {
    return await send<T>(path, options, usedToken);
  } catch (error) {
    if (!(error instanceof ApiError)) {
      throw error;
    }
    if (error.code === 'SIGNUP_INCOMPLETE') {
      onSignupIncomplete?.();
      throw error;
    }
    // 재발급 대상은 "access 토큰 만료" 하나뿐이다.
    // 로그인 비밀번호 오류(AUTH_INVALID_CREDENTIALS)나 위조 토큰(AUTH_INVALID_TOKEN)도 401 이지만 재발급으로 해결되지 않는다
    if (!useAuth || error.status !== 401 || error.code !== 'AUTH_EXPIRED_TOKEN') {
      throw error;
    }
  }

  await refreshOnce(usedToken);

  // 재시도는 한 번만. 여기서 또 401 이 나면 그대로 던진다 (무한 반복 방지)
  return send<T>(path, options, getTokens()?.accessToken ?? null);
}

// usedToken: 401 을 받은 요청이 보냈던 access 토큰
async function refreshOnce(usedToken: string | null): Promise<void> {
  const tokens = getTokens();
  if (!tokens) {
    throw new ApiError(401, SESSION_EXPIRED, '로그인이 필요합니다.');
  }

  // 내가 보낸 토큰과 지금 토큰이 다르면, 응답을 기다리는 사이 다른 요청이 이미 재발급을 끝낸 것이다.
  // 다시 재발급하지 않고 새 토큰으로 재시도만 하면 된다
  if (tokens.accessToken !== usedToken) {
    return;
  }

  // 자바스크립트는 단일 스레드라 아래 "확인 후 대입" 사이에 다른 코드가 끼어들 수 없다.
  // (await 를 만나기 전까지는 실행이 끊기지 않는다. Java 였다면 synchronized 가 필요한 자리)
  if (!refreshInFlight) {
    refreshInFlight = refresh(tokens.refreshToken).finally(() => {
      refreshInFlight = null;
    });
  }
  await refreshInFlight;
}

async function refresh(refreshToken: string): Promise<void> {
  try {
    const response = await send<RefreshResponse>(
      '/auth/refresh',
      { method: 'POST', body: { refreshToken }, auth: false },
      null,
    );
    await setTokens({ accessToken: response.accessToken, refreshToken: response.refreshToken });
  } catch (error) {
    // 서버가 거절한 경우(만료·폐기·재사용 감지·정지 회원)에만 세션을 끝낸다.
    // 네트워크 오류(시간 초과 포함)는 토큰이 아직 유효할 수 있으므로 지우지 않는다.
    // 다만 서버가 교체를 마쳤는데 응답만 유실됐다면 다음 재발급은 재사용으로 감지된다.
    // 클라이언트에서 막을 수 없는 경우이며, 그때는 아래 분기로 와서 다시 로그인하게 된다
    if (error instanceof ApiError && error.code !== NETWORK_ERROR) {
      await clearSession();
      onSessionExpired?.();
    }
    throw error;
  }
}

// 실제 HTTP 호출 한 번. 재시도·재발급 판단은 하지 않는다
async function send<T>(path: string, options: RequestOptions, accessToken: string | null): Promise<T> {
  // 덧붙인 헤더를 먼저 넣고 기본 헤더를 뒤에 넣는다. 덧붙인 헤더가 Authorization 등을 덮어쓰지 못한다
  const headers: Record<string, string> = { ...options.headers, Accept: 'application/json' };
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  if (accessToken) {
    headers.Authorization = `Bearer ${accessToken}`;
  }

  try {
    // 제한 시간은 응답 본문을 다 읽을 때까지 잰다. 헤더만 오고 본문이 멈추는 경우도 끝나야 한다
    return await withTimeout(options.timeoutMs ?? REQUEST_TIMEOUT_MS, async (signal) => {
      let response: Response;
      try {
        response = await fetch(getApiBaseUrl() + path, {
          method: options.method ?? 'GET',
          headers,
          body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
          signal,
        });
      } catch {
        // fetch 는 HTTP 오류(4xx, 5xx)에는 예외를 던지지 않는다. 예외가 났다면 서버에 닿지 못한 것이다
        throw new ApiError(
          0,
          NETWORK_ERROR,
          '서버에 연결할 수 없습니다. 서버가 켜져 있는지, EXPO_PUBLIC_API_URL 이 맞는지 확인하세요.',
        );
      }

      if (!response.ok) {
        throw await parseErrorResponse(response);
      }

      // 204 No Content (로그아웃 등) 는 본문이 없다
      if (response.status === 204) {
        return undefined as T;
      }
      // 본문이 비어 있는 2xx 응답도 견딘다
      const text = await response.text();
      return (text ? JSON.parse(text) : undefined) as T;
    });
  } catch (error) {
    if (error instanceof TimeoutError) {
      // 네트워크 오류와 같은 코드로 던진다. 둘 다 "서버가 처리했는지 모른다"는 같은 상황이고,
      // 코드가 같아야 재발급(토큰을 지우지 않음)과 화면의 다시 시도 안내가 똑같이 동작한다
      throw new ApiError(0, NETWORK_ERROR, '서버가 응답하지 않습니다. 네트워크 연결을 확인해 주세요.');
    }
    throw error;
  }
}
