// 상단 노출 흐름 테스트: 결과별 처리와, 다시 시도할 때 어떤 키가 나가는지
import { ApiError, NETWORK_ERROR } from '../api/errors';
import type { BoostResponse } from '../api/points';
import { BOOST_COST, previewBoost, submitBoost, type BoostApi } from '../mine/boostFlow';
import { BoostKeys } from '../mine/boostKeys';

function boosted(questionId: number, balanceAfter = 12): BoostResponse {
  return { questionId, boostedUntil: '2026-10-01T18:00:00', cost: 100, balanceAfter, ledgerId: 501 };
}

function setup() {
  let made = 0;
  const keys = new BoostKeys(() => `key-${(made += 1)}`);
  const boost = jest.fn(async (questionId: number, _key: string) => boosted(questionId));
  const api: BoostApi = { boost };
  // 서버로 나간 키를 순서대로 꺼낸다
  const sentKeys = () => boost.mock.calls.map(([, key]) => key);
  return { api, keys, boost, sentKeys };
}

const NETWORK = new ApiError(0, NETWORK_ERROR, '서버에 연결할 수 없습니다.');

function apiError(status: number, code: string, message = code): ApiError {
  return new ApiError(status, code, message);
}

describe('submitBoost', () => {
  it('성공하면 응답을 돌려주고 키를 버린다', async () => {
    const { api, keys, boost } = setup();

    const outcome = await submitBoost(api, keys, 42);

    expect(boost).toHaveBeenCalledWith(42, 'key-1');
    expect(outcome).toEqual({ kind: 'boosted', response: boosted(42) });
    expect(keys.hasPending(42)).toBe(false);
  });

  it('네트워크 오류 뒤에 다시 시도하면 같은 키가 나간다', async () => {
    const { api, keys, boost, sentKeys } = setup();
    boost.mockRejectedValueOnce(NETWORK);

    const first = await submitBoost(api, keys, 42);
    const second = await submitBoost(api, keys, 42);

    expect(first.kind).toBe('retry');
    expect(second.kind).toBe('boosted');
    expect(sentKeys()).toEqual(['key-1', 'key-1']);
  });

  it('여러 번 실패해도 계속 같은 키다', async () => {
    const { api, keys, boost, sentKeys } = setup();
    boost.mockRejectedValueOnce(NETWORK);
    boost.mockRejectedValueOnce(apiError(500, 'INTERNAL_ERROR'));
    boost.mockRejectedValueOnce(new TypeError('예상 못 한 오류'));

    await submitBoost(api, keys, 42);
    await submitBoost(api, keys, 42);
    await submitBoost(api, keys, 42);
    await submitBoost(api, keys, 42);

    expect(sentKeys()).toEqual(['key-1', 'key-1', 'key-1', 'key-1']);
  });

  it('성공한 뒤에 다시 누르면 새 키가 나간다', async () => {
    const { api, keys, sentKeys } = setup();

    await submitBoost(api, keys, 42);
    await submitBoost(api, keys, 42);

    expect(sentKeys()).toEqual(['key-1', 'key-2']);
  });

  it('다른 고민이면 다른 키가 나간다', async () => {
    const { api, keys, sentKeys } = setup();

    await submitBoost(api, keys, 42);
    await submitBoost(api, keys, 43);

    expect(sentKeys()).toEqual(['key-1', 'key-2']);
  });

  it('42 가 실패한 채로 43 을 성공시키고 돌아와도 42 는 원래 키로 나간다', async () => {
    const { api, keys, boost, sentKeys } = setup();
    boost.mockRejectedValueOnce(NETWORK);

    await submitBoost(api, keys, 42);
    await submitBoost(api, keys, 43);
    await submitBoost(api, keys, 42);

    expect(sentKeys()).toEqual(['key-1', 'key-2', 'key-1']);
  });

  it('POINT_WALLET_CONFLICT 는 잠시 후 다시 시도하라고 안내하고 키를 유지한다', async () => {
    const { api, keys, boost } = setup();
    boost.mockRejectedValueOnce(apiError(409, 'POINT_WALLET_CONFLICT'));

    const outcome = await submitBoost(api, keys, 42);

    expect(outcome).toEqual({ kind: 'retry', message: '포인트 처리가 몰렸어요. 잠시 후 다시 시도해 주세요.' });
    expect(keys.keyFor(42)).toBe('key-1');
  });

  it.each([
    ['POINT_INSUFFICIENT', 409, 'insufficient', '포인트가 부족해요'],
    ['QUESTION_CLOSED', 409, 'closed', '종료되었거나 숨겨진 고민'],
    ['QUESTION_NOT_FOUND', 404, 'gone', '찾을 수 없는 고민'],
    ['FORBIDDEN', 403, 'gone', '내가 올린 고민만'],
  ])('%s 는 %d 로 거절되고 "%s" 로 안내한다', async (code, status, kind, text) => {
    const { api, keys, boost } = setup();
    boost.mockRejectedValueOnce(apiError(status, code));

    const outcome = await submitBoost(api, keys, 42);

    expect(outcome.kind).toBe(kind);
    expect(outcome.kind !== 'boosted' && outcome.message).toContain(text);
  });

  it('잔액 부족으로 거절된 뒤 포인트를 모아 다시 누르면 같은 키로 나간다 (새 키는 성공 후에만)', async () => {
    const { api, keys, boost, sentKeys } = setup();
    boost.mockRejectedValueOnce(apiError(409, 'POINT_INSUFFICIENT'));

    await submitBoost(api, keys, 42);
    const outcome = await submitBoost(api, keys, 42);

    expect(outcome.kind).toBe('boosted');
    expect(sentKeys()).toEqual(['key-1', 'key-1']);
  });

  it('IDEMPOTENCY_KEY_CONFLICT 는 키를 버린다. 다시 누르면 새 키가 나간다', async () => {
    const { api, keys, boost, sentKeys } = setup();
    boost.mockRejectedValueOnce(apiError(409, 'IDEMPOTENCY_KEY_CONFLICT'));

    const first = await submitBoost(api, keys, 42);
    const second = await submitBoost(api, keys, 42);

    expect(first.kind).toBe('keyConflict');
    expect(second.kind).toBe('boosted');
    expect(sentKeys()).toEqual(['key-1', 'key-2']);
  });

  it('어떤 실패에도 예외를 던지지 않는다', async () => {
    const { api, keys, boost } = setup();
    boost.mockRejectedValueOnce('문자열 예외');

    await expect(submitBoost(api, keys, 42)).resolves.toMatchObject({ kind: 'retry' });
  });

  it('결과를 모르는 실패의 안내에는 한 번만 차감된다는 말이 들어간다', async () => {
    const { api, keys, boost } = setup();
    boost.mockRejectedValueOnce(NETWORK);

    const outcome = await submitBoost(api, keys, 42);

    expect(outcome).toEqual({
      kind: 'retry',
      message: '서버에 연결할 수 없습니다. 다시 시도해도 포인트는 한 번만 차감돼요.',
    });
  });
});

describe('previewBoost (확인 창 내용)', () => {
  const now = Date.UTC(2026, 8, 30, 3, 0, 0); // KST 9월 30일 12:00

  it('차감 금액, 현재 잔액, 차감 후 잔액', () => {
    expect(previewBoost({}, 130, now)).toEqual({
      cost: BOOST_COST,
      balance: 130,
      balanceAfter: 30,
      affordable: true,
      extending: false,
    });
  });

  it('정확히 100P 면 쓸 수 있고, 99P 면 쓸 수 없다', () => {
    expect(previewBoost({}, 100, now)).toMatchObject({ affordable: true, balanceAfter: 0 });
    expect(previewBoost({}, 99, now)).toMatchObject({ affordable: false });
  });

  it('잔액을 아직 모르면 막지 않는다 (서버가 판정한다)', () => {
    expect(previewBoost({}, undefined, now)).toMatchObject({
      balance: undefined,
      balanceAfter: undefined,
      affordable: true,
    });
  });

  it('노출 중이면 연장, 끝났거나 쓴 적이 없으면 새로 시작', () => {
    expect(previewBoost({ boostedUntil: '2026-09-30T12:00:01' }, 200, now).extending).toBe(true);
    expect(previewBoost({ boostedUntil: '2026-09-30T12:00:00' }, 200, now).extending).toBe(false);
    expect(previewBoost({ boostedUntil: '2026-09-29T12:00:00' }, 200, now).extending).toBe(false);
    expect(previewBoost({}, 200, now).extending).toBe(false);
  });
});
