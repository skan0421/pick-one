// Idempotency-Key 규칙 테스트: 언제 같은 키를 쓰고 언제 새 키를 만드는가 (docs/api.md 1.6)
import { BoostKeys } from '../mine/boostKeys';
import { uuidFromBytes } from '../mine/uuid';

// 부를 때마다 key-1, key-2 ... 를 돌려주는 가짜 UUID 생성기
function fakeKeys() {
  let made = 0;
  const newKey = jest.fn(() => {
    made += 1;
    return `key-${made}`;
  });
  return { keys: new BoostKeys(newKey), newKey };
}

describe('BoostKeys', () => {
  it('처음 누르면 새 키를 만든다', () => {
    const { keys, newKey } = fakeKeys();

    expect(keys.keyFor(42)).toBe('key-1');
    expect(newKey).toHaveBeenCalledTimes(1);
  });

  it('성공하기 전에는 몇 번을 다시 시도해도 같은 키다 (네트워크 오류, 타임아웃)', () => {
    const { keys, newKey } = fakeKeys();

    const first = keys.keyFor(42);
    const second = keys.keyFor(42);
    const third = keys.keyFor(42);

    expect(second).toBe(first);
    expect(third).toBe(first);
    expect(newKey).toHaveBeenCalledTimes(1);
  });

  it('성공한 뒤에 다시 누르면 새 키다 (연장은 새 결제다)', () => {
    const { keys } = fakeKeys();

    const first = keys.keyFor(42);
    keys.settle(42);
    const second = keys.keyFor(42);

    expect(first).toBe('key-1');
    expect(second).toBe('key-2');
  });

  it('다른 고민이면 다른 키다', () => {
    const { keys } = fakeKeys();

    expect(keys.keyFor(42)).toBe('key-1');
    expect(keys.keyFor(43)).toBe('key-2');
  });

  it('다른 고민을 시도하고 돌아와도, 끝나지 않은 고민은 원래 키를 쓴다', () => {
    const { keys } = fakeKeys();

    const a = keys.keyFor(42); // 응답을 받지 못함
    const b = keys.keyFor(43);
    keys.settle(43); // 43 은 성공

    expect(keys.keyFor(42)).toBe(a);
    expect(keys.keyFor(43)).not.toBe(b);
  });

  it('한 고민의 성공이 다른 고민의 키를 지우지 않는다', () => {
    const { keys } = fakeKeys();
    keys.keyFor(42);
    keys.keyFor(43);

    keys.settle(42);

    expect(keys.hasPending(42)).toBe(false);
    expect(keys.hasPending(43)).toBe(true);
  });

  it('키 충돌로 버린 뒤에는 새 키다', () => {
    const { keys } = fakeKeys();

    const first = keys.keyFor(42);
    keys.discard(42);

    expect(keys.keyFor(42)).not.toBe(first);
  });

  it('시도한 적 없는 고민을 끝내도 문제없다', () => {
    const { keys } = fakeKeys();

    keys.settle(99);

    expect(keys.hasPending(99)).toBe(false);
    expect(keys.keyFor(99)).toBe('key-1');
  });
});

describe('uuidFromBytes', () => {
  // 서버가 검사하는 형식 (BoostService 의 정규식과 같다)
  const SERVER_PATTERN = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

  it('서버가 받는 UUID 형식으로 만든다', () => {
    const bytes = Uint8Array.from({ length: 16 }, (_, i) => i * 17);

    expect(uuidFromBytes(bytes)).toMatch(SERVER_PATTERN);
  });

  it('버전 4 와 변형 표시를 넣는다', () => {
    const uuid = uuidFromBytes(new Uint8Array(16).fill(0xff));

    expect(uuid).toBe('ffffffff-ffff-4fff-bfff-ffffffffffff');
  });

  it('받은 배열을 고치지 않는다', () => {
    const bytes = new Uint8Array(16).fill(0xff);

    uuidFromBytes(bytes);

    expect(Array.from(bytes)).toEqual(new Array(16).fill(0xff));
  });

  it('난수가 다르면 다른 값이다', () => {
    expect(uuidFromBytes(new Uint8Array(16).fill(1))).not.toBe(uuidFromBytes(new Uint8Array(16).fill(2)));
  });
});
