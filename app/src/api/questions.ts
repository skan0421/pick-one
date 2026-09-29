// 고민 API (docs/api.md 4.1 등록, 4.2 피드). ACTIVE 회원만 호출할 수 있다
import { request } from './client';

export type QuestionType = 'TEXT' | 'IMAGE';

// ACTIVE 노출 중 / HIDDEN 신고 누적으로 숨겨짐 / CLOSED 종료
export type QuestionStatus = 'ACTIVE' | 'HIDDEN' | 'CLOSED';

// 이름 뒤의 ? 는 "없을 수 있는 필드"다. 서버가 null 필드를 JSON 에서 생략하기 때문이다.
// 글 선택지에는 content 만, 사진 선택지에는 imageUrl 만 온다
export type OptionResponse = {
  id: number;
  sortOrder: number;
  content?: string;
  imageUrl?: string;
};

export type FeedItem = {
  id: number;
  questionType: QuestionType;
  content: string;
  boosted: boolean;
  options: OptionResponse[];
  author: { nickname: string };
  createdAt: string;
};

// 커서 방식 목록 응답 (docs/api.md 1.5). 백엔드 CursorPage<T> 와 같다
export type CursorPage<T> = {
  items: T[];
  nextCursor?: string; // 다음 쪽이 없으면 오지 않는다
  hasNext: boolean;
};

export function getFeed(cursor?: string, size = 20): Promise<CursorPage<FeedItem>> {
  // URLSearchParams 가 값의 특수문자를 주소에 넣을 수 있게 바꿔 준다
  const params = new URLSearchParams({ size: String(size) });
  if (cursor) {
    params.set('cursor', cursor);
  }
  return request<CursorPage<FeedItem>>(`/questions/feed?${params.toString()}`);
}

// 고민 등록 요청. 글형 선택지에는 content 만, 사진형 선택지에는 imageUrl 만 넣는다.
// 선택지 순서(sortOrder)는 서버가 배열 순서대로 1부터 붙인다
export type CreateQuestionRequest = {
  questionType: QuestionType;
  content: string;
  options: { content?: string; imageUrl?: string }[];
};

export type QuestionResponse = {
  id: number;
  questionType: QuestionType;
  content: string;
  status: QuestionStatus;
  boostedUntil?: string;
  options: OptionResponse[];
  createdAt: string;
};

export function createQuestion(body: CreateQuestionRequest): Promise<QuestionResponse> {
  return request<QuestionResponse>('/questions', { method: 'POST', body });
}

// 내 고민 목록의 선택지. 피드와 달리 득표 결과가 함께 온다 (작성자는 투표하지 않아도 결과를 볼 수 있다)
export type MyOption = OptionResponse & {
  count: number;
  percent: number;
};

export type MyQuestion = {
  id: number;
  questionType: QuestionType;
  content: string;
  status: QuestionStatus;
  boostedUntil?: string; // 상단 노출이 끝나는 시각. 쓴 적이 없으면 오지 않는다
  totalVotes: number;
  options: MyOption[];
  createdAt: string;
};

// GET /members/me/questions (docs/api.md 4.4). 최신순. 삭제한 고민은 빠지고 HIDDEN 은 상태와 함께 온다
export function getMyQuestions(cursor?: string, size = 20): Promise<CursorPage<MyQuestion>> {
  const params = new URLSearchParams({ size: String(size) });
  if (cursor) {
    params.set('cursor', cursor);
  }
  return request<CursorPage<MyQuestion>>(`/members/me/questions?${params.toString()}`);
}

// DELETE /questions/{id} (docs/api.md 4.5). 작성자만. 성공하면 본문 없는 204
export function deleteQuestion(id: number): Promise<void> {
  return request<void>(`/questions/${id}`, { method: 'DELETE' });
}
