// 고민 등록 요청 한 번의 흐름: 키를 정하고, 요청을 보내고, 결과에 따라 키를 어떻게 할지 정한다 (docs/api.md 4.1).
// 서버 호출 함수를 인자로 받는 순수 로직이다 (src/__tests__/createFlow.test.ts). mine/boostFlow.ts 와 같은 구조다
//
// 글형은 화면이 바로 부르고, 사진형은 uploadFlow.ts 가 사진을 다 올린 뒤에 부른다.
// 그래서 키는 등록 요청에만 붙는다. 사진을 올리는 요청(발급, PUT)에는 붙지 않는다
import { ApiError, NETWORK_ERROR } from '../api/errors';
import type { CreateQuestionRequest, QuestionResponse } from '../api/questions';
import type { ComposeKey } from './composeKey';

export type CreateApi = {
  createQuestion: (body: CreateQuestionRequest, idempotencyKey: string) => Promise<QuestionResponse>;
};

// 결과를 모르는 실패 뒤에 덧붙이는 안내
export const RETRY_HINT = '다시 시도해도 고민은 한 번만 등록돼요.';
// 같은 키로 다른 내용을 보냈을 때의 안내. 앞선 요청이 등록됐는데 응답을 받지 못한 뒤 내용을 고쳐 다시 누른 경우다
export const KEY_CONFLICT_MESSAGE =
  '앞서 보낸 고민이 이미 등록됐을 수 있어요. 내 고민 탭에서 확인한 뒤 다시 올려 주세요.';

// 실패하면 예외를 던진다. 던지는 예외는 화면에 그대로 보여 줄 수 있게 문구를 다듬은 것이다
//   성공                      키를 버린다
//   IDEMPOTENCY_KEY_CONFLICT  키를 버린다. 다시 누르면 새 키로 나간다
//   결과를 모르는 실패        키를 유지한다. 다시 눌러도 같은 키가 나가 두 번 등록되지 않는다
//   서버가 분명히 거절        키를 유지한다. 등록된 것이 없으므로 고친 내용을 같은 키로 보내도 된다
export async function createWithKey(
  api: CreateApi,
  key: ComposeKey,
  body: CreateQuestionRequest,
): Promise<QuestionResponse> {
  // 앞선 시도가 끝나지 않았다면 그때의 키가 나온다
  const idempotencyKey = key.use();
  try {
    const question = await api.createQuestion(body, idempotencyKey);
    key.settle();
    return question;
  } catch (error) {
    if (error instanceof ApiError && error.code === 'IDEMPOTENCY_KEY_CONFLICT') {
      key.discard();
      throw new ApiError(error.status, error.code, KEY_CONFLICT_MESSAGE);
    }
    if (isUnknownOutcome(error)) {
      throw withRetryHint(error);
    }
    throw error;
  }
}

// 서버가 등록했는지 알 수 없는 실패인가.
// 서버에 닿지 못했거나 응답이 없었던 경우(NETWORK_ERROR, 시간 초과 포함), 서버 오류(5xx), 예상하지 못한 예외
export function isUnknownOutcome(error: unknown): boolean {
  if (!(error instanceof ApiError)) {
    return true;
  }
  return error.code === NETWORK_ERROR || error.status >= 500;
}

function withRetryHint(error: unknown): ApiError {
  if (error instanceof ApiError) {
    return new ApiError(error.status, error.code, `${error.message} ${RETRY_HINT}`, error.fieldErrors);
  }
  const message = error instanceof Error ? error.message : '알 수 없는 오류가 발생했습니다.';
  return new ApiError(0, NETWORK_ERROR, `${message} ${RETRY_HINT}`);
}
