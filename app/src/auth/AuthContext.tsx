// 로그인 상태를 앱 전체에서 공유하는 곳.
//
// React 의 Context 는 "아래에 있는 모든 화면이 꺼내 쓸 수 있는 값"이다.
// Spring 의 SecurityContextHolder 처럼, 어느 화면에서든 useAuth() 로 현재 로그인 상태를 얻는다.
// 상태가 바뀌면 이 값을 쓰는 화면이 자동으로 다시 그려진다.
import { useQueryClient } from '@tanstack/react-query';
import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';

import { logout, type MemberSummary, type SignupStatus, type TokenResponse } from '../api/auth';
import { setSessionExpiredHandler, setSignupIncompleteHandler } from '../api/client';
import { errorMessage } from '../api/errors';
import { getMe } from '../api/members';
import { clearSession, getTokens, restoreTokens, setTokens } from './session';

// 화면 분기의 기준이 되는 상태 (docs/api.md 2.1 의 가입 상태 흐름)
//   loading      앱을 막 켜서 저장된 토큰을 확인하는 중
//   signedOut    로그인 안 됨            → 로그인·가입 화면
//   pendingPhone 가입은 했지만 휴대폰 미인증 → 휴대폰 인증 화면
//   active       가입 완료               → 하단 탭 화면
export type AuthStatus = 'loading' | 'signedOut' | 'pendingPhone' | 'active';

type AuthContextValue = {
  status: AuthStatus;
  member: MemberSummary | null;
  // 앱 시작 시 서버에 닿지 못했을 때의 오류 문구. 정상이면 null
  startupError: string | null;
  retryStartup: () => void;
  // 가입·로그인·휴대폰 인증 확인의 응답을 넘기면 토큰을 저장하고 상태를 바꾼다
  signIn: (response: TokenResponse) => Promise<void>;
  signOut: () => Promise<void>;
};

const AuthContext = createContext<AuthContextValue | null>(null);

function toStatus(signupStatus: SignupStatus): AuthStatus {
  switch (signupStatus) {
    case 'ACTIVE':
      return 'active';
    case 'PENDING_PHONE':
      return 'pendingPhone';
    default:
      // SUSPENDED: 정지 회원은 로그인 자체가 거절되므로 여기 올 일은 거의 없다
      return 'signedOut';
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  // useState 는 화면이 기억하는 값이다. set 함수를 부르면 값이 바뀌고 화면이 다시 그려진다
  const [status, setStatus] = useState<AuthStatus>('loading');
  const [member, setMember] = useState<MemberSummary | null>(null);
  const [startupError, setStartupError] = useState<string | null>(null);
  const [startupAttempt, setStartupAttempt] = useState(0);
  const queryClient = useQueryClient();

  // useEffect 는 "화면이 그려진 뒤 실행할 일"이다. 끝의 배열에 든 값이 바뀔 때만 다시 실행된다.
  // 여기서는 앱을 켤 때(그리고 다시 시도할 때) 저장된 토큰으로 로그인 상태를 복원한다
  useEffect(() => {
    let cancelled = false; // 응답이 오기 전에 화면이 사라졌으면 결과를 버린다

    async function restore() {
      const tokens = await restoreTokens();
      if (!tokens) {
        if (!cancelled) {
          setStatus('signedOut');
        }
        return;
      }
      try {
        // 토큰이 아직 유효한지, 가입 상태가 무엇인지 서버에 묻는다.
        // access 토큰이 만료됐다면 API 클라이언트가 알아서 재발급한 뒤 다시 호출한다
        const me = await getMe();
        if (!cancelled) {
          setMember({ id: me.id, nickname: me.nickname, signupStatus: me.signupStatus });
          setStatus(toStatus(me.signupStatus));
        }
      } catch (error) {
        if (cancelled) {
          return;
        }
        if (getTokens()) {
          // 토큰은 남아 있는데 실패했다면 서버에 닿지 못한 것이다. 로그아웃시키지 않고 다시 시도하게 한다
          setStartupError(errorMessage(error));
        } else {
          // 재발급이 거절되어 API 클라이언트가 토큰을 지운 경우
          setStatus('signedOut');
        }
      }
    }

    restore();
    return () => {
      cancelled = true;
    };
  }, [startupAttempt]);

  // API 클라이언트가 보내는 알림을 받는다
  useEffect(() => {
    setSessionExpiredHandler(() => {
      setMember(null);
      setStatus('signedOut');
      queryClient.clear();
    });
    setSignupIncompleteHandler(() => setStatus('pendingPhone'));
    return () => {
      setSessionExpiredHandler(null);
      setSignupIncompleteHandler(null);
    };
  }, [queryClient]);

  const signIn = useCallback(
    async (response: TokenResponse) => {
      // 이전 사용자의 목록이 잠깐이라도 보이지 않도록 캐시를 비운다
      queryClient.clear();
      await setTokens({ accessToken: response.accessToken, refreshToken: response.refreshToken });
      setMember(response.member);
      setStatus(toStatus(response.member.signupStatus));
    },
    [queryClient],
  );

  const signOut = useCallback(async () => {
    const tokens = getTokens();
    if (tokens) {
      try {
        await logout(tokens.refreshToken);
      } catch {
        // 서버 로그아웃이 실패해도(네트워크 끊김 등) 기기에서는 로그아웃한다
      }
    }
    await clearSession();
    queryClient.clear();
    setMember(null);
    setStatus('signedOut');
  }, [queryClient]);

  const retryStartup = useCallback(() => {
    setStartupError(null);
    setStartupAttempt((n) => n + 1);
  }, []);

  return (
    <AuthContext.Provider value={{ status, member, startupError, retryStartup, signIn, signOut }}>
      {children}
    </AuthContext.Provider>
  );
}

// use 로 시작하는 함수를 "훅"이라고 부른다. 화면(컴포넌트) 안에서만 호출할 수 있다
export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) {
    throw new Error('useAuth 는 AuthProvider 안에서만 쓸 수 있습니다.');
  }
  return value;
}
