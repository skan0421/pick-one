// 인증 API (docs/api.md 2.2 ~ 2.5)
// type 은 Java 의 record(DTO) 에 해당한다. 백엔드 DTO 와 필드 이름을 맞춘다.
import { request } from './client';

// 가입 상태 (docs/api.md 2.1). 문자열 세 개 중 하나만 허용하는 타입으로, Java enum 과 같은 역할이다
export type SignupStatus = 'PENDING_PHONE' | 'ACTIVE' | 'SUSPENDED';

export type MemberSummary = {
  id: number;
  nickname: string;
  signupStatus: SignupStatus;
};

// 백엔드 TokenResponse. 가입·로그인·재발급·휴대폰 인증 확인이 모두 이 모양으로 응답한다
export type TokenResponse = {
  member: MemberSummary;
  accessToken: string;
  refreshToken: string;
};

export type SignupRequest = {
  email: string;
  password: string;
  nickname: string;
};

export type LoginRequest = {
  email: string;
  password: string;
};

export function signup(body: SignupRequest): Promise<TokenResponse> {
  return request<TokenResponse>('/auth/signup', { method: 'POST', body, auth: false });
}

export function login(body: LoginRequest): Promise<TokenResponse> {
  return request<TokenResponse>('/auth/login', { method: 'POST', body, auth: false });
}

// 서버의 refresh 토큰을 폐기한다. access 토큰(헤더)과 refresh 토큰(본문)이 모두 필요하다
export function logout(refreshToken: string): Promise<void> {
  return request<void>('/auth/logout', { method: 'POST', body: { refreshToken } });
}
