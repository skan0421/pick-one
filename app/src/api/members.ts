// 회원 API (docs/api.md 2.7, 2.8)
import type { SignupStatus } from './auth';
import { request } from './client';

export type MemberResponse = {
  id: number;
  email: string;
  nickname: string;
  provider: string;
  signupStatus: SignupStatus;
  phoneVerified: boolean;
  hideFromContacts: boolean;
  // 주의: 서버가 항상 0 을 준다. 잔액은 GET /points/balance (api/points.ts) 로 받는다
  pointBalance: number;
  createdAt: string; // 시각은 문자열로 온다
};

export function getMe(): Promise<MemberResponse> {
  return request<MemberResponse>('/members/me');
}

// PATCH /members/me (docs/api.md 2.8). 닉네임을 바꾼다.
// 주요 에러: VALIDATION_ERROR(형식), MEMBER_NICKNAME_DUPLICATE(이미 쓰는 닉네임)
export function updateNickname(nickname: string): Promise<MemberResponse> {
  return request<MemberResponse>('/members/me', { method: 'PATCH', body: { nickname } });
}
