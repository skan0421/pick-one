// 피드 화면의 진행자. 상태(feedState.ts)를 들고 있으면서 서버 호출과 엮는다.
//
// Spring 의 @Service 와 같은 자리다.
//   feedState.ts      상태와 전이 규칙 (도메인)
//   voteFlow.ts       투표 한 번의 흐름
//   FeedController    위 둘과 서버 호출을 엮는다 (서비스)
//   useFeed.ts        이 객체를 화면에 연결한다
// React 를 쓰지 않는 평범한 클래스라 화면 없이 테스트할 수 있다 (src/__tests__/feedController.test.ts)
import { errorMessage } from '../api/errors';
import type { CursorPage, FeedItem } from '../api/questions';
import { currentCard, feedReducer, initialFeedState, shouldPrefetch, type FeedAction, type FeedState } from './feedState';
import { rewardMessage, submitVote, type VoteApi } from './voteFlow';

export type FeedApi = VoteApi & {
  getFeed: (cursor?: string) => Promise<CursorPage<FeedItem>>;
};

export type FeedControllerOptions = {
  // 투표 결과가 나왔을 때 부른다. 화면은 여기서 포인트 잔액을 다시 받는다
  onVoteSettled?: () => void;
  // 하루 적립 한도. 안내 문구에 쓴다. 아직 모르면 undefined
  dailyLimit?: () => number | undefined;
};

export class FeedController {
  private readonly api: FeedApi;
  private readonly options: FeedControllerOptions;
  private state: FeedState = initialFeedState;
  private readonly listeners = new Set<() => void>();

  // 목록 요청이 진행 중인지. 같은 쪽을 두 번 요청하지 않게 막는다.
  // 자바스크립트는 단일 스레드라 "확인 후 대입" 사이에 다른 코드가 끼어들지 않는다 (Java 였다면 AtomicBoolean 이 필요한 자리)
  private loading = false;
  // 세대 번호. 새로고침하거나 화면이 사라지면 올린다.
  // 요청을 보낼 때의 번호와 응답이 왔을 때의 번호가 다르면 늦게 온 응답이므로 버린다 (낙관적 락의 version 검사와 같은 생각)
  private generation = 0;

  constructor(api: FeedApi, options: FeedControllerOptions = {}) {
    this.api = api;
    this.options = options;
  }

  // 화살표 함수로 둔 이유: React 에 함수만 넘겨도 this 가 이 객체를 가리키게 하려는 것이다
  getState = (): FeedState => this.state;

  // 상태가 바뀔 때마다 불러 달라고 등록한다 (옵저버 패턴). 돌려주는 함수를 부르면 등록이 풀린다
  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  // 화면이 나타날 때 부른다
  start(): void {
    if (this.state.load === 'initial') {
      void this.load();
    }
  }

  // 화면이 사라질 때 부른다. 진행 중이던 요청의 응답은 버려진다
  dispose(): void {
    this.generation += 1;
    this.loading = false;
  }

  // 처음부터 다시 받는다
  refresh(): void {
    this.generation += 1;
    this.loading = false;
    this.apply({ type: 'reset' });
    void this.load();
  }

  // 목록 받기에 실패한 뒤 다시 시도한다
  retryLoad(): void {
    void this.load();
  }

  async choose(optionId: number): Promise<void> {
    const card = currentCard(this.state);
    if (!card || this.state.phase.kind !== 'choosing') {
      return; // 이미 요청 중이거나 결과를 보는 중
    }
    const generation = this.generation;
    this.apply({ type: 'voteStarted', optionId });

    const outcome = await submitVote(this.api, card.id, optionId);
    if (generation !== this.generation) {
      return;
    }

    switch (outcome.kind) {
      case 'voted':
        this.apply({
          type: 'voteSucceeded',
          result: outcome.result,
          notice: rewardMessage(outcome.reward, this.options.dailyLimit?.()),
        });
        this.options.onVoteSettled?.();
        break;
      case 'alreadyVoted':
        this.apply({ type: 'resultLoaded', result: outcome.result });
        this.options.onVoteSettled?.();
        break;
      case 'skip':
        this.apply({ type: 'cardSkipped', notice: outcome.notice });
        break;
      case 'retry':
        this.apply({ type: 'voteFailed', message: outcome.message });
        break;
    }
  }

  next(): void {
    this.apply({ type: 'next' });
  }

  dismissNotice(): void {
    this.apply({ type: 'noticeDismissed' });
  }

  // 상태를 바꾸는 유일한 통로. 바꾼 뒤 화면에 알리고, 카드가 얼마 남지 않았으면 다음 쪽을 받기 시작한다
  private apply(action: FeedAction): void {
    const before = this.state;
    this.state = feedReducer(before, action);
    if (this.state === before) {
      return;
    }
    this.listeners.forEach((listener) => listener());
    if (shouldPrefetch(this.state)) {
      void this.load();
    }
  }

  private async load(): Promise<void> {
    if (this.loading) {
      return;
    }
    this.loading = true;
    const generation = this.generation;
    const cursor = this.state.nextCursor;
    this.apply({ type: 'loadStarted' });

    try {
      const page = await this.api.getFeed(cursor);
      if (generation !== this.generation) {
        return;
      }
      this.loading = false;
      this.apply({ type: 'pageLoaded', page });
    } catch (error) {
      if (generation !== this.generation) {
        return;
      }
      this.loading = false;
      this.apply({ type: 'loadFailed', message: errorMessage(error) });
    }
  }
}
