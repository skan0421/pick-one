// 커서 목록 공용 함수 테스트
import { flattenPages, nextCursorOf, pageQuery, type Pages } from '../api/paging';
import type { CursorPage } from '../api/questions';

type Row = { key: number; text: string };

function page(keys: number[], nextCursor?: string): CursorPage<Row> {
  return { items: keys.map((key) => ({ key, text: `줄 ${key}` })), nextCursor, hasNext: nextCursor !== undefined };
}

function pages(...list: CursorPage<Row>[]): Pages<Row> {
  return { pages: list, pageParams: list.map((_, i) => (i === 0 ? undefined : `c${i}`)) };
}

describe('nextCursorOf', () => {
  it('다음 쪽이 있으면 커서를, 없으면 undefined 를 돌려준다', () => {
    expect(nextCursorOf(page([1], 'c1'))).toBe('c1');
    expect(nextCursorOf(page([1]))).toBeUndefined();
  });

  it('hasNext 가 false 면 커서가 있어도 쓰지 않는다', () => {
    expect(nextCursorOf({ items: [], nextCursor: 'c1', hasNext: false })).toBeUndefined();
  });
});

describe('flattenPages', () => {
  it('쪽을 받은 순서대로 편다', () => {
    const data = pages(page([5, 4], 'c1'), page([3, 2]));

    expect(flattenPages(data, (row) => row.key).map((row) => row.key)).toEqual([5, 4, 3, 2]);
  });

  it('키가 같은 항목은 먼저 온 것만 남긴다', () => {
    const data = pages(page([5, 4], 'c1'), page([4, 3]));

    expect(flattenPages(data, (row) => row.key).map((row) => row.key)).toEqual([5, 4, 3]);
  });

  it('아직 받은 것이 없으면 빈 목록', () => {
    expect(flattenPages<Row>(undefined, (row) => row.key)).toEqual([]);
  });
});

describe('pageQuery', () => {
  it('첫 쪽은 size 만 보낸다', () => {
    expect(pageQuery(undefined, 20)).toBe('size=20');
  });

  it('커서의 특수문자를 주소에 넣을 수 있게 바꾼다', () => {
    expect(pageQuery('a+b/c=', 20)).toBe('size=20&cursor=a%2Bb%2Fc%3D');
  });
});
