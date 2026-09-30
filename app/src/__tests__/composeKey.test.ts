// 고민 등록의 Idempotency-Key 규칙 테스트: 언제 같은 키를 쓰고 언제 새 키를 만드는가 (docs/api.md 1.6, 4.1)
import { ComposeKey } from '../compose/composeKey';

function setup() {
  let made = 0;
  const newKey = jest.fn(() => `key-${(made += 1)}`);
  return { key: new ComposeKey(newKey), newKey };
}

describe('ComposeKey', () => {
  it('처음에는 끝나지 않은 의도가 없고, 키를 만들지도 않는다', () => {
    const { key, newKey } = setup();

    expect(key.hasPending()).toBe(false);
    expect(newKey).not.toHaveBeenCalled();
  });

  it('처음 쓸 때 키를 만들어 기억한다', () => {
    const { key, newKey } = setup();

    expect(key.use()).toBe('key-1');
    expect(key.hasPending()).toBe(true);
    expect(newKey).toHaveBeenCalledTimes(1);
  });

  it('끝나기 전에는 몇 번을 써도 같은 키다', () => {
    const { key, newKey } = setup();

    expect([key.use(), key.use(), key.use()]).toEqual(['key-1', 'key-1', 'key-1']);
    expect(newKey).toHaveBeenCalledTimes(1);
  });

  it('성공하면 키를 버린다. 다음에는 새 키다', () => {
    const { key } = setup();

    key.use();
    key.settle();

    expect(key.hasPending()).toBe(false);
    expect(key.use()).toBe('key-2');
  });

  it('키 충돌로 버리면 다음에는 새 키다', () => {
    const { key } = setup();

    key.use();
    key.discard();

    expect(key.hasPending()).toBe(false);
    expect(key.use()).toBe('key-2');
  });

  it('쓴 적 없는 상태에서 끝내거나 버려도 문제없다', () => {
    const { key } = setup();

    key.settle();
    key.discard();

    expect(key.use()).toBe('key-1');
  });
});
