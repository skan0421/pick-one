// 토큰 저장소 — 앱(iOS/Android)용.
// expo-secure-store 는 iOS 키체인 / Android Keystore 로 암호화해서 저장한다.
//
// 같은 이름에 .web.ts 가 붙은 파일(tokenStorage.web.ts)이 있으면 웹에서는 그 파일이 대신 쓰인다.
// 번들러(Metro)가 플랫폼에 맞는 파일을 골라 주므로, 쓰는 쪽은 './tokenStorage' 만 import 하면 된다.
// (Spring 에서 @Profile 로 구현체를 갈아 끼우는 것과 비슷하다)
// 두 파일은 같은 함수를 같은 시그니처로 내보내야 한다
import * as SecureStore from 'expo-secure-store';

import { parseTokens, TOKENS_KEY, type Tokens } from './tokens';

export async function loadTokens(): Promise<Tokens | null> {
  const raw = await SecureStore.getItemAsync(TOKENS_KEY);
  return parseTokens(raw);
}

export async function saveTokens(tokens: Tokens): Promise<void> {
  await SecureStore.setItemAsync(TOKENS_KEY, JSON.stringify(tokens));
}

export async function clearTokens(): Promise<void> {
  await SecureStore.deleteItemAsync(TOKENS_KEY);
}
