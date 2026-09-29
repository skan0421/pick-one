// 피드 화면의 상태와 "상태가 어떻게 바뀌는지"를 모아 둔 곳.
//
// 이 파일에는 화면(React)도 서버 호출도 없다. 입력이 같으면 결과가 같은 순수 함수뿐이라
// 서버나 화면 없이 단위 테스트할 수 있다 (src/__tests__/feedState.test.ts).
//
// Java 에 빗대면
//   FeedState   = 불변 객체 (record). 직접 고치지 않고 매번 새 객체를 만든다
//   FeedAction  = 커맨드 객체. "무슨 일이 일어났는지"를 담는다 (sealed interface 의 구현체들)
//   feedReducer = 상태 기계의 전이 함수. (현재 상태, 일어난 일) → 다음 상태
import type { CursorPage, FeedItem } from '../api/questions';
import type { OptionTally } from '../api/votes';

// 남은 카드가 이 수 이하가 되면 다음 쪽을 미리 받아 둔다
export const PREFETCH_THRESHOLD = 3;

// 화면에 그릴 투표 결과. 투표 응답과 결과 조회 응답을 같은 모양으로 맞춘 것이다 (voteFlow.ts 의 toResultView)
export type ResultView = {
  totalVotes: number;
  myOptionId?: number;
  options: OptionTally[];
};

// 지금 보고 있는 카드의 단계.
//   choosing   고르는 중. 결과는 보여 주지 않는다
//   submitting 투표 요청을 보내고 응답을 기다리는 중. 버튼을 막는다
//   result     결과를 보는 중
// "타입 | 타입" 은 Java 의 sealed interface 와 같다. kind 값으로 어느 쪽인지 구분한다
export type Phase =
  | { kind: 'choosing' }
  | { kind: 'submitting'; optionId: number }
  | { kind: 'result'; result: ResultView };

// 잠깐 보여 주는 안내 문구. seq 는 같은 문구가 연달아 와도 새 안내로 구분하기 위한 번호다
export type Notice = {
  seq: number;
  text: string;
};

// 목록을 받아 오는 상태
//   initial 첫 쪽을 받는 중 (새로고침 포함)
//   idle    받는 중이 아님
//   more    다음 쪽을 받는 중
//   error   받다가 실패함. 자동으로 다시 시도하지 않는다
export type LoadStatus = 'initial' | 'idle' | 'more' | 'error';

export type FeedState = {
  cards: FeedItem[]; // 지금까지 받은 카드 전부. 지나간 카드도 남겨 두어 중복 판단에 쓴다
  index: number; // 지금 보고 있는 카드의 위치
  nextCursor?: string;
  hasNext: boolean;
  load: LoadStatus;
  loadError?: string;
  phase: Phase;
  notice?: Notice;
  noticeSeq: number;
};

export type FeedAction =
  | { type: 'loadStarted' }
  | { type: 'pageLoaded'; page: CursorPage<FeedItem> }
  | { type: 'loadFailed'; message: string }
  | { type: 'voteStarted'; optionId: number }
  | { type: 'voteSucceeded'; result: ResultView; notice?: string } // 방금 투표에 성공함
  | { type: 'resultLoaded'; result: ResultView } // 이미 투표한 고민이라 결과만 받아 옴
  | { type: 'voteFailed'; message: string } // 다시 시도할 수 있는 실패
  | { type: 'cardSkipped'; notice: string } // 투표할 수 없는 카드라 넘김
  | { type: 'next' }
  | { type: 'noticeDismissed' }
  | { type: 'reset' };

export const initialFeedState: FeedState = {
  cards: [],
  index: 0,
  hasNext: true,
  load: 'initial',
  phase: { kind: 'choosing' },
  noticeSeq: 0,
};

export function feedReducer(state: FeedState, action: FeedAction): FeedState {
  // { ...state, 바꿀값 } 은 state 를 복사하면서 일부만 바꾼 새 객체를 만든다 (record 의 wither 와 같다)
  switch (action.type) {
    case 'loadStarted':
      return { ...state, load: state.load === 'initial' ? 'initial' : 'more', loadError: undefined };

    case 'pageLoaded': {
      // 같은 id 가 두 번 올 수 있다. 쪽 사이에 상단 노출이 끝난 고민은 일반 목록에서 한 번 더 나온다 (docs/api.md 4.2)
      const seen = new Set(state.cards.map((card) => card.id));
      const fresh: FeedItem[] = [];
      for (const item of action.page.items) {
        if (!seen.has(item.id)) {
          seen.add(item.id);
          fresh.push(item);
        }
      }
      return {
        ...state,
        cards: [...state.cards, ...fresh],
        nextCursor: action.page.nextCursor,
        // 커서 없이 "다음 있음"만 오면 첫 쪽을 끝없이 다시 받게 되므로 다음이 없는 것으로 본다
        hasNext: action.page.hasNext && !!action.page.nextCursor,
        load: 'idle',
        loadError: undefined,
      };
    }

    case 'loadFailed':
      return { ...state, load: 'error', loadError: action.message };

    case 'voteStarted':
      // 고르는 중일 때만 받는다. 버튼을 빠르게 두 번 누르거나 스와이프와 버튼이 함께 눌려도 요청은 한 번만 나간다
      if (state.phase.kind !== 'choosing' || !currentCard(state)) {
        return state;
      }
      return { ...state, phase: { kind: 'submitting', optionId: action.optionId } };

    case 'voteSucceeded':
      if (state.phase.kind !== 'submitting') {
        return state;
      }
      return withNotice({ ...state, phase: { kind: 'result', result: action.result } }, action.notice);

    case 'resultLoaded':
      if (state.phase.kind !== 'submitting') {
        return state;
      }
      return { ...state, phase: { kind: 'result', result: action.result } };

    case 'voteFailed':
      if (state.phase.kind !== 'submitting') {
        return state;
      }
      return withNotice({ ...state, phase: { kind: 'choosing' } }, action.message);

    case 'cardSkipped':
      if (!currentCard(state)) {
        return state;
      }
      return withNotice({ ...state, index: state.index + 1, phase: { kind: 'choosing' } }, action.notice);

    case 'next':
      // 결과를 본 뒤에만 넘어간다. 투표하지 않고 넘기는 기능은 없다
      if (state.phase.kind !== 'result') {
        return state;
      }
      return { ...state, index: state.index + 1, phase: { kind: 'choosing' } };

    case 'noticeDismissed':
      return { ...state, notice: undefined };

    case 'reset':
      // 안내 번호는 이어 간다. 새로고침 전후의 안내가 같은 번호를 쓰지 않게 하려는 것이다
      return { ...initialFeedState, noticeSeq: state.noticeSeq };
  }
}

function withNotice(state: FeedState, text: string | undefined): FeedState {
  if (!text) {
    return state;
  }
  const seq = state.noticeSeq + 1;
  return { ...state, notice: { seq, text }, noticeSeq: seq };
}

// 아래는 상태에서 값을 계산해 내는 함수들이다 (record 의 계산 메서드)

// 지금 보여 줄 카드. 다 봤으면 undefined
export function currentCard(state: FeedState): FeedItem | undefined {
  return state.cards[state.index];
}

// 지금 카드 뒤에 남은 카드 수
export function remainingAfterCurrent(state: FeedState): number {
  return Math.max(0, state.cards.length - state.index - 1);
}

// 다음 쪽을 지금 받아야 하는가.
// 실패한 상태(error)에서는 false 다. 서버가 죽어 있을 때 요청을 끝없이 반복하지 않기 위해서다
export function shouldPrefetch(state: FeedState): boolean {
  return state.hasNext && state.load === 'idle' && remainingAfterCurrent(state) <= PREFETCH_THRESHOLD;
}

// 더 보여 줄 카드가 없는가 ("지금은 고를 고민이 없어요")
export function isExhausted(state: FeedState): boolean {
  return !currentCard(state) && !state.hasNext && state.load === 'idle';
}
