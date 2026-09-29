// 상단 노출 요청에 붙일 Idempotency-Key 를 정하는 규칙 (docs/api.md 1.6).
// 화면도 서버 호출도 없는 순수 로직이다 (src/__tests__/boostKeys.test.ts)
//
// 왜 필요한가
//   요청을 보냈는데 응답을 받지 못했다면(네트워크 끊김, 타임아웃) 앱은 서버가 처리했는지 알 수 없다.
//   서버는 이미 100P 를 뺐을 수도 있다. 이때 "새 요청"을 보내면 100P 가 한 번 더 빠진다.
//   같은 키로 다시 보내면 서버가 같은 요청임을 알아보고, 빼지 않고 처음 결과를 돌려준다.
//
// 규칙
//   1. 키 하나는 "이 고민을 상단에 올리겠다"는 사용자의 의도 하나다
//   2. 그 의도가 성공으로 끝나기 전에는 몇 번을 다시 보내든 같은 키를 쓴다
//   3. 성공하면 키를 버린다. 다음에 누르는 것은 새 의도(연장)이므로 새 키를 만든다
//   4. 다른 고민은 다른 의도이므로 다른 키를 쓴다
//
// Java 에 빗대면 Map<Long, UUID> 를 든 작은 저장소다.
// 결제에서 주문번호를 먼저 만들어 두고, 결제 요청을 다시 보낼 때 같은 주문번호를 쓰는 것과 같다
export class BoostKeys {
  // 고민 id → 아직 끝나지 않은 의도의 키
  private readonly pending = new Map<number, string>();

  // newKey: 새 UUID 를 만드는 함수. 밖에서 넣어 준다 (생성자 주입). 테스트에서는 정해진 값을 돌려주는 가짜를 넣는다
  constructor(private readonly newKey: () => string) {}

  // 이 고민의 요청에 붙일 키. 끝나지 않은 의도가 있으면 그 키를, 없으면 새로 만들어 기억한다
  keyFor(questionId: number): string {
    const existing = this.pending.get(questionId);
    if (existing !== undefined) {
      return existing;
    }
    const created = this.newKey();
    this.pending.set(questionId, created);
    return created;
  }

  // 성공했다. 의도가 끝났으므로 키를 버린다
  settle(questionId: number): void {
    this.pending.delete(questionId);
  }

  // 서버가 "이 키는 다른 요청에 이미 쓰였다"고 답했다 (IDEMPOTENCY_KEY_CONFLICT).
  // 이 키로는 몇 번을 보내도 같은 답이 오므로 버린다. 이 경우 포인트는 빠지지 않았다
  discard(questionId: number): void {
    this.pending.delete(questionId);
  }

  // 끝나지 않은 의도가 있는가 (앞선 요청의 결과를 모르는 상태인가)
  hasPending(questionId: number): boolean {
    return this.pending.has(questionId);
  }
}
