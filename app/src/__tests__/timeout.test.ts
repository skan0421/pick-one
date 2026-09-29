// 제한 시간 헬퍼 테스트. 실제로 기다리지 않고 가짜 시계를 돌린다
// (jest.useFakeTimers 는 setTimeout 을 가짜로 바꾼다. Java 테스트에서 Clock 을 고정하는 것과 비슷하다)
import { REQUEST_TIMEOUT_MS, TimeoutError, UPLOAD_TIMEOUT_MS, withTimeout } from '../api/timeout';

// 끝나지 않는 작업. 취소 신호도 무시한다
function never<T>(): Promise<T> {
  return new Promise<T>(() => undefined);
}

// 던져진 예외를 꺼낸다. 호출 즉시 catch 를 붙여 두므로 시계를 돌리는 동안 "처리되지 않은 예외"가 되지 않는다
function caught(promise: Promise<unknown>): Promise<unknown> {
  return promise.then(
    () => {
      throw new Error('실패해야 하는데 성공했습니다.');
    },
    (error: unknown) => error,
  );
}

beforeEach(() => {
  jest.useFakeTimers();
});

afterEach(() => {
  jest.useRealTimers();
});

describe('withTimeout', () => {
  it('API 요청은 15초, 사진 올리기는 그보다 긴 60초다', () => {
    expect(REQUEST_TIMEOUT_MS).toBe(15_000);
    expect(UPLOAD_TIMEOUT_MS).toBe(60_000);
  });

  it('제때 끝나면 결과를 돌려주고 타이머를 남기지 않는다', async () => {
    await expect(withTimeout(1000, async () => 'ok')).resolves.toBe('ok');

    expect(jest.getTimerCount()).toBe(0);
  });

  it('작업이 던진 예외는 그대로 전달하고 타이머를 남기지 않는다', async () => {
    const boom = new Error('boom');

    await expect(
      withTimeout(1000, async () => {
        throw boom;
      }),
    ).rejects.toBe(boom);

    expect(jest.getTimerCount()).toBe(0);
  });

  it('제한 시간이 지나면 TimeoutError 를 던지고 취소 신호를 보낸다', async () => {
    let seen: AbortSignal | undefined;
    const pending = caught(
      withTimeout(1000, (signal) => {
        seen = signal;
        return never();
      }),
    );

    await jest.advanceTimersByTimeAsync(1000);
    const error = await pending;

    expect(error).toBeInstanceOf(TimeoutError);
    expect((error as TimeoutError).timeoutMs).toBe(1000);
    expect(seen?.aborted).toBe(true);
  });

  it('제한 시간 직전에는 끝나지 않고 취소하지도 않는다', async () => {
    let seen: AbortSignal | undefined;
    let settled = false;
    const pending = caught(
      withTimeout(1000, (signal) => {
        seen = signal;
        return never();
      }),
    ).then(() => {
      settled = true;
    });

    await jest.advanceTimersByTimeAsync(999);
    expect(settled).toBe(false);
    expect(seen?.aborted).toBe(false);

    await jest.advanceTimersByTimeAsync(1);
    await pending;
    expect(settled).toBe(true);
  });

  it('취소되면서 작업이 다른 예외로 끝나도 TimeoutError 로 알린다', async () => {
    const pending = caught(
      withTimeout(
        1000,
        (signal) =>
          new Promise((_, reject) => {
            signal.addEventListener('abort', () => reject(new Error('Aborted')));
          }),
      ),
    );

    await jest.advanceTimersByTimeAsync(1000);

    expect(await pending).toBeInstanceOf(TimeoutError);
  });
});
