// 스와이프 판정 테스트
import type { FeedItem, OptionResponse } from '../api/questions';
import { canSwipeToChoose, classifySwipe, optionForSwipe } from '../feed/swipe';

// 속도 없이 거리만 있는 입력
function move(dx: number, dy: number, vx = 0, vy = 0) {
  return { dx, dy, vx, vy };
}

function textCard(options: OptionResponse[]): FeedItem {
  return {
    id: 1,
    questionType: 'TEXT',
    content: '고민',
    boosted: false,
    options,
    author: { nickname: '작성자' },
    createdAt: '2026-09-29T12:00:00',
  };
}

const TWO = [
  { id: 101, sortOrder: 1, content: '카페' },
  { id: 102, sortOrder: 2, content: '밥집' },
];

describe('classifySwipe', () => {
  it('충분히 밀면 방향을 돌려준다', () => {
    expect(classifySwipe(move(-80, 0))).toBe('left');
    expect(classifySwipe(move(120, 10))).toBe('right');
    expect(classifySwipe(move(5, -90))).toBe('up');
  });

  it('조금만 움직였으면 스와이프가 아니다 (탭하다 손이 흔들린 경우)', () => {
    expect(classifySwipe(move(-79, 0))).toBeNull();
    expect(classifySwipe(move(0, -40))).toBeNull();
    expect(classifySwipe(move(0, 0))).toBeNull();
  });

  it('짧아도 빠르게 튕기면 인정한다', () => {
    expect(classifySwipe(move(-30, 0, -600, 0))).toBe('left');
    expect(classifySwipe(move(40, 0, 900, 0))).toBe('right');
    expect(classifySwipe(move(0, -35, 0, -700))).toBe('up');
  });

  it('너무 짧으면 빨라도 인정하지 않는다', () => {
    expect(classifySwipe(move(-29, 0, -2000, 0))).toBeNull();
  });

  it('짧고 느리면 인정하지 않는다', () => {
    expect(classifySwipe(move(-50, 0, -599, 0))).toBeNull();
  });

  it('대각선으로 밀면 어느 쪽도 아니다', () => {
    expect(classifySwipe(move(100, 100))).toBeNull();
    expect(classifySwipe(move(-100, -80))).toBeNull();
  });

  it('아래로 미는 동작에는 기능이 없다', () => {
    expect(classifySwipe(move(0, 200))).toBeNull();
    expect(classifySwipe(move(0, 40, 0, 900))).toBeNull();
  });
});

describe('optionForSwipe', () => {
  it('선택지가 2개인 글형은 왼쪽이 첫 번째, 오른쪽이 두 번째다', () => {
    const card = textCard(TWO);
    expect(canSwipeToChoose(card)).toBe(true);
    expect(optionForSwipe(card, 'left')).toBe(101);
    expect(optionForSwipe(card, 'right')).toBe(102);
  });

  it('배열 순서가 아니라 sortOrder 를 따른다', () => {
    const card = textCard([TWO[1], TWO[0]]);
    expect(optionForSwipe(card, 'left')).toBe(101);
    expect(optionForSwipe(card, 'right')).toBe(102);
    // 원본 배열은 건드리지 않는다
    expect(card.options.map((o) => o.id)).toEqual([102, 101]);
  });

  it('위로 미는 동작은 선택이 아니다', () => {
    expect(optionForSwipe(textCard(TWO), 'up')).toBeUndefined();
  });

  it('선택지가 3개 이상이면 스와이프로 고를 수 없다', () => {
    const card = textCard([...TWO, { id: 103, sortOrder: 3, content: '술집' }]);
    expect(canSwipeToChoose(card)).toBe(false);
    expect(optionForSwipe(card, 'left')).toBeUndefined();
  });

  it('사진형은 스와이프로 고를 수 없다', () => {
    const card: FeedItem = {
      ...textCard([
        { id: 201, sortOrder: 1, imageUrl: 'https://example.com/a.jpg' },
        { id: 202, sortOrder: 2, imageUrl: 'https://example.com/b.jpg' },
      ]),
      questionType: 'IMAGE',
    };
    expect(canSwipeToChoose(card)).toBe(false);
    expect(optionForSwipe(card, 'right')).toBeUndefined();
  });
});
