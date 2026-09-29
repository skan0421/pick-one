// 포인트 내역을 사람이 읽는 글로 바꾼다. 순수 함수 (src/__tests__/ledger.test.ts)
import type { LedgerItem } from '../api/points';

// 서버의 TxType (docs/api.md 6.2)
const TX_LABELS: Record<string, string> = {
  VOTE_REWARD: '투표 참여',
  BOOST_USE: '상단 노출',
};

// 서버가 모르는 값을 보내도(나중에 종류가 추가되는 경우) 화면이 깨지지 않게 받은 값을 그대로 보여 준다
export function txLabel(txType: string): string {
  return TX_LABELS[txType] ?? txType;
}

// "+1P", "-100P". 부호를 항상 붙인다. 0 은 "0P"
export function formatAmount(amount: number): string {
  if (amount > 0) {
    return `+${amount}P`;
  }
  return `${amount}P`;
}

// 적립인가 사용인가. 화면에서 색을 고를 때 쓴다
export function isEarning(item: Pick<LedgerItem, 'amount'>): boolean {
  return item.amount > 0;
}
