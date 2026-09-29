// 토큰 저장소 — 앱(iOS/Android)용.
// expo-secure-store 는 iOS 키체인 / Android Keystore 로 암호화해서 저장한다.
//
// 같은 이름에 .web.ts 가 붙은 파일(tokenStorage.web.ts)이 있으면 웹에서는 그 파일이 대신 쓰인다.
// 번들러(Metro)가 플랫폼에 맞는 파일을 골라 주므로, 쓰는 쪽은 './tokenStorage' 만 import 하면 된다.
// (Spring 에서 @Profile 로 구현체를 갈아 끼우는 것과 비슷하다)
import * as SecureStore from 'expo-secure-store';

export type Tokens = {
  accessToken: string;
  refreshToken: string;
};

const KEY = 'pickone.tokens';

export async function loadTokens(): Promise<Tokens | null> {
  const raw = await SecureStore.getItemAsync(KEY);
  return parseTokens(raw);
}

export async function saveTokens(tokens: Tokens): Promise<void> {
  await SecureStore.setItemAsync(KEY, JSON.stringify(tokens));
}

export async function clearTokens(): Promise<void> {
  await SecureStore.deleteItemAsync(KEY);
}

// 저장된 값이 깨져 있으면 없는 것으로 취급한다 (다시 로그인하면 된다)
export function parseTokens(raw: string | null): Tokens | null {
  if (!raw) {
    return null;
  }
  try {
    const value = JSON.parse(raw);
    if (typeof value?.accessToken === 'string' && typeof value?.refreshToken === 'string') {
      return { accessToken: value.accessToken, refreshToken: value.refreshToken };
    }
  } catch {
    // JSON 이 아니면 아래에서 null 반환
  }
  return null;
}
