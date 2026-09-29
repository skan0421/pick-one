// 피드 진행자 테스트. 가짜 서버 함수를 넣어 "언제 무엇을 요청하는지"를 검증한다.
// 화면(React)은 쓰지 않는다
import { ApiError } from '../api/errors';
import type { CursorPage, FeedItem } from '../api/questions';
import type { VoteResponse } from '../api/votes';
import { FeedController, type FeedApi } from '../feed/feedController';
import { currentCard, isExhausted } from '../feed/feedState';

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

// from 부터 to 까지의 id 로 만든 한 쪽
function page(from: number, to: number, nextCursor?: string): CursorPage<FeedItem> {
  const items: FeedItem[] = [];
  for (let id = from; id <= to; id += 1) {
    items.push(card(id));
  }
  return { items, nextCursor, hasNext: nextCursor !== undefined };
}

function voteResponse(optionId: number, earned = true): VoteResponse {
  return {
    voteId: 1,
    result: { totalVotes: 1, myOptionId: optionId, options: [{ optionId, count: 1, percent: 100 }] },
    pointReward: earned ? { earned: true, amount: 1 } : { earned: false, amount: 0, reason: 'DAILY_LIMIT_REACHED' },
  };
}

// 나중에 원하는 때에 응답을 돌려줄 수 있는 Promise (Java 의 CompletableFuture 를 직접 complete 하는 것과 같다)
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

// 기다리고 있는 비동기 작업이 모두 끝나게 한다
async function settle(): Promise<void> {
  for (let i = 0; i < 5; i += 1) {
    await Promise.resolve();
  }
}

function setup(pages: Record<string, CursorPage<FeedItem>>) {
  const getFeed = jest.fn(async (cursor?: string) => pages[cursor ?? 'first']);
  const vote = jest.fn(async (_questionId: number, optionId: number) => voteResponse(optionId));
  const getResults = jest.fn();
  const onVoteSettled = jest.fn();
  const api: FeedApi = { getFeed, vote, getResults };
  const controller = new FeedController(api, { onVoteSettled, dailyLimit: () => 50 });
  return { controller, getFeed, vote, getResults, onVoteSettled };
}

// 지금 카드에 투표하고 다음 카드로 넘긴다
async function voteAndNext(controller: FeedController): Promise<void> {
  const current = currentCard(controller.getState());
  await controller.choose(current!.options[0].id);
  controller.next();
  await settle();
}

describe('목록 받기', () => {
  it('시작하면 첫 쪽을 커서 없이 한 번 요청한다', async () => {
    const { controller, getFeed } = setup({ first: page(1, 20, 'c1') });

    controller.start();
    controller.start(); // 두 번 불려도 요청은 한 번
    await settle();

    expect(getFeed).toHaveBeenCalledTimes(1);
    expect(getFeed).toHaveBeenCalledWith(undefined);
    expect(currentCard(controller.getState())?.id).toBe(1);
  });

  it('남은 카드가 3장이 되는 순간 다음 쪽을 한 번만 요청한다', async () => {
    const { controller, getFeed } = setup({ first: page(1, 20, 'c1'), c1: page(21, 24) });
    controller.start();
    await settle();

    // 16번째 카드까지: 남은 카드 4장
    for (let i = 0; i < 15; i += 1) {
      await voteAndNext(controller);
    }
    expect(currentCard(controller.getState())?.id).toBe(16);
    expect(getFeed).toHaveBeenCalledTimes(1);

    // 17번째 카드: 남은 카드 3장
    await voteAndNext(controller);
    expect(currentCard(controller.getState())?.id).toBe(17);
    expect(getFeed).toHaveBeenCalledTimes(2);
    expect(getFeed).toHaveBeenLastCalledWith('c1');

    // 다음 쪽이 없으므로 더 요청하지 않는다
    for (let i = 0; i < 8; i += 1) {
      await voteAndNext(controller);
    }
    expect(getFeed).toHaveBeenCalledTimes(2);
    expect(isExhausted(controller.getState())).toBe(true);
  });

  it('첫 쪽이 3장 이하이고 다음 쪽이 있으면 바로 이어서 받는다', async () => {
    const { controller, getFeed } = setup({ first: page(1, 2, 'c1'), c1: page(3, 4) });

    controller.start();
    await settle();

    expect(getFeed).toHaveBeenCalledTimes(2);
    expect(controller.getState().cards.map((c) => c.id)).toEqual([1, 2, 3, 4]);
  });

  it('다음 쪽 받기에 실패하면 자동으로 반복하지 않고, 다시 시도하면 같은 커서로 요청한다', async () => {
    const { controller, getFeed } = setup({ first: page(1, 2, 'c1'), c1: page(3, 4) });
    getFeed.mockImplementationOnce(async () => page(1, 2, 'c1'));
    getFeed.mockImplementationOnce(async () => {
      throw new ApiError(0, 'NETWORK_ERROR', '서버에 연결할 수 없습니다.');
    });

    controller.start();
    await settle();

    expect(getFeed).toHaveBeenCalledTimes(2);
    expect(controller.getState().load).toBe('error');
    // 받아 둔 카드는 계속 볼 수 있다
    expect(currentCard(controller.getState())?.id).toBe(1);

    await voteAndNext(controller);
    expect(getFeed).toHaveBeenCalledTimes(2);

    controller.retryLoad();
    await settle();
    expect(getFeed).toHaveBeenCalledTimes(3);
    expect(getFeed).toHaveBeenLastCalledWith('c1');
    expect(controller.getState().cards.map((c) => c.id)).toEqual([1, 2, 3, 4]);
  });

  it('새로고침하면 처음부터 다시 받고, 그 전에 보낸 요청의 응답은 버린다', async () => {
    const slow = deferred<CursorPage<FeedItem>>();
    const { controller, getFeed } = setup({});
    getFeed.mockImplementationOnce(() => slow.promise);
    getFeed.mockImplementationOnce(async () => page(5, 6));

    controller.start();
    controller.refresh();
    await settle();
    expect(controller.getState().cards.map((c) => c.id)).toEqual([5, 6]);

    slow.resolve(page(1, 2)); // 새로고침 전에 보낸 요청이 이제야 도착
    await settle();
    expect(controller.getState().cards.map((c) => c.id)).toEqual([5, 6]);
  });

  it('화면이 사라진 뒤 도착한 응답은 버린다', async () => {
    const slow = deferred<CursorPage<FeedItem>>();
    const { controller, getFeed } = setup({});
    getFeed.mockImplementationOnce(() => slow.promise);

    controller.start();
    controller.dispose();
    slow.resolve(page(1, 2));
    await settle();

    expect(controller.getState().cards).toEqual([]);
  });
});

describe('투표', () => {
  it('투표하면 결과 단계가 되고 포인트 안내가 나오며 잔액 갱신을 알린다', async () => {
    const { controller, vote, onVoteSettled } = setup({ first: page(1, 5) });
    controller.start();
    await settle();

    await controller.choose(12);

    expect(vote).toHaveBeenCalledWith(1, 12);
    const state = controller.getState();
    expect(state.phase.kind).toBe('result');
    expect(state.notice?.text).toBe('+1P');
    expect(onVoteSettled).toHaveBeenCalledTimes(1);
  });

  it('응답을 기다리는 동안 또 눌러도 요청은 한 번만 나간다', async () => {
    const slow = deferred<VoteResponse>();
    const { controller, vote } = setup({ first: page(1, 5) });
    vote.mockImplementationOnce(() => slow.promise);
    controller.start();
    await settle();

    const first = controller.choose(11);
    const second = controller.choose(12);
    expect(controller.getState().phase).toEqual({ kind: 'submitting', optionId: 11 });

    slow.resolve(voteResponse(11));
    await Promise.all([first, second]);

    expect(vote).toHaveBeenCalledTimes(1);
    expect(controller.getState().phase.kind).toBe('result');
  });

  it('하루 한도에 걸리면 한도 안내가 나온다', async () => {
    const { controller, vote } = setup({ first: page(1, 5) });
    vote.mockImplementationOnce(async (_questionId, optionId) => voteResponse(optionId, false));
    controller.start();
    await settle();

    await controller.choose(11);

    expect(controller.getState().phase.kind).toBe('result');
    expect(controller.getState().notice?.text).toBe('오늘 적립 한도(50P) 도달');
  });

  it('이미 투표한 고민이면 결과를 조회해 보여 주고 포인트 안내는 하지 않는다', async () => {
    const { controller, vote, getResults, onVoteSettled } = setup({ first: page(1, 5) });
    vote.mockRejectedValueOnce(new ApiError(409, 'VOTE_ALREADY_VOTED', '이미 투표한 고민입니다.'));
    getResults.mockResolvedValueOnce({
      questionId: 1,
      totalVotes: 4,
      myOptionId: 12,
      options: [
        { optionId: 11, sortOrder: 1, content: '가', count: 1, percent: 25 },
        { optionId: 12, sortOrder: 2, content: '나', count: 3, percent: 75 },
      ],
    });
    controller.start();
    await settle();

    await controller.choose(11);

    const state = controller.getState();
    expect(getResults).toHaveBeenCalledWith(1);
    expect(state.phase).toMatchObject({ kind: 'result', result: { myOptionId: 12, totalVotes: 4 } });
    expect(state.notice).toBeUndefined();
    expect(onVoteSettled).toHaveBeenCalledTimes(1);
  });

  it('종료된 고민이면 안내하고 다음 카드로 넘어간다', async () => {
    const { controller, vote, onVoteSettled } = setup({ first: page(1, 5) });
    vote.mockRejectedValueOnce(new ApiError(409, 'QUESTION_CLOSED', '종료된 고민입니다.'));
    controller.start();
    await settle();

    await controller.choose(11);

    const state = controller.getState();
    expect(currentCard(state)?.id).toBe(2);
    expect(state.phase).toEqual({ kind: 'choosing' });
    expect(state.notice?.text).toBe('이미 종료된 고민이에요');
    expect(onVoteSettled).not.toHaveBeenCalled();
  });

  it('네트워크 오류면 같은 카드에서 다시 고를 수 있다', async () => {
    const { controller, vote } = setup({ first: page(1, 5) });
    vote.mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '서버에 연결할 수 없습니다.'));
    controller.start();
    await settle();

    await controller.choose(11);
    expect(currentCard(controller.getState())?.id).toBe(1);
    expect(controller.getState().phase).toEqual({ kind: 'choosing' });

    await controller.choose(11);
    expect(vote).toHaveBeenCalledTimes(2);
    expect(controller.getState().phase.kind).toBe('result');
  });
});

describe('화면에 알리기', () => {
  it('상태가 바뀔 때마다 등록된 함수를 부르고, 등록을 풀면 부르지 않는다', async () => {
    const { controller } = setup({ first: page(1, 5) });
    const listener = jest.fn();
    const unsubscribe = controller.subscribe(listener);

    controller.start();
    await settle();
    const calls = listener.mock.calls.length;
    expect(calls).toBeGreaterThan(0);

    unsubscribe();
    await controller.choose(11);
    expect(listener).toHaveBeenCalledTimes(calls);
  });
});
