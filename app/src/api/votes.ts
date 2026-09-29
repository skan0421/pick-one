// 투표 API (docs/api.md 5.1 ~ 5.3). ACTIVE 회원만 호출할 수 있다
import { request } from './client';
import { pageQuery } from './paging';
import type { CursorPage, OptionResponse, QuestionStatus, QuestionType } from './questions';

// 선택지 하나의 득표. percent 는 서버가 소수점 1자리로 계산하고 합이 100 이 되게 보정해서 준다
export type OptionTally = {
  optionId: number;
  count: number;
  percent: number;
};

// 투표로 받은 포인트. 하루 한도에 걸리면 투표는 성공하고 earned 만 false 가 된다.
// reason 은 적립되지 않았을 때만 온다 (서버가 null 필드를 JSON 에서 생략한다)
export type PointReward = {
  earned: boolean;
  amount: number;
  reason?: string; // 'DAILY_LIMIT_REACHED'
};

// POST /questions/{id}/votes 의 응답.
// 여기 options 에는 선택지 글·사진이 없다. 화면은 optionId 로 피드 카드의 선택지와 짝지어 그린다
export type VoteResponse = {
  voteId: number;
  result: {
    totalVotes: number;
    myOptionId: number;
    options: OptionTally[];
  };
  pointReward: PointReward;
};

// GET /questions/{id}/results 의 응답. 그 고민에 투표한 사람과 작성자만 볼 수 있다
export type VoteResultResponse = {
  questionId: number;
  totalVotes: number;
  myOptionId?: number; // 작성자가 조회하면 오지 않는다
  options: (OptionTally & { sortOrder: number; content?: string; imageUrl?: string })[];
};

export function vote(questionId: number, optionId: number): Promise<VoteResponse> {
  return request<VoteResponse>(`/questions/${questionId}/votes`, { method: 'POST', body: { optionId } });
}

export function getResults(questionId: number): Promise<VoteResultResponse> {
  return request<VoteResultResponse>(`/questions/${questionId}/results`);
}

// 내가 투표한 고민 한 건. 고민(question)과 득표(result)가 따로 오고, 선택지는 id 로 짝지어야 한다
export type MyVoteItem = {
  voteId: number;
  votedAt: string;
  question: {
    id: number;
    questionType: QuestionType;
    content: string;
    status: QuestionStatus;
    boosted: boolean;
    options: OptionResponse[];
    author: { nickname: string };
    createdAt: string;
  };
  myOptionId: number; // 내가 고른 선택지
  result: {
    totalVotes: number;
    options: OptionTally[];
  };
};

// GET /members/me/votes (docs/api.md 5.3). 투표한 시각의 최신순.
// 삭제됐거나 숨겨진 고민은 빠지고, 종료(CLOSED)된 고민은 포함된다
export function getMyVotes(cursor?: string, size = 20): Promise<CursorPage<MyVoteItem>> {
  return request<CursorPage<MyVoteItem>>(`/members/me/votes?${pageQuery(cursor, size)}`);
}
