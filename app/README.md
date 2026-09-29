# pick-one 앱 (Expo)

pick-one 백엔드에 붙는 React Native(Expo) 앱의 뼈대입니다. 같은 코드로 휴대폰 앱과 웹이 함께 동작합니다.
디자인은 다루지 않았고, 가입 → 휴대폰 인증 → 피드 조회가 실제 서버와 연결되는지 확인하는 것이 목적입니다.

## 기술 선택

| 항목 | 선택 | 이유 |
|---|---|---|
| 프레임워크 | Expo SDK 57, TypeScript | 기획(`docs/planning.md`)의 "앱 우선, 같은 코드로 웹 배포" |
| 화면 이동 | Expo Router | 파일 경로가 곧 화면 주소. 설정 파일 없이 폴더 구조만 보면 화면 구성을 알 수 있음 |
| 서버 상태 | TanStack Query | 로딩·오류·캐시를 직접 관리하지 않아도 됨 |
| UI | React Native Paper | 의존성 하나로 입력칸·버튼·카드가 갖춰져 있고 앱과 웹에서 똑같이 동작. 빌드 설정을 추가할 필요가 없음 (NativeWind, Tamagui 는 바벨·번들러 설정이 필요) |
| HTTP | 내장 `fetch` | 필요한 기능(헤더 첨부, 재발급)이 작아 라이브러리 없이 직접 작성 |

## 실행 방법

필요: Node.js 22 이상. 백엔드가 먼저 떠 있어야 합니다 ([루트 README 의 로컬 실행](../README.md#로컬-실행)).

```bash
cd app
npm install
cp .env.example .env      # 서버 주소 설정
npx expo start --web      # 웹 브라우저로 실행 (http://localhost:8081)
```

`.env` 를 고치면 개발 서버를 껐다가 다시 켜야 반영됩니다.

### 휴대폰에서 실행할 때: localhost 대신 PC 의 내부 IP

휴대폰에 [Expo Go](https://expo.dev/go) 를 설치하고 `npx expo start` 가 보여 주는 QR 코드를 찍으면 됩니다.
이때 `.env` 의 서버 주소는 `localhost` 가 아니라 **PC 의 내부 IP** 여야 합니다.

```
EXPO_PUBLIC_API_URL=http://192.168.0.12:8080
```

- `localhost` 는 "이 기기 자신"을 뜻합니다. 휴대폰에서 `localhost:8080` 을 부르면 PC 가 아니라 휴대폰 안에서 서버를 찾습니다.
- 내부 IP 확인: Windows 는 `ipconfig` 의 "IPv4 주소", macOS 는 `ipconfig getifaddr en0`
- 휴대폰과 PC 가 같은 Wi-Fi 에 있어야 합니다.
- 연결이 안 되면 Windows 방화벽이 8080 포트의 들어오는 연결을 막고 있는지 확인합니다.
- 웹과 달리 앱에는 CORS 제한이 없으므로 서버의 허용 origin 설정은 바꾸지 않아도 됩니다.

### 웹에서 실행할 때: CORS

브라우저는 서버가 허용한 주소에서 온 요청만 통과시킵니다. 백엔드의 기본 허용 주소는 `http://localhost:8081` (Expo 웹 개발 서버의 기본 포트)입니다.
다른 포트나 주소로 띄웠다면 백엔드를 `CORS_ALLOWED_ORIGINS` 환경변수와 함께 실행합니다.

### 인증번호 확인

로컬 서버는 문자를 보내지 않습니다. 인증번호는 백엔드 로그의 `[SMS 본문]` 줄에 찍힙니다.

## 검사 명령

```bash
npm run typecheck   # 타입 검사 (tsc --noEmit)
npm run lint        # 린트
npm test            # 단위 테스트 (API 클라이언트)
```

CI 는 타입 검사와 린트만 돌립니다. 단위 테스트는 로컬에서 실행합니다.

## 폴더 구조

```
app/
  .env.example              서버 주소 예시
  app.json                  앱 이름, 아이콘 등 Expo 설정
  src/
    app/                    화면. 파일 경로가 곧 주소
      _layout.tsx           최상위 틀. 로그인 상태에 따라 열 수 있는 화면을 정함
      (auth)/               로그인 전
        login.tsx             /login
        signup.tsx            /signup
      verify-phone.tsx      /verify-phone  휴대폰 인증
      (tabs)/               로그인 후. 하단 탭 4개
        index.tsx             /              피드
        post.tsx              /post          올리기
        my-questions.tsx      /my-questions  내 고민
        my.tsx                /my            마이 (로그아웃)
    api/                    서버 호출
      client.ts               공통 요청 함수. 헤더 첨부, 에러 변환, 재발급
      errors.ts               ApiError
      config.ts               서버 주소
      auth.ts, phone.ts, members.ts, questions.ts   API 별 함수와 타입
    auth/                   로그인 상태
      AuthContext.tsx         현재 상태를 모든 화면에 제공
      session.ts              메모리에 든 토큰
      tokenStorage.ts         토큰 저장 (앱)
      tokenStorage.web.ts     토큰 저장 (웹)
    __tests__/              단위 테스트
```

Java/Spring 에 빗대면 다음과 같습니다.

| 여기 | Spring 에서 비슷한 것 |
|---|---|
| `src/app/` 의 파일 경로 | `@RequestMapping` 경로 |
| `_layout.tsx` 의 `Stack.Protected` | Spring Security 의 `requestMatchers(...)` |
| `api/*.ts` 의 `type` | DTO (`record`) |
| `api/client.ts` | `RestClient` + 인터셉터 |
| `AuthContext` | `SecurityContextHolder` |
| `Promise`, `async/await` | `CompletableFuture` |
| `tokenStorage.ts` / `.web.ts` | `@Profile` 로 갈아 끼우는 구현체 |

## 화면 흐름

서버의 가입 상태(`docs/api.md` 2.1)를 그대로 따릅니다.

```
로그인 안 됨 ──가입/로그인──> PENDING_PHONE ──휴대폰 인증──> ACTIVE
(로그인·가입 화면)            (휴대폰 인증 화면)             (하단 탭)
```

화면을 직접 이동시키는 코드는 거의 없습니다. 로그인 상태가 바뀌면 최상위 레이아웃이 그 상태에서 열 수 있는 화면으로 보냅니다.

## 토큰 재발급

access 토큰(30분)이 만료되면 서버는 401 `AUTH_EXPIRED_TOKEN` 을 돌려줍니다. 클라이언트는 refresh 토큰으로 새 토큰을 받고 원래 요청을 한 번 다시 보냅니다.

**재발급은 동시에 한 번만 나갑니다.** 서버는 이미 쓴 refresh 토큰이 다시 오면 탈취로 보고 그 회원의 모든 기기를 로그아웃시킵니다(`docs/api.md` 1.3). 한 화면이 요청 3개를 동시에 보내 셋 다 401 을 받았을 때 각자 재발급하면 그 상황이 됩니다. 그래서 진행 중인 재발급이 있으면 새로 보내지 않고 그 결과를 함께 기다립니다. 재발급이 끝난 뒤 늦게 도착한 401 은 재발급 없이 새 토큰으로 재시도만 합니다.

## 토큰 저장과 보안상 트레이드오프

| 플랫폼 | 저장소 | 특징 |
|---|---|---|
| 앱 (iOS/Android) | `expo-secure-store` | iOS 키체인 / Android Keystore 로 암호화 저장 |
| 웹 | `sessionStorage` | 암호화되지 않음. 아래 참고 |

웹에는 앱의 보안 저장소에 해당하는 것이 없어 선택지마다 단점이 있습니다.

- **httpOnly 쿠키**: 자바스크립트가 읽을 수 없어 가장 안전하지만, 지금 서버는 refresh 토큰을 요청 본문으로 받으므로 쓸 수 없습니다. 쓰려면 서버가 쿠키를 발급하고 CSRF 대책을 세워야 합니다.
- **localStorage**: 탭을 닫아도 로그인이 유지되지만 XSS 에 노출되고, 탭끼리 공유되므로 두 탭이 같은 refresh 토큰으로 동시에 재발급해 전 기기 로그아웃이 될 수 있습니다.
- **sessionStorage (선택)**: 탭마다 따로 저장되고 탭을 닫으면 지워집니다.

sessionStorage 를 고른 대가와 한계는 다음과 같습니다.

- XSS 에는 여전히 노출됩니다. 페이지에서 악성 스크립트가 실행되면 토큰을 읽을 수 있습니다.
- 탭을 닫거나 새 탭을 열면 다시 로그인해야 합니다.
- 브라우저 탭 복제 시 sessionStorage 가 복사되어 두 탭이 같은 refresh 토큰을 가질 수 있습니다. 이 상태에서 두 탭이 각각 재발급하면 서버가 재사용으로 감지해 전 기기 로그아웃이 됩니다. "재발급은 한 번만" 규칙은 탭 하나 안에서만 지켜집니다.

운영 배포 전에는 웹용 refresh 토큰을 httpOnly 쿠키로 옮기는 것을 검토해야 합니다.
