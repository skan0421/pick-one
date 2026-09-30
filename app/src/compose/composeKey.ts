// 고민 등록 요청에 붙일 Idempotency-Key 를 정하는 규칙 (docs/api.md 1.6, 4.1).
// 화면도 서버 호출도 없는 순수 로직이다 (src/__tests__/composeKey.test.ts). mine/boostKeys.ts 와 같은 생각이다
//
// 왜 필요한가
//   등록 요청을 보냈는데 응답을 받지 못했다면(네트워크 끊김, 시간 초과) 앱은 서버가 등록했는지 알 수 없다.
//   이때 "새 요청"을 보내면 같은 고민이 두 번 올라간다 (docs/troubleshooting.md 24).
//   같은 키로 다시 보내면 서버가 같은 요청임을 알아보고, 새로 등록하지 않고 처음 결과를 돌려준다.
//
// 규칙
//   1. 키 하나는 "이 고민을 올리겠다"는 사용자의 의도 하나다
//   2. 그 의도가 성공으로 끝나기 전에는 몇 번을 다시 보내든 같은 키를 쓴다
//   3. 성공하면 키를 버린다. 다음에 올리는 것은 새 고민이므로 새 키를 만든다
//   4. 키는 등록 요청을 보낼 때 만든다. 사진형은 사진을 다 올린 뒤에야 등록하므로 그때 만들어진다
//
// boostKeys.ts 는 고민마다 키를 따로 들지만(Map), 올리기 화면은 한 번에 고민 하나만 쓰므로 키도 하나다
export class ComposeKey {
  // 아직 끝나지 않은 의도의 키. 없으면 undefined
  private pending: string | undefined;

  // newKey: 새 UUID 를 만드는 함수. 밖에서 넣어 준다 (생성자 주입). 테스트에서는 정해진 값을 돌려주는 가짜를 넣는다
  constructor(private readonly newKey: () => string) {}

  // 등록 요청에 붙일 키. 끝나지 않은 의도가 있으면 그 키를, 없으면 새로 만들어 기억한다
  use(): string {
    if (this.pending === undefined) {
      this.pending = this.newKey();
    }
    return this.pending;
  }

  // 등록됐다. 의도가 끝났으므로 키를 버린다
  settle(): void {
    this.pending = undefined;
  }

  // 서버가 "이 키는 다른 내용의 요청에 이미 쓰였다"고 답했다 (IDEMPOTENCY_KEY_CONFLICT).
  // 이 키로는 몇 번을 보내도 같은 답이 오므로 버린다
  discard(): void {
    this.pending = undefined;
  }

  // 끝나지 않은 의도가 있는가 (앞선 등록 요청의 결과를 모르는 상태일 수 있다)
  hasPending(): boolean {
    return this.pending !== undefined;
  }
}
