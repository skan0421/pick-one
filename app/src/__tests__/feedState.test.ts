// 피드 상태 전이 테스트. 화면도 서버도 없이 순수 함수만 검증한다
import type { CursorPage, FeedItem } from '../api/questions';
import {
  currentCard,
  feedReducer,
  initialFeedState,
  isExhausted,
  remainingAfterCurrent,
  shouldPrefetch,
  type FeedAction,
  type FeedState,
  type ResultView,
} from '../feed/feedState';

function card(id: number): FeedItem {
  return {
    id,
    questionType: 'TEXT',
    content: `고민 ${id}`,
    boosted: false,
    options: [
      { id: id * 10 + 1, sortOrder: 1, content: '가' },
      { id: id * 10 + 2, sortOrder: 2, content: '나' },
    ],
    author: { nickname: '작성자' },
    createdAt: '2026-09-29T12:00:00',
  };
}

function page(ids: number[], nextCursor?: string): CursorPage<FeedItem> {
  return { items: ids.map(card), nextCursor, hasNext: nextCursor !== undefined };
}

// 액션을 차례로 적용한 결과
function run(actions: FeedAction[], from: FeedState = initialFeedState): FeedState {
  return actions.reduce(feedReducer, from);
}

const RESULT: ResultView = {
  totalVotes: 3,
  myOptionId: 11,
  options: [
    { optionId: 11, count: 2, percent: 66.7 },
    { optionId: 12, count: 1, percent: 33.3 },
  ],
};

// 카드 n 장을 받고 첫 카드의 결과를 보고 있는 상태
function votedOnFirst(ids: number[], nextCursor?: string): FeedState {
  return run([
    { type: 'pageLoaded', page: page(ids, nextCursor) },
    { type: 'voteStarted', optionId: 11 },
    { type: 'voteSucceeded', result: RESULT, notice: '+1P' },
  ]);
}

describe('목록 받기', () => {
  it('처음에는 첫 쪽을 받는 중이고 카드가 없다', () => {
    expect(initialFeedState.load).toBe('initial');
    expect(currentCard(initialFeedState)).toBeUndefined();
    expect(isExhausted(initialFeedState)).toBe(false);
  });

  it('첫 쪽을 받으면 첫 카드를 고르는 단계가 된다', () => {
    const state = run([{ type: 'pageLoaded', page: page([1, 2, 3], 'c1') }]);

    expect(state.cards.map((c) => c.id)).toEqual([1, 2, 3]);
    expect(currentCard(state)?.id).toBe(1);
    expect(state.phase).toEqual({ kind: 'choosing' });
    expect(state.load).toBe('idle');
    expect(state.nextCursor).toBe('c1');
    expect(state.hasNext).toBe(true);
  });

  it('받기 실패는 error 로 남고, 다시 받기 시작하면 문구가 지워진다', () => {
    const failed = run([{ type: 'loadFailed', message: '서버에 연결할 수 없습니다.' }]);
    expect(failed.load).toBe('error');
    expect(failed.loadError).toBe('서버에 연결할 수 없습니다.');

    const retrying = run([{ type: 'loadStarted' }], failed);
    expect(retrying.load).toBe('more');
    expect(retrying.loadError).toBeUndefined();
  });

  it('커서 없이 hasNext 만 true 로 오면 다음이 없는 것으로 본다', () => {
    const state = run([{ type: 'pageLoaded', page: { items: [card(1)], hasNext: true } }]);
    expect(state.hasNext).toBe(false);
  });
});

describe('중복 제거', () => {
  it('다음 쪽에 이미 받은 id 가 섞여 오면 한 번만 넣는다', () => {
    const state = run([
      { type: 'pageLoaded', page: page([1, 2, 3], 'c1') },
      { type: 'pageLoaded', page: page([3, 4, 2, 5], 'c2') },
    ]);
    expect(state.cards.map((c) => c.id)).toEqual([1, 2, 3, 4, 5]);
  });

  it('한 쪽 안에서 같은 id 가 두 번 와도 한 번만 넣는다', () => {
    const state = run([{ type: 'pageLoaded', page: page([1, 2, 2, 1, 3]) }]);
    expect(state.cards.map((c) => c.id)).toEqual([1, 2, 3]);
  });

  it('이미 지나간 카드가 다시 와도 넣지 않고, 보던 카드도 바뀌지 않는다', () => {
    const state = run([{ type: 'next' }, { type: 'pageLoaded', page: page([1, 4], 'c2') }], votedOnFirst([1, 2, 3], 'c1'));

    expect(state.cards.map((c) => c.id)).toEqual([1, 2, 3, 4]);
    expect(currentCard(state)?.id).toBe(2);
  });

  it('받은 쪽이 전부 중복이어도 커서는 앞으로 나아간다', () => {
    const state = run([
      { type: 'pageLoaded', page: page([1, 2], 'c1') },
      { type: 'pageLoaded', page: page([2, 1], 'c2') },
    ]);
    expect(state.cards).toHaveLength(2);
    expect(state.nextCursor).toBe('c2');
  });
});

describe('미리 불러오기 조건', () => {
  // 카드 total 장 중 position 번째(0부터)를 보고 있는 상태
  function at(total: number, position: number, nextCursor?: string): FeedState {
    const ids = Array.from({ length: total }, (_, i) => i + 1);
    return { ...run([{ type: 'pageLoaded', page: page(ids, nextCursor) }]), index: position };
  }

  it('남은 카드가 4장이면 받지 않는다', () => {
    const state = at(20, 15, 'c1');
    expect(remainingAfterCurrent(state)).toBe(4);
    expect(shouldPrefetch(state)).toBe(false);
  });

  it('남은 카드가 3장이 되면 받는다', () => {
    const state = at(20, 16, 'c1');
    expect(remainingAfterCurrent(state)).toBe(3);
    expect(shouldPrefetch(state)).toBe(true);
  });

  it('카드를 다 봤을 때도 받는다', () => {
    const state = at(20, 20, 'c1');
    expect(remainingAfterCurrent(state)).toBe(0);
    expect(shouldPrefetch(state)).toBe(true);
  });

  it('다음 쪽이 없으면 받지 않는다', () => {
    expect(shouldPrefetch(at(20, 19))).toBe(false);
  });

  it('이미 받는 중이면 받지 않는다', () => {
    expect(shouldPrefetch(run([{ type: 'loadStarted' }], at(20, 18, 'c1')))).toBe(false);
  });

  it('받기에 실패한 상태에서는 자동으로 다시 받지 않는다', () => {
    expect(shouldPrefetch(run([{ type: 'loadFailed', message: '실패' }], at(20, 18, 'c1')))).toBe(false);
  });

  it('첫 쪽을 받기 전에는 받지 않는다 (첫 쪽 요청이 따로 나간다)', () => {
    expect(shouldPrefetch(initialFeedState)).toBe(false);
  });
});

describe('투표 단계', () => {
  it('선택하면 요청 중이 된다', () => {
    const state = run([
      { type: 'pageLoaded', page: page([1, 2]) },
      { type: 'voteStarted', optionId: 11 },
    ]);
    expect(state.phase).toEqual({ kind: 'submitting', optionId: 11 });
  });

  it('요청 중에 또 선택하면 무시한다 (두 번 눌림 방지)', () => {
    const state = run([
      { type: 'pageLoaded', page: page([1, 2]) },
      { type: 'voteStarted', optionId: 11 },
      { type: 'voteStarted', optionId: 12 },
    ]);
    expect(state.phase).toEqual({ kind: 'submitting', optionId: 11 });
  });

  it('카드가 없으면 선택을 무시한다', () => {
    const state = run([{ type: 'voteStarted', optionId: 11 }]);
    expect(state.phase).toEqual({ kind: 'choosing' });
  });

  it('투표에 성공하면 결과와 포인트 안내가 나온다', () => {
    const state = votedOnFirst([1, 2]);

    expect(state.phase).toEqual({ kind: 'result', result: RESULT });
    expect(state.notice).toEqual({ seq: 1, text: '+1P' });
    expect(currentCard(state)?.id).toBe(1);
  });

  it('이미 투표한 고민이면 안내 없이 결과만 보여 준다', () => {
    const state = run([
      { type: 'pageLoaded', page: page([1, 2]) },
      { type: 'voteStarted', optionId: 11 },
      { type: 'resultLoaded', result: RESULT },
    ]);
    expect(state.phase).toEqual({ kind: 'result', result: RESULT });
    expect(state.notice).toBeUndefined();
  });

  it('다시 시도할 수 있는 실패면 같은 카드에서 다시 고르게 한다', () => {
    const state = run([
      { type: 'pageLoaded', page: page([1, 2]) },
      { type: 'voteStarted', optionId: 11 },
      { type: 'voteFailed', message: '서버에 연결할 수 없습니다.' },
    ]);
    expect(state.phase).toEqual({ kind: 'choosing' });
    expect(currentCard(state)?.id).toBe(1);
    expect(state.notice?.text).toBe('서버에 연결할 수 없습니다.');
  });

  it('요청 중이 아닐 때 온 투표 결과는 무시한다', () => {
    const before = run([{ type: 'pageLoaded', page: page([1, 2]) }]);
    expect(run([{ type: 'voteSucceeded', result: RESULT }], before)).toBe(before);
    expect(run([{ type: 'resultLoaded', result: RESULT }], before)).toBe(before);
    expect(run([{ type: 'voteFailed', message: '실패' }], before)).toBe(before);
  });
});

describe('다음 카드로 이동', () => {
  it('결과를 본 뒤 다음으로 넘기면 다음 카드를 고르는 단계가 된다', () => {
    const state = run([{ type: 'next' }], votedOnFirst([1, 2]));

    expect(currentCard(state)?.id).toBe(2);
    expect(state.phase).toEqual({ kind: 'choosing' });
  });

  it('투표하지 않은 카드는 넘길 수 없다', () => {
    const before = run([{ type: 'pageLoaded', page: page([1, 2]) }]);
    expect(run([{ type: 'next' }], before)).toBe(before);
  });

  it('투표할 수 없는 카드는 안내와 함께 넘긴다', () => {
    const state = run([
      { type: 'pageLoaded', page: page([1, 2]) },
      { type: 'voteStarted', optionId: 11 },
      { type: 'cardSkipped', notice: '이미 종료된 고민이에요' },
    ]);
    expect(currentCard(state)?.id).toBe(2);
    expect(state.phase).toEqual({ kind: 'choosing' });
    expect(state.notice?.text).toBe('이미 종료된 고민이에요');
  });

  it('마지막 카드를 넘겼고 다음 쪽이 없으면 더 보여 줄 것이 없다', () => {
    const state = run([{ type: 'next' }], votedOnFirst([1]));

    expect(currentCard(state)).toBeUndefined();
    expect(isExhausted(state)).toBe(true);
  });

  it('마지막 카드를 넘겼어도 다음 쪽이 있으면 아직 끝이 아니다', () => {
    const state = run([{ type: 'next' }], votedOnFirst([1], 'c1'));

    expect(currentCard(state)).toBeUndefined();
    expect(isExhausted(state)).toBe(false);
    expect(shouldPrefetch(state)).toBe(true);
  });

  it('빈 목록을 받으면 더 보여 줄 것이 없다', () => {
    expect(isExhausted(run([{ type: 'pageLoaded', page: page([]) }]))).toBe(true);
  });
});

describe('안내와 새로고침', () => {
  it('안내 번호는 매번 늘어난다', () => {
    const first = votedOnFirst([1, 2, 3]);
    const second = run(
      [{ type: 'next' }, { type: 'voteStarted', optionId: 21 }, { type: 'voteSucceeded', result: RESULT, notice: '+1P' }],
      first,
    );
    expect(first.notice?.seq).toBe(1);
    expect(second.notice?.seq).toBe(2);
  });

  it('안내를 닫으면 사라진다', () => {
    expect(run([{ type: 'noticeDismissed' }], votedOnFirst([1, 2])).notice).toBeUndefined();
  });

  it('새로고침하면 카드와 위치가 처음으로 돌아가고, 지나간 카드도 다시 받을 수 있다', () => {
    const state = run([{ type: 'next' }, { type: 'reset' }], votedOnFirst([1, 2], 'c1'));

    expect(state.cards).toEqual([]);
    expect(state.index).toBe(0);
    expect(state.load).toBe('initial');
    expect(state.nextCursor).toBeUndefined();

    const reloaded = run([{ type: 'pageLoaded', page: page([1, 2]) }], state);
    expect(reloaded.cards.map((c) => c.id)).toEqual([1, 2]);
  });
});
