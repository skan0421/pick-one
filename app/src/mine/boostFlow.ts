// 상단 노출 한 번의 흐름: 키를 정하고, 요청을 보내고, 결과에 따라 키를 어떻게 할지 정한다 (docs/api.md 6.3).
// 서버 호출 함수를 인자로 받는 순수 로직이다 (src/__tests__/boostFlow.test.ts). feed/voteFlow.ts 와 같은 구조다
import { ApiError, errorMessage } from '../api/errors';
import type { BoostResponse } from '../api/points';
import type { MyQuestion } from '../api/questions';
import { parseServerTime } from '../feed/time';
import type { BoostKeys } from './boostKeys';

// 상단 노출 비용과 시간 (docs/api.md 1.7 의 기본값).
// 서버에는 비용을 미리 알려 주는 API 가 없어 확인 창에는 이 값을 보여 준다. 실제로 빠진 금액은 응답의 cost 다
export const BOOST_COST = 100;
export const BOOST_HOURS = 24;

export type BoostApi = {
  boost: (questionId: number, idempotencyKey: string) => Promise<BoostResponse>;
};

// 상단 노출 시도의 결과
//   boosted      성공 (같은 키의 재요청에 서버가 처음 결과를 돌려준 경우도 포함)
//   insufficient 잔액 부족
//   closed       종료되었거나 숨겨진 고민
//   gone         삭제되었거나 내 것이 아닌 고민
//   keyConflict  키가 다른 요청에 이미 쓰였다. 키를 버렸으므로 다시 누르면 새 키로 나간다
//   retry        결과를 모른다 (네트워크 오류, 지갑 충돌, 서버 오류). 키를 유지하므로 다시 눌러도 두 번 빠지지 않는다
export type BoostOutcome =
  | { kind: 'boosted'; response: BoostResponse }
  | { kind: 'insufficient' | 'closed' | 'gone' | 'keyConflict' | 'retry'; message: string };

// 서버가 분명히 거절한 경우의 안내. 이때 포인트는 빠지지 않았다
const REJECTIONS: Record<string, { kind: 'insufficient' | 'closed' | 'gone'; message: string }> = {
  POINT_INSUFFICIENT: { kind: 'insufficient', message: '포인트가 부족해요. 피드에서 투표하면 1P 씩 모을 수 있어요.' },
  QUESTION_CLOSED: { kind: 'closed', message: '종료되었거나 숨겨진 고민은 상단에 올릴 수 없어요.' },
  QUESTION_NOT_FOUND: { kind: 'gone', message: '삭제되었거나 찾을 수 없는 고민이에요.' },
  FORBIDDEN: { kind: 'gone', message: '내가 올린 고민만 상단에 올릴 수 있어요.' },
};

// 예외를 던지지 않는다. 어떤 실패든 BoostOutcome 으로 바꿔 돌려준다
export async function submitBoost(api: BoostApi, keys: BoostKeys, questionId: number): Promise<BoostOutcome> {
  // 앞선 시도가 끝나지 않았다면 그때의 키가 나온다
  const key = keys.keyFor(questionId);
  try {
    const response = await api.boost(questionId, key);
    keys.settle(questionId);
    return { kind: 'boosted', response };
  } catch (error) {
    if (error instanceof ApiError) {
      if (error.code === 'IDEMPOTENCY_KEY_CONFLICT') {
        keys.discard(questionId);
        return { kind: 'keyConflict', message: '요청이 겹쳤어요. 다시 시도해 주세요.' };
      }
      const rejection = REJECTIONS[error.code];
      if (rejection) {
        return rejection;
      }
      if (error.code === 'POINT_WALLET_CONFLICT') {
        return { kind: 'retry', message: '포인트 처리가 몰렸어요. 잠시 후 다시 시도해 주세요.' };
      }
    }
    // 서버가 처리했는지 알 수 없다. 키를 그대로 두어 다시 시도할 때 같은 키가 나가게 한다
    return { kind: 'retry', message: `${errorMessage(error)} 다시 시도해도 포인트는 한 번만 차감돼요.` };
  }
}

// 확인 창에 보여 줄 내용
export type BoostPreview = {
  cost: number;
  balance: number | undefined; // 잔액을 아직 받지 못했으면 undefined
  balanceAfter: number | undefined;
  affordable: boolean; // 잔액을 모르면 true (서버가 판정한다)
  extending: boolean; // 지금 노출 중이라 이어 붙이는 경우
};

export function previewBoost(
  question: Pick<MyQuestion, 'boostedUntil'>,
  balance: number | undefined,
  nowMs: number,
): BoostPreview {
  const until = question.boostedUntil ? parseServerTime(question.boostedUntil) : null;
  return {
    cost: BOOST_COST,
    balance,
    balanceAfter: balance === undefined ? undefined : balance - BOOST_COST,
    affordable: balance === undefined || balance >= BOOST_COST,
    extending: until !== null && until > nowMs,
  };
}
