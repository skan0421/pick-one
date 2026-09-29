// 내 고민 목록 데이터 테스트
import { ApiError } from '../api/errors';
import type { CursorPage, MyQuestion } from '../api/questions';
import {
  flattenPages,
  isAlreadyDeleted,
  isBoosted,
  nextCursorOf,
  removeQuestion,
  statusLabel,
  typeLabel,
  type MyQuestionPages,
} from '../mine/myQuestions';

function question(id: number): MyQuestion {
  return {
    id,
    questionType: 'TEXT',
    content: `고민 ${id}`,
    status: 'ACTIVE',
    totalVotes: 0,
    options: [
      { id: id * 10 + 1, sortOrder: 1, content: '가', count: 0, percent: 0 },
      { id: id * 10 + 2, sortOrder: 2, content: '나', count: 0, percent: 0 },
    ],
    createdAt: '2026-09-30T12:00:00',
  };
}

function page(ids: number[], nextCursor?: string): CursorPage<MyQuestion> {
  return { items: ids.map(question), nextCursor, hasNext: nextCursor !== undefined };
}

function pages(...list: CursorPage<MyQuestion>[]): MyQuestionPages {
  return { pages: list, pageParams: list.map((_, i) => (i === 0 ? undefined : `c${i}`)) };
}

describe('nextCursorOf', () => {
  it('다음 쪽이 있으면 커서를 돌려준다', () => {
    expect(nextCursorOf(page([1, 2], 'c1'))).toBe('c1');
  });

  it('다음 쪽이 없으면 undefined', () => {
    expect(nextCursorOf(page([1, 2]))).toBeUndefined();
    // hasNext 가 false 면 커서가 남아 있어도 더 받지 않는다
    expect(nextCursorOf({ items: [], nextCursor: 'c1', hasNext: false })).toBeUndefined();
  });
});

describe('flattenPages', () => {
  it('받은 순서대로 하나의 목록으로 편다', () => {
    expect(flattenPages(pages(page([5, 4], 'c1'), page([3, 2], 'c2'), page([1]))).map((q) => q.id)).toEqual([
      5, 4, 3, 2, 1,
    ]);
  });

  it('같은 id 가 다음 쪽에 또 오면 한 번만 넣는다', () => {
    expect(flattenPages(pages(page([5, 4], 'c1'), page([4, 3]))).map((q) => q.id)).toEqual([5, 4, 3]);
  });

  it('아직 받은 것이 없으면 빈 목록', () => {
    expect(flattenPages(undefined)).toEqual([]);
    expect(flattenPages(pages(page([])))).toEqual([]);
  });
});

describe('removeQuestion', () => {
  it('해당 고민만 빼고 쪽 구조와 커서는 유지한다', () => {
    const before = pages(page([5, 4], 'c1'), page([3, 2]));

    const after = removeQuestion(before, 3);

    expect(flattenPages(after).map((q) => q.id)).toEqual([5, 4, 2]);
    expect(after?.pages).toHaveLength(2);
    expect(after?.pages[0].nextCursor).toBe('c1');
    expect(after?.pageParams).toEqual(before.pageParams);
  });

  it('원본을 고치지 않는다', () => {
    const before = pages(page([5, 4]));
    removeQuestion(before, 5);
    expect(before.pages[0].items.map((q) => q.id)).toEqual([5, 4]);
  });

  it('없는 id 면 내용이 그대로다', () => {
    expect(flattenPages(removeQuestion(pages(page([5, 4])), 99)).map((q) => q.id)).toEqual([5, 4]);
  });

  it('쪽의 항목을 모두 지워도 쪽은 남는다 (다음 쪽 이어 받기가 끊기지 않게)', () => {
    const after = removeQuestion(pages(page([5], 'c1')), 5);
    expect(after?.pages).toHaveLength(1);
    expect(nextCursorOf(after!.pages[0])).toBe('c1');
  });

  it('받은 것이 없으면 그대로 undefined', () => {
    expect(removeQuestion(undefined, 1)).toBeUndefined();
  });
});

describe('isAlreadyDeleted', () => {
  it('QUESTION_NOT_FOUND 는 이미 삭제된 것으로 본다', () => {
    expect(isAlreadyDeleted(new ApiError(404, 'QUESTION_NOT_FOUND', '고민을 찾을 수 없습니다.'))).toBe(true);
  });

  it('그 밖의 실패는 삭제된 것으로 보지 않는다', () => {
    expect(isAlreadyDeleted(new ApiError(403, 'FORBIDDEN', '권한이 없습니다.'))).toBe(false);
    expect(isAlreadyDeleted(new ApiError(0, 'NETWORK_ERROR', '서버에 연결할 수 없습니다.'))).toBe(false);
    expect(isAlreadyDeleted(new Error('오류'))).toBe(false);
  });
});

describe('표시용 글', () => {
  it('상태와 유형을 한국어로 바꾼다', () => {
    expect(statusLabel('ACTIVE')).toBe('진행 중');
    expect(statusLabel('HIDDEN')).toBe('숨김 (신고 누적)');
    expect(statusLabel('CLOSED')).toBe('종료');
    expect(typeLabel('TEXT')).toBe('글형');
    expect(typeLabel('IMAGE')).toBe('사진형');
  });

  it('모르는 값은 받은 그대로 보여 준다', () => {
    expect(statusLabel('ARCHIVED')).toBe('ARCHIVED');
    expect(typeLabel('VIDEO')).toBe('VIDEO');
  });
});

describe('isBoosted', () => {
  // KST 2026-09-30 12:00 = UTC 03:00
  const now = Date.UTC(2026, 8, 30, 3, 0, 0);

  it('끝나는 시각이 지금보다 뒤면 상단 노출 중', () => {
    expect(isBoosted({ boostedUntil: '2026-09-30T12:00:01' }, now)).toBe(true);
  });

  it('끝났거나 쓴 적이 없으면 아니다', () => {
    expect(isBoosted({ boostedUntil: '2026-09-30T12:00:00' }, now)).toBe(false);
    expect(isBoosted({ boostedUntil: '2026-09-29T12:00:00' }, now)).toBe(false);
    expect(isBoosted({}, now)).toBe(false);
  });

  it('읽을 수 없는 시각이면 아니다', () => {
    expect(isBoosted({ boostedUntil: '내일' }, now)).toBe(false);
  });
});
