// 공통 API 클라이언트 테스트. 실제 서버 없이 fetch 를 가짜로 바꿔서 검증한다.
// jest.fn() 은 Mockito 의 mock 과 같고, describe/it 은 JUnit 의 클래스/@Test 에 해당한다.
import { request, setSessionExpiredHandler, setSignupIncompleteHandler } from '../api/client';
import { ApiError } from '../api/errors';
import { clearSession, getTokens, setTokens } from '../auth/session';

// 토큰 저장소는 기기 기능(키체인)을 쓰므로 테스트에서는 아무 일도 하지 않는 가짜로 바꾼다
jest.mock('../auth/tokenStorage', () => ({
  loadTokens: jest.fn(async () => null),
  saveTokens: jest.fn(async () => undefined),
  clearTokens: jest.fn(async () => undefined),
}));

type FakeResponse = {
  ok: boolean;
  status: number;
  json: () => Promise<unknown>;
  text: () => Promise<string>;
};

type Call = {
  url: string;
  method: string;
  authorization: string | undefined;
  body: unknown;
};

function respond(status: number, body?: unknown): FakeResponse {
  const text = body === undefined ? '' : JSON.stringify(body);
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => JSON.parse(text),
    text: async () => text,
  };
}

const EXPIRED = { code: 'AUTH_EXPIRED_TOKEN', message: '만료된 토큰입니다.' };

const calls: Call[] = [];
// 테스트마다 서버 동작을 바꿔 끼운다
let server: (call: Call) => Promise<FakeResponse> | FakeResponse;

// 실패해야 하는 요청에서 던져진 에러를 꺼낸다. 성공하면 테스트를 실패시킨다
async function failure(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise;
  } catch (error) {
    if (error instanceof ApiError) {
      return error;
    }
    throw error;
  }
  throw new Error('요청이 실패해야 하는데 성공했습니다.');
}

function callsTo(pathSuffix: string): Call[] {
  return calls.filter((c) => c.url.endsWith(pathSuffix));
}

// access-1 은 만료, access-2 는 유효. refresh-1 을 주면 access-2/refresh-2 로 교체해 주는 서버
function rotatingServer(call: Call): FakeResponse {
  if (call.url.endsWith('/auth/refresh')) {
    return respond(200, {
      member: { id: 1, nickname: 'tester', signupStatus: 'ACTIVE' },
      accessToken: 'access-2',
      refreshToken: 'refresh-2',
    });
  }
  if (call.authorization === 'Bearer access-2') {
    return respond(200, { path: call.url });
  }
  return respond(401, EXPIRED);
}

beforeEach(async () => {
  process.env.EXPO_PUBLIC_API_URL = 'http://test-server:8080';
  calls.length = 0;
  server = rotatingServer;
  setSessionExpiredHandler(null);
  setSignupIncompleteHandler(null);
  await setTokens({ accessToken: 'access-1', refreshToken: 'refresh-1' });

  globalThis.fetch = jest.fn(async (url: string, init: RequestInit) => {
    const headers = (init.headers ?? {}) as Record<string, string>;
    const call: Call = {
      url,
      method: init.method ?? 'GET',
      authorization: headers.Authorization,
      body: typeof init.body === 'string' ? JSON.parse(init.body) : undefined,
    };
    calls.push(call);
    return server(call);
  }) as unknown as typeof fetch;
});

describe('요청 기본 동작', () => {
  it('주소 앞에 서버 주소와 /api/v1 을 붙이고 Authorization 헤더를 넣는다', async () => {
    await setTokens({ accessToken: 'access-2', refreshToken: 'refresh-2' });

    await request('/members/me');

    expect(calls).toHaveLength(1);
    expect(calls[0].url).toBe('http://test-server:8080/api/v1/members/me');
    expect(calls[0].authorization).toBe('Bearer access-2');
  });

  it('auth: false 면 토큰이 있어도 Authorization 헤더를 보내지 않는다', async () => {
    server = () => respond(200, {});

    await request('/auth/login', { method: 'POST', body: { email: 'a@b.c', password: 'pw' }, auth: false });

    expect(calls[0].authorization).toBeUndefined();
    expect(calls[0].body).toEqual({ email: 'a@b.c', password: 'pw' });
  });

  it('204 응답은 본문 없이 끝난다', async () => {
    server = () => respond(204);

    await expect(request('/auth/logout', { method: 'POST', body: { refreshToken: 'r' } })).resolves.toBeUndefined();
  });
});

describe('에러 응답 파싱', () => {
  it('VALIDATION_ERROR 의 errors 를 필드별로 읽는다', async () => {
    server = () =>
      respond(400, {
        code: 'VALIDATION_ERROR',
        message: '입력값이 올바르지 않습니다.',
        errors: [{ field: 'email', reason: '이메일 형식이 아닙니다.' }],
      });

    const error = await failure(request('/auth/signup', { method: 'POST', body: {}, auth: false }));

    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(400);
    expect(error.code).toBe('VALIDATION_ERROR');
    expect(error.message).toBe('입력값이 올바르지 않습니다.');
    expect(error.fieldError('email')).toBe('이메일 형식이 아닙니다.');
    expect(error.fieldError('password')).toBeUndefined();
  });

  it('errors 가 없는 에러 응답도 읽는다', async () => {
    server = () => respond(409, { code: 'MEMBER_EMAIL_DUPLICATE', message: '이미 가입된 이메일입니다.' });

    const error = await failure(request('/auth/signup', { method: 'POST', body: {}, auth: false }));

    expect(error.code).toBe('MEMBER_EMAIL_DUPLICATE');
    expect(error.fieldErrors).toEqual([]);
  });

  it('JSON 이 아닌 에러 응답은 UNKNOWN_ERROR 로 바꾼다', async () => {
    server = () => ({
      ok: false,
      status: 502,
      json: async () => {
        throw new SyntaxError('Unexpected token <');
      },
      text: async () => '<html>Bad Gateway</html>',
    });

    const error = await failure(request('/questions/feed'));

    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(502);
    expect(error.code).toBe('UNKNOWN_ERROR');
  });

  it('서버에 닿지 못하면 NETWORK_ERROR 로 바꾼다', async () => {
    server = () => {
      throw new TypeError('Failed to fetch');
    };

    const error = await failure(request('/questions/feed'));

    expect(error).toBeInstanceOf(ApiError);
    expect(error.code).toBe('NETWORK_ERROR');
  });
});

describe('401 재발급', () => {
  it('동시에 여러 요청이 만료 401 을 받아도 재발급은 한 번만 호출한다', async () => {
    const results = await Promise.all([request('/a'), request('/b'), request('/c')]);

    expect(callsTo('/auth/refresh')).toHaveLength(1);
    expect(callsTo('/auth/refresh')[0].body).toEqual({ refreshToken: 'refresh-1' });
    // 재발급 요청에는 Authorization 헤더가 없어야 한다
    expect(callsTo('/auth/refresh')[0].authorization).toBeUndefined();
    expect(results).toHaveLength(3);
    // 세 요청 모두 새 토큰으로 한 번씩 재시도했다
    for (const path of ['/a', '/b', '/c']) {
      expect(callsTo(path).map((c) => c.authorization)).toEqual(['Bearer access-1', 'Bearer access-2']);
    }
    expect(getTokens()).toEqual({ accessToken: 'access-2', refreshToken: 'refresh-2' });
  });

  it('재발급이 끝난 뒤 옛 토큰의 401 이 늦게 도착하면 재발급 없이 새 토큰으로 재시도한다', async () => {
    // /slow 의 첫 응답(401)을 테스트가 원하는 시점에 풀어 준다
    let releaseSlow: () => void = () => undefined;
    const slowGate = new Promise<void>((resolve) => {
      releaseSlow = resolve;
    });
    server = async (call) => {
      if (call.url.endsWith('/slow') && call.authorization === 'Bearer access-1') {
        await slowGate;
      }
      return rotatingServer(call);
    };

    const slow = request('/slow'); // access-1 을 들고 출발, 응답 대기 중
    await request('/fast'); // 401 → 재발급 → 재시도까지 끝남
    expect(getTokens()?.accessToken).toBe('access-2');

    releaseSlow(); // 이제서야 /slow 의 401 이 도착
    await slow;

    expect(callsTo('/auth/refresh')).toHaveLength(1);
    expect(callsTo('/slow').map((c) => c.authorization)).toEqual(['Bearer access-1', 'Bearer access-2']);
  });

  it('재시도는 한 번만 한다. 재시도도 401 이면 그대로 실패한다', async () => {
    server = (call) => {
      if (call.url.endsWith('/auth/refresh')) {
        return respond(200, { accessToken: 'access-2', refreshToken: 'refresh-2' });
      }
      return respond(401, EXPIRED); // 새 토큰도 계속 만료라고 답하는 서버
    };

    const error = await failure(request('/a'));

    expect(error.code).toBe('AUTH_EXPIRED_TOKEN');
    expect(callsTo('/a')).toHaveLength(2);
    expect(callsTo('/auth/refresh')).toHaveLength(1);
  });

  it('재발급이 재사용 감지로 거절되면 토큰을 지우고 세션 만료를 알린다', async () => {
    const expired = jest.fn();
    setSessionExpiredHandler(expired);
    server = (call) => {
      if (call.url.endsWith('/auth/refresh')) {
        return respond(401, { code: 'AUTH_REFRESH_REUSED', message: '다시 로그인해 주세요.' });
      }
      return respond(401, EXPIRED);
    };

    const errors = await Promise.all([failure(request('/a')), failure(request('/b'))]);

    expect(errors.map((e) => e.code)).toEqual(['AUTH_REFRESH_REUSED', 'AUTH_REFRESH_REUSED']);
    expect(callsTo('/auth/refresh')).toHaveLength(1);
    expect(getTokens()).toBeNull();
    expect(expired).toHaveBeenCalledTimes(1);
    // 재발급에 실패했으면 원래 요청을 재시도하지 않는다
    expect(callsTo('/a')).toHaveLength(1);
  });

  it('재발급 중 네트워크 오류가 나면 토큰을 지우지 않는다', async () => {
    const expired = jest.fn();
    setSessionExpiredHandler(expired);
    server = (call) => {
      if (call.url.endsWith('/auth/refresh')) {
        throw new TypeError('Failed to fetch');
      }
      return respond(401, EXPIRED);
    };

    const error = await failure(request('/a'));

    expect(error.code).toBe('NETWORK_ERROR');
    expect(getTokens()).toEqual({ accessToken: 'access-1', refreshToken: 'refresh-1' });
    expect(expired).not.toHaveBeenCalled();
  });

  it('실패한 재발급 뒤에도 다음 재발급은 새로 시도한다', async () => {
    let refreshAttempts = 0;
    server = (call) => {
      if (call.url.endsWith('/auth/refresh')) {
        refreshAttempts += 1;
        if (refreshAttempts === 1) {
          throw new TypeError('Failed to fetch');
        }
      }
      return rotatingServer(call);
    };

    await request('/a').catch(() => undefined);
    await request('/a');

    expect(refreshAttempts).toBe(2);
    expect(getTokens()?.accessToken).toBe('access-2');
  });

  it('만료가 아닌 401 은 재발급하지 않는다', async () => {
    server = () => respond(401, { code: 'AUTH_INVALID_CREDENTIALS', message: '이메일 또는 비밀번호가 올바르지 않습니다.' });

    const error = await failure(request('/auth/login', { method: 'POST', body: {}, auth: false }));

    expect(error.code).toBe('AUTH_INVALID_CREDENTIALS');
    expect(callsTo('/auth/refresh')).toHaveLength(0);
  });

  it('위조·형식 오류 토큰(AUTH_INVALID_TOKEN)도 재발급하지 않는다', async () => {
    server = () => respond(401, { code: 'AUTH_INVALID_TOKEN', message: '유효하지 않은 토큰입니다.' });

    const error = await failure(request('/members/me'));

    expect(error.code).toBe('AUTH_INVALID_TOKEN');
    expect(callsTo('/auth/refresh')).toHaveLength(0);
  });

  it('저장된 토큰이 없으면 재발급을 시도하지 않고 실패한다', async () => {
    await clearSession();
    server = () => respond(401, EXPIRED);

    const error = await failure(request('/members/me'));

    expect(error.code).toBe('SESSION_EXPIRED');
    expect(callsTo('/auth/refresh')).toHaveLength(0);
  });
});

describe('가입 미완료', () => {
  it('403 SIGNUP_INCOMPLETE 를 받으면 알림 콜백을 부르고 에러를 던진다', async () => {
    const incomplete = jest.fn();
    setSignupIncompleteHandler(incomplete);
    server = () => respond(403, { code: 'SIGNUP_INCOMPLETE', message: '휴대폰 인증이 필요합니다.' });

    const error = await failure(request('/questions/feed'));

    expect(error.code).toBe('SIGNUP_INCOMPLETE');
    expect(incomplete).toHaveBeenCalledTimes(1);
  });
});
