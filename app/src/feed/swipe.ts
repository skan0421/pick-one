// 스와이프 판정. 손가락(마우스)이 움직인 거리와 속도를 받아 어느 방향으로 민 것인지 정한다.
// 화면과 무관한 계산이라 따로 두고 단위 테스트한다.
//
// 주의: SwipeArea.tsx 와 SwipeArea.web.tsx 가 함께 쓰는 코드는 이 파일에 둔다.
// SwipeArea.web.tsx 에서 './SwipeArea' 를 import 하면 웹에서는 자기 자신을 가리킨다 (docs/troubleshooting.md 18)
import type { ReactNode } from 'react';

import type { FeedItem } from '../api/questions';

export type SwipeDirection = 'left' | 'right' | 'up';

// 어느 축의 스와이프를 받을지. none 이면 받지 않는다
export type SwipeAxis = 'horizontal' | 'vertical' | 'none';

export type SwipeAreaProps = {
  axis: SwipeAxis;
  onSwipe: (direction: SwipeDirection) => void;
  // 스와이프가 시작되고 끝날 때 알린다. 스와이프 중에 눌린 버튼을 무시하는 데 쓴다
  onActiveChange?: (active: boolean) => void;
  children: ReactNode;
};

export type SwipeInput = {
  dx: number; // 가로로 움직인 거리. 오른쪽이 +
  dy: number; // 세로로 움직인 거리. 아래쪽이 +
  vx: number; // 놓는 순간의 가로 속도
  vy: number;
};

export const SWIPE_MIN_DISTANCE = 80; // 이만큼 밀면 속도와 관계없이 인정
export const SWIPE_FLICK_DISTANCE = 30; // 빠르게 튕겼을 때 필요한 최소 거리
export const SWIPE_FLICK_VELOCITY = 600; // "빠르게"의 기준
export const SWIPE_AXIS_RATIO = 1.5; // 한 축이 다른 축보다 이 배수 이상 커야 그 축으로 본다 (대각선은 버린다)

export function classifySwipe({ dx, dy, vx, vy }: SwipeInput): SwipeDirection | null {
  const absX = Math.abs(dx);
  const absY = Math.abs(dy);

  if (absX >= absY * SWIPE_AXIS_RATIO) {
    return isFarEnough(absX, vx) ? (dx < 0 ? 'left' : 'right') : null;
  }
  if (absY >= absX * SWIPE_AXIS_RATIO) {
    // 아래로 미는 동작에는 기능이 없다
    return dy < 0 && isFarEnough(absY, vy) ? 'up' : null;
  }
  return null;
}

function isFarEnough(distance: number, velocity: number): boolean {
  if (distance >= SWIPE_MIN_DISTANCE) {
    return true;
  }
  return distance >= SWIPE_FLICK_DISTANCE && Math.abs(velocity) >= SWIPE_FLICK_VELOCITY;
}

// 좌우 스와이프로 고를 수 있는 카드인가. 선택지가 2개인 글형만 해당한다.
// 3개 이상이면 방향과 선택지를 짝지을 수 없고, 사진형은 사진을 직접 눌러 고른다
export function canSwipeToChoose(card: FeedItem): boolean {
  return card.questionType === 'TEXT' && card.options.length === 2;
}

// 스와이프 방향에 해당하는 선택지 id. 왼쪽 = 첫 번째, 오른쪽 = 두 번째
export function optionForSwipe(card: FeedItem, direction: SwipeDirection): number | undefined {
  if (!canSwipeToChoose(card) || direction === 'up') {
    return undefined;
  }
  // 배열 순서를 믿지 않고 sortOrder 로 정렬한다. [...배열] 은 원본을 건드리지 않으려고 복사하는 것이다
  const [first, second] = [...card.options].sort((a, b) => a.sortOrder - b.sortOrder);
  return direction === 'left' ? first.id : second.id;
}
