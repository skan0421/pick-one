// 커서 방식 목록(docs/api.md 1.5)을 다루는 공용 함수. 화면도 서버 호출도 없는 순수 함수다.
// 내 고민, 포인트 내역, 내가 투표한 고민 목록이 함께 쓴다 (Java 의 제네릭 유틸 클래스)
import type { CursorPage } from './questions';

// 쪽 단위로 쌓인 목록. TanStack Query 가 "무한 목록"을 캐시에 담는 모양과 같다
//   pages      받은 쪽들 (받은 순서대로)
//   pageParams 각 쪽을 받을 때 쓴 커서
// <T> 는 Java 의 제네릭과 같다. Pages<LedgerItem> 은 포인트 내역의 쪽 묶음이다
export type Pages<T> = {
  pages: CursorPage<T>[];
  pageParams: unknown[];
};

// 다음 쪽을 받을 때 쓸 커서. 다음 쪽이 없으면 undefined (더 받지 않는다)
export function nextCursorOf<T>(page: CursorPage<T>): string | undefined {
  return page.hasNext ? page.nextCursor : undefined;
}

// 여러 쪽을 하나의 목록으로 편다. keyOf 가 같은 값을 돌려주는 항목은 한 번만 넣는다.
// 쪽을 받는 사이에 새 항목이 생기면 목록이 한 칸씩 밀려 앞 쪽의 마지막 항목이 다음 쪽에 또 올 수 있다
export function flattenPages<T>(data: Pages<T> | undefined, keyOf: (item: T) => number): T[] {
  const seen = new Set<number>();
  const items: T[] = [];
  for (const page of data?.pages ?? []) {
    for (const item of page.items) {
      const key = keyOf(item);
      if (!seen.has(key)) {
        seen.add(key);
        items.push(item);
      }
    }
  }
  return items;
}

// 주소 뒤에 붙일 ?cursor=...&size=... 를 만든다. URLSearchParams 가 값의 특수문자를 주소에 넣을 수 있게 바꿔 준다
export function pageQuery(cursor: string | undefined, size: number): string {
  const params = new URLSearchParams({ size: String(size) });
  if (cursor) {
    params.set('cursor', cursor);
  }
  return params.toString();
}
