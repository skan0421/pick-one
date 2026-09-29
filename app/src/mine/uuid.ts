// UUID(v4) 만들기. Java 의 UUID.randomUUID() 와 같다.
// 서버는 Idempotency-Key 가 UUID 형식(8-4-4-4-12 자리의 16진수)이 아니면 거절한다
import * as Crypto from 'expo-crypto';

export function newUuid(): string {
  try {
    return Crypto.randomUUID();
  } catch {
    // 웹 브라우저는 https 나 localhost 가 아닌 주소(예: http://100.x.y.z:8081)에서 randomUUID 를 주지 않는다.
    // 난수 16바이트는 어디서나 받을 수 있으므로 그것으로 직접 만든다
    return uuidFromBytes(Crypto.getRandomBytes(16));
  }
}

// 난수 16바이트를 UUID v4 글자로 바꾼다 (RFC 4122). 순수 함수 (src/__tests__/boostKeys.test.ts)
export function uuidFromBytes(source: Uint8Array): string {
  const bytes = Uint8Array.from(source);
  bytes[6] = (bytes[6] & 0x0f) | 0x40; // 버전 4
  bytes[8] = (bytes[8] & 0x3f) | 0x80; // 변형(variant) 표시
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
