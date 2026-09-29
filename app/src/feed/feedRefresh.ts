// "피드를 새로 받아야 한다"는 표시.
//
// 피드는 캐시(react-query)가 아니라 FeedController 가 직접 상태를 들고 있어, 다른 화면이 캐시를 비우는 방식으로는
// 새로 받게 할 수 없다. 그래서 번호 하나를 두고, 다른 화면은 번호를 올리고 피드는 다시 보일 때 번호를 비교한다
// (Java 의 AtomicLong 버전 번호. 단일 스레드라 일반 값으로 충분하다)
let version = 0;

// 피드 내용이 달라질 일을 했을 때 부른다 (예: 상단 노출)
export function markFeedStale(): void {
  version += 1;
}

export function feedVersion(): number {
  return version;
}
