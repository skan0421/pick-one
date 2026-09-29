// 휴대폰 인증 API (docs/api.md 3.3, 3.4). PENDING_PHONE 회원도 호출할 수 있다
import type { TokenResponse } from './auth';
import { request } from './client';

export type SendVerificationResponse = {
  expiresInSeconds: number; // 인증번호 유효 시간
  cooldownSeconds: number; // 다시 요청할 수 있을 때까지 남은 시간
};

// 인증번호 발송 요청. 로컬 서버는 문자를 보내지 않고 서버 로그에 인증번호를 찍는다
export function sendVerification(phone: string): Promise<SendVerificationResponse> {
  return request<SendVerificationResponse>('/phone-verifications', { method: 'POST', body: { phone } });
}

// 인증번호 확인. 성공하면 회원이 ACTIVE 가 되고 새 토큰을 받는다.
// access 토큰 안에 가입 상태가 들어 있어서, 옛 토큰을 계속 쓰면 여전히 403 SIGNUP_INCOMPLETE 가 난다.
// 호출한 쪽은 반드시 응답의 토큰으로 교체해야 한다
export function confirmVerification(phone: string, code: string): Promise<TokenResponse> {
  return request<TokenResponse>('/phone-verifications/confirm', { method: 'POST', body: { phone, code } });
}
