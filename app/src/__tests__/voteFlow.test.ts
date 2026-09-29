// 투표 흐름 테스트. 서버 호출 함수를 가짜로 넣어 에러 코드별 처리를 검증한다.
// jest.fn() 은 Mockito 의 mock, mockResolvedValue 는 thenReturn, mockRejectedValue 는 thenThrow 에 해당한다
import { ApiError, NETWORK_ERROR } from '../api/errors';
import type { VoteResponse, VoteResultResponse } from '../api/votes';
import { rewardMessage, submitVote, toResultView, type VoteApi } from '../feed/voteFlow';

const VOTE_RESPONSE: VoteResponse = {
  voteId: 9001,
  result: {
    totalVotes: 3,
    myOptionId: 101,
    options: [
      { optionId: 101, count: 2, percent: 66.7 },
      { optionId: 102, count: 1, percent: 33.3 },
    ],
  },
  pointReward: { earned: true, amount: 1 },
};

const RESULT_RESPONSE: VoteResultResponse = {
  questionId: 42,
  totalVotes: 3,
  myOptionId: 102,
  options: [
    { optionId: 101, sortOrder: 1, content: '카페', count: 2, percent: 66.7 },
    { optionId: 102, sortOrder: 2, content: '밥집', count: 1, percent: 33.3 },
  ],
};

function apiError(status: number, code: string): ApiError {
  return new ApiError(status, code, `${code} 메시지`);
}

function fakeApi(): { api: VoteApi; vote: jest.Mock; getResults: jest.Mock } {
  const vote = jest.fn();
  const getResults = jest.fn();
  return { api: { vote, getResults }, vote, getResults };
}

describe('submitVote', () => {
  it('투표에 성공하면 결과와 포인트를 돌려주고 결과 조회는 하지 않는다', async () => {
    const { api, vote, getResults } = fakeApi();
    vote.mockResolvedValue(VOTE_RESPONSE);

    const outcome = await submitVote(api, 42, 101);

    expect(vote).toHaveBeenCalledWith(42, 101);
    expect(getResults).not.toHaveBeenCalled();
    expect(outcome).toEqual({
      kind: 'voted',
      result: toResultView(VOTE_RESPONSE.result),
      reward: { earned: true, amount: 1 },
    });
  });

  it('이미 투표한 고민이면 에러 대신 결과를 조회해 돌려준다', async () => {
    const { api, vote, getResults } = fakeApi();
    vote.mockRejectedValue(apiError(409, 'VOTE_ALREADY_VOTED'));
    getResults.mockResolvedValue(RESULT_RESPONSE);

    const outcome = await submitVote(api, 42, 101);

    expect(getResults).toHaveBeenCalledTimes(1);
    expect(getResults).toHaveBeenCalledWith(42);
    // 내가 고른 선택지는 방금 누른 것(101)이 아니라 서버에 기록된 것(102)이다
    expect(outcome).toEqual({ kind: 'alreadyVoted', result: toResultView(RESULT_RESPONSE) });
  });

  it('이미 투표한 고민인데 결과를 볼 수 없으면 카드를 넘긴다', async () => {
    const { api, vote, getResults } = fakeApi();
    vote.mockRejectedValue(apiError(409, 'VOTE_ALREADY_VOTED'));
    getResults.mockRejectedValue(apiError(404, 'QUESTION_NOT_FOUND'));

    expect((await submitVote(api, 42, 101)).kind).toBe('skip');
  });

  it('이미 투표한 고민인데 결과 조회가 네트워크 오류면 다시 시도하게 한다', async () => {
    const { api, vote, getResults } = fakeApi();
    vote.mockRejectedValue(apiError(409, 'VOTE_ALREADY_VOTED'));
    getResults.mockRejectedValue(new ApiError(0, NETWORK_ERROR, '서버에 연결할 수 없습니다.'));

    expect(await submitVote(api, 42, 101)).toEqual({ kind: 'retry', message: '서버에 연결할 수 없습니다.' });
  });

  it('없는 고민과 종료된 고민은 서로 다른 안내와 함께 넘긴다', async () => {
    const notFound = fakeApi();
    notFound.vote.mockRejectedValue(apiError(404, 'QUESTION_NOT_FOUND'));
    const closed = fakeApi();
    closed.vote.mockRejectedValue(apiError(409, 'QUESTION_CLOSED'));

    const first = await submitVote(notFound.api, 42, 101);
    const second = await submitVote(closed.api, 42, 101);

    expect(first).toEqual({ kind: 'skip', notice: '삭제되었거나 볼 수 없는 고민이에요' });
    expect(second).toEqual({ kind: 'skip', notice: '이미 종료된 고민이에요' });
    expect(notFound.getResults).not.toHaveBeenCalled();
  });

  it.each(['VOTE_OWN_QUESTION', 'VOTE_OPTION_MISMATCH'])('%s 는 다시 시도해도 같으므로 넘긴다', async (code) => {
    const { api, vote } = fakeApi();
    vote.mockRejectedValue(apiError(400, code));

    expect((await submitVote(api, 42, 101)).kind).toBe('skip');
  });

  it.each(['POINT_WALLET_CONFLICT', 'INTERNAL_ERROR', NETWORK_ERROR])('%s 는 다시 시도하게 한다', async (code) => {
    const { api, vote } = fakeApi();
    vote.mockRejectedValue(apiError(409, code));

    expect(await submitVote(api, 42, 101)).toEqual({ kind: 'retry', message: `${code} 메시지` });
  });

  it('ApiError 가 아닌 예외도 던지지 않고 다시 시도로 돌려준다', async () => {
    const { api, vote } = fakeApi();
    vote.mockRejectedValue(new TypeError('예상 못 한 오류'));

    expect(await submitVote(api, 42, 101)).toEqual({ kind: 'retry', message: '예상 못 한 오류' });
  });
});

describe('rewardMessage', () => {
  it('적립되면 받은 포인트를 보여 준다', () => {
    expect(rewardMessage({ earned: true, amount: 1 }, 50)).toBe('+1P');
  });

  it('하루 한도에 걸리면 한도를 알려 준다', () => {
    expect(rewardMessage({ earned: false, amount: 0, reason: 'DAILY_LIMIT_REACHED' }, 50)).toBe(
      '오늘 적립 한도(50P) 도달',
    );
  });

  it('한도 값을 아직 모르면 숫자 없이 알려 준다', () => {
    expect(rewardMessage({ earned: false, amount: 0, reason: 'DAILY_LIMIT_REACHED' })).toBe('오늘 적립 한도 도달');
  });

  it('그 밖의 이유로 적립되지 않았으면 안내하지 않는다', () => {
    expect(rewardMessage({ earned: false, amount: 0 })).toBeUndefined();
  });
});

describe('toResultView', () => {
  it('결과 조회 응답에서 화면에 필요한 값만 남긴다', () => {
    expect(toResultView(RESULT_RESPONSE)).toEqual({
      totalVotes: 3,
      myOptionId: 102,
      options: [
        { optionId: 101, count: 2, percent: 66.7 },
        { optionId: 102, count: 1, percent: 33.3 },
      ],
    });
  });

  it('내가 고른 선택지가 없는 응답(작성자)도 바꾼다', () => {
    const view = toResultView({ questionId: 42, totalVotes: 0, options: [] });
    expect(view.myOptionId).toBeUndefined();
    expect(view.options).toEqual([]);
  });
});
