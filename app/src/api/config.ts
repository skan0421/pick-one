// 서버 주소 설정.
// Spring 의 application.yml + 환경변수 주입과 같은 역할이다.
// EXPO_PUBLIC_ 으로 시작하는 환경변수만 앱 코드에서 읽을 수 있고, 빌드 시점에 값이 코드에 박힌다.
// 그래서 .env 를 바꾸면 개발 서버(npx expo start)를 다시 시작해야 한다.

const API_PREFIX = '/api/v1';

export function getApiBaseUrl(): string {
  const url = process.env.EXPO_PUBLIC_API_URL;
  if (!url) {
    throw new Error(
      'EXPO_PUBLIC_API_URL 이 설정되지 않았습니다. app/.env.example 을 app/.env 로 복사한 뒤 개발 서버를 다시 시작하세요.',
    );
  }
  // 끝에 붙은 "/" 를 떼어 "http://host:8080//api/v1" 같은 주소가 만들어지지 않게 한다
  return url.replace(/\/+$/, '') + API_PREFIX;
}
