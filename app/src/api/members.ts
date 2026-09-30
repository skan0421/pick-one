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
  // 지갑 잔액. GET /points/balance 의 balance 와 같은 값이다. 휴대폰 인증 전에는 지갑이 없어 0 이다.
  // 화면은 이 값을 쓰지 않고 GET /points/balance (api/points.ts) 를 쓴다. 오늘 적립과 상한이 함께 필요하기 때문이다
  pointBalance: number;
  createdAt: string; // 시각은 문자열로 온다
};

export function getMe(): Promise<MemberResponse> {
  return request<MemberResponse>('/members/me');
}

// PATCH /members/me (docs/api.md 2.8). 닉네임을 바꾼다.
// 서버는 보낸 필드만 바꾼다. nickname 을 빼고 보내면 아무것도 바꾸지 않고 현재 정보를 돌려준다
// 주요 에러: VALIDATION_ERROR(형식), MEMBER_NICKNAME_DUPLICATE(이미 쓰는 닉네임)
export function updateNickname(nickname: string): Promise<MemberResponse> {
  return request<MemberResponse>('/members/me', { method: 'PATCH', body: { nickname } });
}
