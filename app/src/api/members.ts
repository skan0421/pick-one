// 회원 API (docs/api.md 2.7)
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
  pointBalance: number;
  createdAt: string; // 시각은 문자열로 온다
};

export function getMe(): Promise<MemberResponse> {
  return request<MemberResponse>('/members/me');
}
