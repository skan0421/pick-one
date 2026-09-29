// 포인트 내역 표시 테스트
import { formatAmount, isEarning, txLabel } from '../my/ledger';

describe('txLabel', () => {
  it.each([
    ['VOTE_REWARD', '투표 참여'],
    ['BOOST_USE', '상단 노출'],
  ])('%s → %s', (txType, expected) => {
    expect(txLabel(txType)).toBe(expected);
  });

  it('모르는 종류는 받은 값을 그대로 보여 준다', () => {
    expect(txLabel('GIFT_SEND')).toBe('GIFT_SEND');
  });
});

describe('formatAmount', () => {
  it.each([
    [1, '+1P'],
    [50, '+50P'],
    [-100, '-100P'],
    [0, '0P'],
  ])('%d → %s', (amount, expected) => {
    expect(formatAmount(amount)).toBe(expected);
  });
});

describe('isEarning', () => {
  it('양수만 적립이다', () => {
    expect(isEarning({ amount: 1 })).toBe(true);
    expect(isEarning({ amount: -100 })).toBe(false);
    expect(isEarning({ amount: 0 })).toBe(false);
  });
});
