// 투표 한 번의 흐름: 요청을 보내고, 실패했다면 에러 코드에 따라 어떻게 할지 정한다.
//
// 서버 호출 함수(VoteApi)를 직접 import 하지 않고 인자로 받는다.
// Spring 의 생성자 주입과 같은 이유다. 테스트에서 가짜(Mockito 의 mock)를 넣을 수 있다
import { ApiError, errorMessage } from '../api/errors';
import type { PointReward, VoteResponse, VoteResultResponse } from '../api/votes';
import type { ResultView } from './feedState';

export type VoteApi = {
  vote: (questionId: number, optionId: number) => Promise<VoteResponse>;
  getResults: (questionId: number) => Promise<VoteResultResponse>;
};

// 투표 시도의 결과. 화면은 이 네 가지만 알면 된다
//   voted        투표 성공
//   alreadyVoted 이미 투표한 고민이었음. 에러로 보지 않고 결과를 보여 준다
//   skip         이 카드는 투표할 수 없음. 안내하고 다음 카드로
//   retry        일시적인 실패. 같은 카드에서 다시 고를 수 있게 한다
export type VoteOutcome =
  | { kind: 'voted'; result: ResultView; reward: PointReward }
  | { kind: 'alreadyVoted'; result: ResultView }
  | { kind: 'skip'; notice: string }
  | { kind: 'retry'; message: string };

// 다시 시도해도 결과가 같은 에러와 안내 문구. 이 카드는 넘긴다
const SKIP_NOTICES: Record<string, string> = {
  QUESTION_NOT_FOUND: '삭제되었거나 볼 수 없는 고민이에요',
  QUESTION_CLOSED: '이미 종료된 고민이에요',
  VOTE_OWN_QUESTION: '내가 올린 고민에는 투표할 수 없어요',
  VOTE_OPTION_MISMATCH: '선택지가 바뀐 고민이에요',
  RESULT_NOT_ALLOWED: '결과를 볼 수 없는 고민이에요',
};

// 예외를 던지지 않는다. 어떤 실패든 VoteOutcome 으로 바꿔 돌려준다
export async function submitVote(api: VoteApi, questionId: number, optionId: number): Promise<VoteOutcome> {
  try {
    const response = await api.vote(questionId, optionId);
    return { kind: 'voted', result: toResultView(response.result), reward: response.pointReward };
  } catch (error) {
    if (!(error instanceof ApiError) || error.code !== 'VOTE_ALREADY_VOTED') {
      return classifyFailure(error);
    }
  }

  // 이미 투표한 고민이다. 피드를 받은 뒤 다른 기기에서 투표했거나,
  // 앞선 요청이 서버에는 반영됐는데 응답만 받지 못한 경우다. 결과를 받아 와 보여 준다
  try {
    return { kind: 'alreadyVoted', result: toResultView(await api.getResults(questionId)) };
  } catch (error) {
    return classifyFailure(error);
  }
}

function classifyFailure(error: unknown): VoteOutcome {
  if (error instanceof ApiError && SKIP_NOTICES[error.code]) {
    return { kind: 'skip', notice: SKIP_NOTICES[error.code] };
  }
  // 네트워크 오류, 지갑 충돌(POINT_WALLET_CONFLICT), 서버 오류 등
  return { kind: 'retry', message: errorMessage(error) };
}

// 투표 후 잠깐 보여 줄 포인트 안내. 보여 줄 것이 없으면 undefined
export function rewardMessage(reward: PointReward, dailyLimit?: number): string | undefined {
  if (reward.earned) {
    return `+${reward.amount}P`;
  }
  if (reward.reason === 'DAILY_LIMIT_REACHED') {
    return dailyLimit === undefined ? '오늘 적립 한도 도달' : `오늘 적립 한도(${dailyLimit}P) 도달`;
  }
  return undefined;
}

// 투표 응답의 result 와 결과 조회 응답은 모양이 조금 다르다. 화면이 쓰는 부분만 뽑아 같은 모양으로 맞춘다
export function toResultView(source: VoteResponse['result'] | VoteResultResponse): ResultView {
  return {
    totalVotes: source.totalVotes,
    myOptionId: source.myOptionId,
    options: source.options.map((option) => ({
      optionId: option.optionId,
      count: option.count,
      percent: option.percent,
    })),
  };
}
