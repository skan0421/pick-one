// 현재 로그인 세션의 토큰을 메모리에 들고 있는 곳.
// 저장소(tokenStorage)는 읽고 쓰는 데 시간이 걸리는 비동기 작업이라, 요청마다 읽지 않고
// 메모리의 값을 쓴다. 저장소는 앱을 다시 켰을 때 복원하는 용도다.
//
// 모듈 최상위 변수는 앱 전체에서 하나만 존재한다 (Spring 의 싱글턴 빈 필드와 같다).
import { clearTokens, loadTokens, saveTokens } from './tokenStorage';
import type { Tokens } from './tokens';

let current: Tokens | null = null;

export function getTokens(): Tokens | null {
  return current;
}

// 앱 시작 시 한 번 호출해 저장소의 토큰을 메모리로 올린다
export async function restoreTokens(): Promise<Tokens | null> {
  current = await loadTokens();
  return current;
}

export async function setTokens(tokens: Tokens): Promise<void> {
  // 메모리를 먼저 바꾼다. 저장이 끝나기를 기다리는 동안 출발하는 요청도 새 토큰을 쓰게 하기 위해서다
  current = tokens;
  await saveTokens(tokens);
}

export async function clearSession(): Promise<void> {
  current = null;
  await clearTokens();
}
