// 포인트 API (docs/api.md 6.1). ACTIVE 회원만 호출할 수 있다
import { request } from './client';

export type PointBalance = {
  balance: number;
  todayEarned: number; // 오늘 투표로 받은 포인트
  dailyEarnLimit: number; // 하루 적립 한도 (50)
};

export function getBalance(): Promise<PointBalance> {
  return request<PointBalance>('/points/balance');
}
