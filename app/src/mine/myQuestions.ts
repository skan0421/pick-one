// 내 고민 목록의 데이터 다루기. 화면도 서버 호출도 없는 순수 함수다 (src/__tests__/myQuestions.test.ts)
import { ApiError } from '../api/errors';
import { flattenPages as flattenBy, nextCursorOf as nextCursor, type Pages } from '../api/paging';
import type { CursorPage, MyQuestion, QuestionStatus, QuestionType } from '../api/questions';
import { parseServerTime } from '../feed/time';

// 쪽 단위로 쌓인 내 고민 목록 (api/paging.ts 의 Pages 참고)
export type MyQuestionPages = Pages<MyQuestion>;

// 다음 쪽을 받을 때 쓸 커서. 다음 쪽이 없으면 undefined (더 받지 않는다)
export function nextCursorOf(page: CursorPage<MyQuestion>): string | undefined {
  return nextCursor(page);
}

// 여러 쪽을 하나의 목록으로 편다. 같은 id 는 한 번만 넣는다.
// 쪽을 받는 사이에 새 고민을 올리면 목록이 한 칸씩 밀려 앞 쪽의 마지막 항목이 다음 쪽에 또 올 수 있다
export function flattenPages(data: MyQuestionPages | undefined): MyQuestion[] {
  return flattenBy(data, (item) => item.id);
}

// 삭제한 고민을 목록에서 뺀다. 원본은 고치지 않고 새 객체를 만든다.
// 쪽 구조와 커서는 그대로 두므로 이어 받기는 계속된다
export function removeQuestion(data: MyQuestionPages | undefined, id: number): MyQuestionPages | undefined {
  if (!data) {
    return data;
  }
  return {
    ...data,
    pages: data.pages.map((page) => ({ ...page, items: page.items.filter((item) => item.id !== id) })),
  };
}

// 상단 노출에 성공한 고민의 끝나는 시각을 바꾼다. 원본은 고치지 않고 새 객체를 만든다.
// 목록을 다시 받지 않으므로 보던 위치가 유지된다
export function applyBoost(
  data: MyQuestionPages | undefined,
  id: number,
  boostedUntil: string,
): MyQuestionPages | undefined {
  if (!data) {
    return data;
  }
  return {
    ...data,
    pages: data.pages.map((page) => ({
      ...page,
      items: page.items.map((item) => (item.id === id ? { ...item, boostedUntil } : item)),
    })),
  };
}

// 삭제 요청이 실패했을 때, 그래도 목록에서 빼야 하는가.
// QUESTION_NOT_FOUND 는 이미 삭제된 고민이다 (다른 기기에서 지웠거나, 앞선 요청이 반영된 뒤 응답만 받지 못한 경우)
export function isAlreadyDeleted(error: unknown): boolean {
  return error instanceof ApiError && error.code === 'QUESTION_NOT_FOUND';
}

const STATUS_LABELS: Record<QuestionStatus, string> = {
  ACTIVE: '진행 중',
  HIDDEN: '숨김 (신고 누적)',
  CLOSED: '종료',
};

const TYPE_LABELS: Record<QuestionType, string> = {
  TEXT: '글형',
  IMAGE: '사진형',
};

// 서버가 모르는 값을 보내도(나중에 상태가 추가되는 경우) 화면이 깨지지 않게 받은 값을 그대로 보여 준다
export function statusLabel(status: string): string {
  return STATUS_LABELS[status as QuestionStatus] ?? status;
}

export function typeLabel(type: string): string {
  return TYPE_LABELS[type as QuestionType] ?? type;
}

// 지금 상단 노출 중인가. 서버의 피드와 같은 기준이다 (boosted_until > 지금)
export function isBoosted(question: Pick<MyQuestion, 'boostedUntil'>, nowMs: number): boolean {
  if (!question.boostedUntil) {
    return false;
  }
  const until = parseServerTime(question.boostedUntil);
  return until !== null && until > nowMs;
}
