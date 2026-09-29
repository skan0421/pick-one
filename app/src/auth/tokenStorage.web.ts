// 토큰 저장소 — 웹용. 웹 브라우저에는 expo-secure-store 가 없어 sessionStorage 를 쓴다.
//
// 보안상 한계 (자세한 내용은 app/README.md):
// - 자바스크립트로 읽을 수 있는 저장소라 XSS 공격에 성공한 스크립트는 토큰을 읽을 수 있다
// - localStorage 대신 sessionStorage 를 고른 이유: 탭마다 분리되고 탭을 닫으면 지워진다.
//   localStorage 는 탭끼리 공유되어 두 탭이 같은 refresh 토큰으로 동시에 재발급할 수 있고,
//   서버는 이를 탈취로 보고 모든 기기를 로그아웃시킨다 (docs/api.md 1.3)
// - 단, 브라우저의 "탭 복제"는 sessionStorage 를 복사하므로 그 경우에는 같은 문제가 생길 수 있다
import type { Tokens } from './tokenStorage';
import { parseTokens } from './tokenStorage';

export type { Tokens };
export { parseTokens };

const KEY = 'pickone.tokens';

// 아래 함수들은 실제로는 기다릴 일이 없지만, 앱용 구현과 시그니처를 맞추려고 async 로 둔다
export async function loadTokens(): Promise<Tokens | null> {
  return parseTokens(window.sessionStorage.getItem(KEY));
}

export async function saveTokens(tokens: Tokens): Promise<void> {
  window.sessionStorage.setItem(KEY, JSON.stringify(tokens));
}

export async function clearTokens(): Promise<void> {
  window.sessionStorage.removeItem(KEY);
}
