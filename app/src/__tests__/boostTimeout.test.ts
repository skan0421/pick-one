// 상단 노출 요청이 시간 초과된 뒤 다시 시도하면 같은 Idempotency-Key 가 나가는지 확인한다 (docs/troubleshooting.md 24).
//
// boostFlow.test.ts 는 서버 호출을 가짜로 바꿔 흐름만 본다. 여기서는 가짜로 바꾸는 것이 fetch 하나뿐이다.
// 키 규칙(BoostKeys) → 흐름(submitBoost) → API(boostQuestion) → 공통 클라이언트(request)를 실제 코드로 엮어,
// 서버로 나가는 헤더를 직접 본다. 15초는 가짜 시계로 돌린다
import { boostQuestion } from '../api/points';
import { setTokens } from '../auth/session';
import { submitBoost, type BoostApi } from '../mine/boostFlow';
import { BoostKeys } from '../mine/boostKeys';

jest.mock('../auth/tokenStorage', () => ({
  loadTokens: jest.fn(async () => null),
  saveTokens: jest.fn(async () => undefined),
  clearTokens: jest.fn(async () => undefined),
}));

type Sent = { url: string; method: string; key: string | undefined; signal: AbortSignal | undefined };
type FakeResponse = { ok: boolean; status: number; json: () => Promise<unknown>; text: () => Promise<string> };

const KEY_1 = '11111111-1111-4111-8111-111111111111';
const KEY_2 = '22222222-2222-4222-8222-222222222222';

const api: BoostApi = { boost: boostQuestion };

const sent: Sent[] = [];
// 서버가 포인트를 뺀 횟수와, 키별로 기억해 둔 처음 결과 (서버의 Idempotency-Key 처리를 흉내 낸다)
let charges = 0;
let processed: Map<string, unknown>;
// 앞에서부터 몇 번의 요청에 응답하지 않을지
let silentCount = 0;
// 응답하지 않는 요청도 서버에는 닿아 처리되는가 (응답만 유실된 경우)
let processSilently = false;
let keys: BoostKeys;

function ok(body: unknown): FakeResponse {
  const text = JSON.stringify(body);
  return { ok: true, status: 200, json: async () => JSON.parse(text), text: async () => text };
}

// 같은 키가 다시 오면 포인트를 빼지 않고 처음 결과를 돌려준다
function serverProcess(key: string): unknown {
  const before = processed.get(key);
  if (before !== undefined) {
    return before;
  }
  charges += 1;
  const result = { questionId: 42, boostedUntil: '2026-10-01T18:00:00', cost: 100, balanceAfter: 30, ledgerId: 501 };
  processed.set(key, result);
  return result;
}

beforeEach(async () => {
  jest.useFakeTimers();
  process.env.EXPO_PUBLIC_API_URL = 'http://test-server:8080';
  sent.length = 0;
  charges = 0;
  processed = new Map();
  silentCount = 0;
  processSilently = false;
  const made = [KEY_1, KEY_2];
  keys = new BoostKeys(() => made.shift() ?? 'no-more-keys');
  await setTokens({ accessToken: 'access', refreshToken: 'refresh' });

  globalThis.fetch = jest.fn(async (url: string, init: RequestInit) => {
    const headers = (init.headers ?? {}) as Record<string, string>;
    const call: Sent = {
      url,
      method: init.method ?? 'GET',
      key: headers['Idempotency-Key'],
      signal: init.signal ?? undefined,
    };
    sent.push(call);
    if (sent.length <= silentCount) {
      if (processSilently && call.key) {
        serverProcess(call.key);
      }
      return new Promise<FakeResponse>(() => undefined);
    }
    return ok(serverProcess(call.key ?? ''));
  }) as unknown as typeof fetch;
});

afterEach(() => {
  jest.useRealTimers();
});

// 확인을 누르고 15초가 지날 때까지 기다린다
async function pressAndWaitForTimeout() {
  const pending = submitBoost(api, keys, 42);
  await jest.advanceTimersByTimeAsync(15_000);
  return pending;
}

describe('상단 노출 시간 초과', () => {
  it('15초 동안 응답이 없으면 다시 시도 안내로 끝난다 (로딩이 계속 돌지 않는다)', async () => {
    silentCount = 1;

    const outcome = await pressAndWaitForTimeout();

    expect(outcome).toEqual({
      kind: 'retry',
      message: '서버가 응답하지 않습니다. 네트워크 연결을 확인해 주세요. 다시 시도해도 포인트는 한 번만 차감돼요.',
    });
    expect(sent[0].signal?.aborted).toBe(true);
  });

  it('시간 초과 뒤에도 키를 기억한다', async () => {
    silentCount = 1;

    await pressAndWaitForTimeout();

    expect(keys.hasPending(42)).toBe(true);
    expect(keys.keyFor(42)).toBe(KEY_1);
  });

  it('시간 초과 뒤 다시 시도하면 같은 Idempotency-Key 가 나간다', async () => {
    silentCount = 1;

    const first = await pressAndWaitForTimeout();
    const second = await submitBoost(api, keys, 42);

    expect(first.kind).toBe('retry');
    expect(second.kind).toBe('boosted');
    expect(sent.map((call) => call.url)).toEqual([
      'http://test-server:8080/api/v1/questions/42/boosts',
      'http://test-server:8080/api/v1/questions/42/boosts',
    ]);
    expect(sent.map((call) => call.method)).toEqual(['POST', 'POST']);
    expect(sent.map((call) => call.key)).toEqual([KEY_1, KEY_1]);
  });

  it('연달아 시간 초과되어도 계속 같은 키가 나간다', async () => {
    silentCount = 3;

    await pressAndWaitForTimeout();
    await pressAndWaitForTimeout();
    await pressAndWaitForTimeout();
    const last = await submitBoost(api, keys, 42);

    expect(last.kind).toBe('boosted');
    expect(sent.map((call) => call.key)).toEqual([KEY_1, KEY_1, KEY_1, KEY_1]);
  });

  it('서버가 처리했는데 응답만 오지 않았어도, 다시 시도에서 포인트는 한 번만 빠진다', async () => {
    silentCount = 1;
    processSilently = true;

    const first = await pressAndWaitForTimeout();
    expect(first.kind).toBe('retry');
    expect(charges).toBe(1); // 앱은 실패로 알지만 서버는 이미 뺐다

    const second = await submitBoost(api, keys, 42);

    expect(second).toMatchObject({ kind: 'boosted', response: { cost: 100, balanceAfter: 30, ledgerId: 501 } });
    expect(charges).toBe(1);
    expect(sent.map((call) => call.key)).toEqual([KEY_1, KEY_1]);
  });

  it('다시 시도가 성공한 뒤에 누르면(연장) 새 키가 나간다', async () => {
    silentCount = 1;

    await pressAndWaitForTimeout();
    await submitBoost(api, keys, 42);
    await submitBoost(api, keys, 42);

    expect(sent.map((call) => call.key)).toEqual([KEY_1, KEY_1, KEY_2]);
    expect(charges).toBe(2);
  });
});
