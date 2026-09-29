// 포인트 API (docs/api.md 6.1 ~ 6.3). ACTIVE 회원만 호출할 수 있다
import { request } from './client';
import { pageQuery } from './paging';
import type { CursorPage } from './questions';

export type PointBalance = {
  balance: number;
  todayEarned: number; // 오늘 투표로 받은 포인트
  dailyEarnLimit: number; // 하루 적립 한도 (50)
};

export function getBalance(): Promise<PointBalance> {
  return request<PointBalance>('/points/balance');
}

// 포인트 내역 한 줄. 서버의 point_ledger 한 행이다 (원장: 넣기만 하고 고치거나 지우지 않는 기록)
export type LedgerItem = {
  id: number;
  amount: number; // 적립은 양수, 사용은 음수
  balanceAfter: number; // 이 내역이 처리된 직후의 잔액
  txType: string; // 'VOTE_REWARD' | 'BOOST_USE'. 나중에 늘어날 수 있어 string 으로 받는다
  refType: string; // 'VOTE' | 'QUESTION'
  refId: number; // 투표 id 또는 고민 id
  createdAt: string;
};

// GET /points/ledger (docs/api.md 6.2). 최신순
export function getLedger(cursor?: string, size = 20): Promise<CursorPage<LedgerItem>> {
  return request<CursorPage<LedgerItem>>(`/points/ledger?${pageQuery(cursor, size)}`);
}

export type BoostResponse = {
  questionId: number;
  boostedUntil: string; // 상단 노출이 끝나는 시각
  cost: number; // 차감된 포인트
  balanceAfter: number;
  ledgerId: number;
};

// POST /questions/{id}/boosts (docs/api.md 6.3). 100P 를 쓰고 24시간 동안 피드 위쪽에 보인다.
//
// idempotencyKey 는 "이 결제 의도"의 이름표다 (docs/api.md 1.6).
// 서버는 같은 이름표가 다시 오면 포인트를 또 빼지 않고 처음 처리한 결과를 돌려준다.
// 그래서 응답을 받지 못해 다시 보낼 때는 반드시 같은 값을 넣어야 한다. 값을 정하는 규칙은 mine/boostKeys.ts 에 있다
export function boostQuestion(questionId: number, idempotencyKey: string): Promise<BoostResponse> {
  return request<BoostResponse>(`/questions/${questionId}/boosts`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
  });
}
