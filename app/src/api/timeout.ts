// 요청의 제한 시간. 앱·웹 공용이고 기기 기능을 쓰지 않는다.
//
// fetch 에는 제한 시간이 없다. 연결이 끊긴 것을 기기가 알아채지 못하면(VPN 이 꺼짐, 서버가 응답 없이 멈춤)
// 응답도 예외도 오지 않아 화면이 끝없이 기다린다 (docs/troubleshooting.md 24).
// Java 의 HttpClient 에 connectTimeout/readTimeout 을 주는 것과 같은 일을 직접 해야 한다

// 우리 서버로 가는 요청 (api/client.ts). 보내기 시작해서 응답 본문을 다 읽을 때까지의 시간이다
export const REQUEST_TIMEOUT_MS = 15_000;
// 저장소로 사진을 올리는 PUT (compose/fileTransfer.ts, fileTransfer.web.ts).
// 파일을 보내는 시간이 들어가므로 더 길게 잡는다. 올리는 사진은 줄인 뒤라 대개 1MB 안팎이고,
// 60초면 초당 약 17KB(1MB 기준)의 느린 연결까지 받아 준다. 업로드 주소의 수명(5분)보다는 짧다
export const UPLOAD_TIMEOUT_MS = 60_000;

// 제한 시간이 지났음을 알리는 예외. 부르는 쪽이 자기 문구의 ApiError 로 바꾼다
export class TimeoutError extends Error {
  constructor(readonly timeoutMs: number) {
    super(`제한 시간(${timeoutMs}ms)이 지났습니다.`);
    this.name = 'TimeoutError';
  }
}

// run 을 실행하되 ms 안에 끝나지 않으면 TimeoutError 를 던진다.
// run 은 받은 signal 을 fetch 등에 넘겨야 한다. 시간이 지나면 signal 로 요청을 취소한다 (Java 의 Future.cancel).
//
// 취소만 믿지 않고 타이머와 경주(Promise.race)시킨다. 취소 신호를 무시하는 구현을 만나도 기다림은 ms 에서 끝난다.
// 주의: 취소는 "앱이 그만 기다린다"는 뜻일 뿐이다. 이미 서버에 닿은 요청은 서버에서 처리됐을 수 있다
export async function withTimeout<T>(ms: number, run: (signal: AbortSignal) => Promise<T>): Promise<T> {
  const controller = new AbortController();
  let timer: ReturnType<typeof setTimeout> | undefined;
  const expired = new Promise<never>((_, reject) => {
    timer = setTimeout(() => {
      reject(new TimeoutError(ms));
      controller.abort();
    }, ms);
  });

  try {
    return await Promise.race([run(controller.signal), expired]);
  } finally {
    clearTimeout(timer);
  }
}
