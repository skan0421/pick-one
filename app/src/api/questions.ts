// 고민 API (docs/api.md 4.2). ACTIVE 회원만 호출할 수 있다
import { request } from './client';

export type QuestionType = 'TEXT' | 'IMAGE';

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
