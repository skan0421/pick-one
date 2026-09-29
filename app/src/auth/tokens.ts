// 토큰 저장소(앱용·웹용)가 함께 쓰는 타입과 함수.
//
// 주의: 이 내용을 tokenStorage.ts 에 두고 tokenStorage.web.ts 에서 './tokenStorage' 로 가져오면 안 된다.
// 웹에서는 './tokenStorage' 가 tokenStorage.web.ts 자신으로 해석되어 자기 자신을 끝없이 불러온다.
// 플랫폼별 파일이 함께 쓰는 코드는 이렇게 플랫폼 접미사가 없는 다른 이름의 파일에 둔다

export type Tokens = {
  accessToken: string;
  refreshToken: string;
};

export const TOKENS_KEY = 'pickone.tokens';

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
