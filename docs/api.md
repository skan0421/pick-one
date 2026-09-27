# API 명세 (MVP v1)

기획은 [planning.md](./planning.md), 스키마는 [erd.md](./erd.md) 와 `V1__init_schema.sql` 을 기준으로 한다.
1차 기능은 구현 대상이고, 2차 기능은 설계만 정리한다. 스키마 변경은 마지막 "DB 마이그레이션 계획" 절에 V2 이후 버전으로 정리하며 V1 은 수정하지 않는다.

---

## 1. 공통 규칙

### 1.1 기본
- Base URL: `/api/v1`
- 리소스는 REST 명사 복수형 (`/questions`, `/members`, `/points`)
- 요청/응답은 JSON (`Content-Type: application/json`)
- 시각은 ISO-8601, KST 오프셋 포함 (`2026-09-27T17:30:00+09:00`)
- ID 는 숫자(BIGINT), 응답에서는 JSON number 로 내려준다

### 1.2 인증
- `Authorization: Bearer <accessToken>` 헤더
- 인증 수준 표기
  | 표기 | 의미 |
  |---|---|
  | 공개 | 토큰 없이 호출 가능 |
  | 로그인 | 유효한 access 토큰 필요. `PENDING_PHONE` 회원도 호출 가능 |
  | ACTIVE | 로그인 + 휴대폰 인증을 마친 `ACTIVE` 회원만. `PENDING_PHONE` 이면 403 `SIGNUP_INCOMPLETE` |

### 1.3 토큰 정책
| 항목 | 값 |
|---|---|
| Access 토큰 | JWT, 30분. 클레임: `sub`(memberId), `signupStatus`, `exp` |
| Refresh 토큰 | JWT, 14일. 클레임: `sub`, `jti`(UUID), `exp`. Redis 에 저장 |
| 저장 (유효) | `refresh:{memberId}:{jti}` → 발급 시각/기기 정보, TTL 14일 |
| 저장 (사용됨) | `refresh:used:{jti}` → memberId, TTL 은 **그 토큰의 원래 만료 시각까지** |

**Rotation**: `POST /auth/refresh` 가 성공하면 새 access + 새 refresh 를 발급하고, 옛 refresh 의 키를 `refresh:{memberId}:{jti}` 에서 삭제한 뒤 `refresh:used:{jti}` 로 옮긴다.

**재사용 감지**: refresh 요청이 들어오면 아래 순서로 판별한다.
1. `refresh:used:{jti}` 가 존재 → 이미 교체된 토큰이 다시 들어온 것이므로 **탈취로 간주**. 해당 memberId 의 `refresh:{memberId}:*` 를 전부 삭제(모든 기기 로그아웃)하고 401 `AUTH_REFRESH_REUSED`
2. `refresh:{memberId}:{jti}` 가 존재 → 정상. rotation 수행
3. 어느 키에도 없음(만료·로그아웃·위조) → 401 `AUTH_INVALID_TOKEN`

**로그아웃**: `POST /auth/logout` 은 요청의 refresh 를 `refresh:{memberId}:{jti}` 에서 삭제한다. (`used` 로 옮기지 않으므로 이후 재사용은 `AUTH_INVALID_TOKEN`)

### 1.4 에러 응답
모든 에러는 같은 형식이다.
```json
{ "code": "VOTE_ALREADY_VOTED", "message": "이미 투표한 고민입니다." }
```
`VALIDATION_ERROR` 는 필드 정보를 추가한다.
```json
{
  "code": "VALIDATION_ERROR",
  "message": "입력값이 올바르지 않습니다.",
  "errors": [ { "field": "options[0].content", "reason": "20자 이하여야 합니다." } ]
}
```

#### 에러 코드 목록
| 코드 | HTTP | 설명 |
|---|---|---|
| `VALIDATION_ERROR` | 400 | 요청 본문/파라미터 검증 실패 |
| `AUTH_INVALID_CREDENTIALS` | 401 | 이메일 또는 비밀번호 불일치 |
| `AUTH_INVALID_TOKEN` | 401 | 토큰 서명 오류, 형식 오류, 존재하지 않는 refresh |
| `AUTH_EXPIRED_TOKEN` | 401 | access 토큰 만료 (클라이언트는 refresh 시도) |
| `AUTH_REFRESH_REUSED` | 401 | 이미 사용된 refresh 재사용 감지. 전체 세션 폐기됨, 재로그인 필요 |
| `AUTH_CODE_INVALID` | 401 | 소셜 로그인 일회용 code 가 없거나 만료 |
| `SIGNUP_INCOMPLETE` | 403 | 휴대폰 인증 전(`PENDING_PHONE`) 회원이 ACTIVE 전용 API 호출 |
| `FORBIDDEN` | 403 | 타인의 리소스 접근 (고민 삭제, 결과 조회 등) |
| `MEMBER_EMAIL_DUPLICATE` | 409 | 이미 가입된 이메일 |
| `MEMBER_NICKNAME_DUPLICATE` | 409 | 이미 사용 중인 닉네임 |
| `MEMBER_NOT_FOUND` | 404 | 회원 없음 |
| `MEMBER_SUSPENDED` | 403 | 정지된 회원 |
| `PHONE_ALREADY_REGISTERED` | 409 | 다른 회원이 이미 인증한 휴대폰 번호 |
| `PHONE_ALREADY_VERIFIED` | 409 | 이미 인증을 마친 회원이 다시 인증 요청 |
| `OTP_INVALID` | 400 | 인증번호 불일치 (남은 시도 횟수 포함) |
| `OTP_EXPIRED` | 400 | 인증번호 만료 또는 발송 이력 없음 |
| `OTP_ATTEMPT_EXCEEDED` | 429 | 인증번호 확인 5회 실패, 재발송 필요 |
| `OTP_COOLDOWN` | 429 | 같은 번호 재발송 1분 쿨다운 |
| `OTP_DAILY_LIMIT` | 429 | 같은 번호 일일 발송 한도 초과 |
| `QUESTION_NOT_FOUND` | 404 | 고민 없음, 삭제됨, 또는 HIDDEN |
| `QUESTION_OPTION_COUNT_INVALID` | 400 | TEXT 2~4개 / IMAGE 2개 규칙 위반 |
| `QUESTION_OPTION_TYPE_MISMATCH` | 400 | TEXT 인데 image_url, IMAGE 인데 content 가 옴 |
| `QUESTION_CLOSED` | 409 | 종료된 고민에 투표/boost |
| `VOTE_ALREADY_VOTED` | 409 | 같은 고민에 두 번 투표 (`uk_vote_member_id_question_id`) |
| `VOTE_OPTION_MISMATCH` | 400 | 선택지가 해당 고민의 것이 아님 (`fk_vote_option` 복합 FK) |
| `VOTE_OWN_QUESTION` | 403 | 자기 고민에 투표 |
| `RESULT_NOT_ALLOWED` | 403 | 투표하지 않았고 작성자도 아닌 사람이 결과 조회 |
| `POINT_INSUFFICIENT` | 409 | 잔액 부족 |
| `POINT_WALLET_NOT_FOUND` | 409 | 지갑 없음 (ACTIVE 전환 전) |
| `IDEMPOTENCY_KEY_REQUIRED` | 400 | 포인트 사용 API 에 헤더 누락 |
| `IDEMPOTENCY_KEY_CONFLICT` | 409 | 같은 키로 다른 요청 본문 |
| `BLOCK_SELF` | 400 | 자기 자신 차단 (`chk_member_block_not_self`) |
| `BLOCK_ALREADY_EXISTS` | 409 | 이미 차단한 사용자 |
| `REPORT_DUPLICATE` | 409 | 같은 고민 중복 신고 (`uk_report_reporter_id_question_id`) |
| `REPORT_OWN_QUESTION` | 400 | 자기 고민 신고 |
| `CONTACTS_TOO_MANY` | 400 | 연락처 업로드 상한 초과 |
| `RATE_LIMITED` | 429 | 일반 요청 제한 |
| `INTERNAL_ERROR` | 500 | 서버 오류 |

### 1.5 커서 기반 페이징
목록 API 는 offset 대신 커서를 쓴다. 새 글이 계속 끼어드는 피드에서 중복/누락을 막기 위해서다.

- 요청: `?cursor=<opaque>&size=20` (size 기본 20, 최대 50). 첫 페이지는 cursor 생략
- 응답:
  ```json
  { "items": [ ... ], "nextCursor": "eyJiIjowLCJ0IjoiMjAyNi0wOS0yN1QxNzowMDowMCIsImlkIjo0Mn0", "hasNext": true }
  ```
- 커서는 `(정렬키, id)` 를 JSON → base64url 로 감싼 불투명 문자열이다. 클라이언트는 해석하지 않고 그대로 돌려준다. 정렬키는 API 마다 다르다 (피드: boosted 여부 + created_at, 내역: created_at)
- `hasNext=false` 면 `nextCursor` 는 null

### 1.6 Idempotency-Key (포인트 사용 API)
포인트를 **차감**하는 API(`POST /questions/{id}/boosts`)는 `Idempotency-Key: <UUID>` 헤더가 필수다. 네트워크 재시도로 포인트가 두 번 빠지는 것을 막는다.

- 키는 `point_ledger.idempotency_key`(유니크) 에 그대로 저장된다
- 같은 키 + 같은 대상으로 재요청 → 최초 처리 결과를 그대로 200 으로 반환 (차감 없음)
- 같은 키 + 다른 대상(다른 questionId) → 409 `IDEMPOTENCY_KEY_CONFLICT`
- 헤더 없음 → 400 `IDEMPOTENCY_KEY_REQUIRED`
- 적립(투표 보상)은 서버가 `vote:{voteId}` 형태로 키를 만들어 같은 컬럼에 저장한다. 클라이언트 헤더는 필요 없다

### 1.7 포인트 정책 (설정값)
아래 수치는 `application.yml` 의 `pickone.point.*` 설정값이며 코드에 하드코딩하지 않는다.
| 항목 | 기본값 | 설명 |
|---|---|---|
| 투표 적립 | +1P | `tx_type = VOTE_REWARD` |
| 투표 적립 일일 상한 | 50P | 회원별, KST 자정 초기화. 초과 시 투표는 성공하되 적립 없음 |
| 상단 노출 비용 | 100P | `tx_type = BOOST_USE`, 24시간 |
| 가입 보너스 | 없음 | 소셜 계정 무한 생성으로 인한 어뷰징 방지 |

- 일일 적립 합계는 Redis 카운터 `point:daily:{memberId}:{yyyyMMdd}` (TTL 자정) 로 빠르게 확인하고, 카운터가 유실되면 `point_ledger` 에서 `SUM(amount) WHERE tx_type='VOTE_REWARD' AND created_at >= 오늘` 로 재계산할 수 있다
- `point_wallet` 행은 회원이 `ACTIVE` 로 전환되는 시점(휴대폰 인증 확인 트랜잭션 안)에 `balance = 0` 으로 생성된다. `PENDING_PHONE` 회원은 지갑이 없다
- 불변식: `SUM(point_ledger.amount) = point_wallet.balance`, `point_wallet.balance >= 0` (`chk_point_wallet_balance`)

### 1.8 입력 검증 규칙 요약
| 항목 | 규칙 |
|---|---|
| 이메일 | 형식 검증, 100자 |
| 비밀번호 | 8~64자, 영문+숫자 포함 |
| 닉네임 | 2~30자, 한글/영문/숫자 |
| 고민 본문 | 1~300자 |
| 텍스트 선택지 | 1~20자, 2~4개 |
| 사진 선택지 | `image_url` 500자, 정확히 2개 |
| 휴대폰 번호 | 한국 번호, 서버에서 E.164(`+8210...`) 로 정규화 |

---

## 2. 인증·회원

### 2.1 가입 상태와 흐름
회원은 가입 경로와 무관하게 아래 상태를 거친다.

```
가입(이메일 or 소셜) ──> PENDING_PHONE ──휴대폰 인증 확인──> ACTIVE ──(운영 제재)──> SUSPENDED
```

- `PENDING_PHONE`: 토큰은 발급되지만 인증·내 정보·휴대폰 인증 API 만 쓸 수 있다. 나머지는 403 `SIGNUP_INCOMPLETE`
- `ACTIVE` 전환 시 한 트랜잭션에서: `member.signup_status=ACTIVE`, 휴대폰 컬럼 저장, `point_wallet` 생성(0P), `hide_pending` 매칭 (7절)
- 상태는 V2 의 `member.signup_status` 컬럼에 저장한다. 기존 `member.status`(ACTIVE/SUSPENDED) 는 제재 용도로 그대로 둔다

### 2.2 POST /auth/signup — 이메일 가입
- 인증: 공개
- 요청
  ```json
  { "email": "user@example.com", "password": "pass1234", "nickname": "고민많은사람" }
  ```
- 응답 `201`
  ```json
  {
    "member": { "id": 1, "nickname": "고민많은사람", "signupStatus": "PENDING_PHONE" },
    "accessToken": "eyJ...",
    "refreshToken": "eyJ..."
  }
  ```
- 처리: `password_hash = BCrypt`, `signup_status = PENDING_PHONE`, 휴대폰 컬럼은 NULL
- 주요 에러: `VALIDATION_ERROR`, `MEMBER_EMAIL_DUPLICATE`, `MEMBER_NICKNAME_DUPLICATE`

### 2.3 POST /auth/login — 이메일 로그인
- 인증: 공개
- 요청
  ```json
  { "email": "user@example.com", "password": "pass1234" }
  ```
- 응답 `200`
  ```json
  {
    "member": { "id": 1, "nickname": "고민많은사람", "signupStatus": "ACTIVE" },
    "accessToken": "eyJ...",
    "refreshToken": "eyJ..."
  }
  ```
- 처리: `email IS NOT NULL AND password_hash IS NOT NULL` 인 회원만 대상. 소셜 전용 회원(password_hash NULL)은 이메일이 같아도 `AUTH_INVALID_CREDENTIALS`
- 주요 에러: `AUTH_INVALID_CREDENTIALS`(이메일 없음과 비밀번호 틀림을 구분하지 않음), `MEMBER_SUSPENDED`

### 2.4 POST /auth/refresh — 토큰 재발급
- 인증: 공개 (본문의 refresh 로 인증)
- 요청
  ```json
  { "refreshToken": "eyJ..." }
  ```
- 응답 `200`
  ```json
  { "accessToken": "eyJ...", "refreshToken": "eyJ..." }
  ```
- 처리: 1.3 의 판별 순서. 성공 시 rotation, 옛 jti 는 `refresh:used:{jti}` 로 이동
- 주요 에러: `AUTH_REFRESH_REUSED`(전체 세션 폐기), `AUTH_INVALID_TOKEN`, `MEMBER_SUSPENDED`

### 2.5 POST /auth/logout — 로그아웃
- 인증: 로그인
- 요청
  ```json
  { "refreshToken": "eyJ..." }
  ```
- 응답 `204`
- 처리: `refresh:{memberId}:{jti}` 삭제. access 는 만료까지 유효하므로 클라이언트가 폐기한다 (30분 수명이라 블랙리스트는 두지 않음)
- 주요 에러: `AUTH_INVALID_TOKEN`

### 2.6 소셜 로그인 (카카오, 구글)

Spring Security OAuth2 Client 의 Authorization Code 흐름을 그대로 쓴다. 아래 두 경로는 Spring Security 가 제공하는 것이라 `/api/v1` 접두사가 없다.

```
[앱/웹]                         [서버]                                   [카카오/구글]
  │ 1. GET /oauth2/authorization/kakao
  │──────────────────────────────>│ 2. state 생성, 제공자 인가 페이지로 302
  │<──────────────────────────────│
  │ 3. 사용자 동의 ───────────────────────────────────────────────────────>│
  │<─────────────────────────── 4. /login/oauth2/code/kakao?code=..&state=.. ───│
  │──────────────────────────────>│ 5. code → 제공자 access 토큰 교환 ────>│
  │                               │ 6. 사용자 정보 조회 <──────────────────│
  │                               │ 7. OAuth2UserService:
  │                               │    member_social_account(provider, provider_user_id) 조회
  │                               │    없으면 member(PENDING_PHONE) + social_account 생성
  │                               │ 8. SuccessHandler: 일회용 code 발급 (Redis, TTL 60초)
  │<── 302 {FRONT_URL}/oauth/callback?code=<일회용 code> ──│
  │ 9. POST /api/v1/auth/token { code }
  │──────────────────────────────>│ 10. code 검증·삭제 후 access + refresh 발급
  │<──────────────────────────────│
```

**설계 결정**
- JWT 를 redirect URL 에 직접 싣지 않고 일회용 code 로 교환한다. URL 은 브라우저 히스토리·서버 로그·리퍼러에 남기 때문이다. code 는 `oauth:code:{code}` → memberId 로 Redis 에 60초 저장하고 교환 즉시 삭제한다
- `provider_user_id` 는 카카오 `id`, 구글 `sub` 를 문자열로 저장한다. 제공자가 준 이메일은 `member_social_account.email` 에 참고용으로 두고, `member.email` 에는 이메일 가입과 충돌하지 않을 때만 복사한다
- **이메일 중복 정책**: 소셜 로그인의 이메일이 이미 이메일 가입 회원의 것이면 자동으로 묶지 않고 별도 회원을 만들지도 않는다. 이 경우 `{FRONT_URL}/oauth/callback?error=EMAIL_ALREADY_REGISTERED` 로 보낸다. 제공자마다 이메일 검증 수준이 달라 자동 연동은 계정 탈취 경로가 될 수 있다. 계정 연동은 이후 기능으로 남긴다
- **카카오는 이메일을 제공하지 않을 수 있다.** 그래서 V2 에서 `member.email` 을 NULL 허용으로 바꾼다 (유니크는 유지, MariaDB 는 NULL 을 여러 개 허용)
- **닉네임 자동 생성**: 소셜 가입 시 서버가 `형용사 + 동물 + 숫자` 형태로 만든다 (예: `용감한수달3921`). `uk_member_nickname` 충돌 시 숫자를 바꿔 재시도한다. 사용자는 이후 `PATCH /members/me` 로 바꿀 수 있다
- `password_hash` 는 NULL. 소셜 회원은 이메일 로그인이 불가능하다
- 네이버 확장: `provider` enum 에 `NAVER` 추가 + `spring.security.oauth2.client.registration.naver` 설정 + 사용자 정보 파싱(`response.id`) 추가면 된다. API 경로와 테이블은 그대로다
- `state` 파라미터로 CSRF 를 막는 것은 Spring Security 기본 동작이다

#### POST /auth/token — 일회용 code 를 토큰으로 교환
- 인증: 공개
- 요청
  ```json
  { "code": "b1f7c2e0-...-9a" }
  ```
- 응답 `200`
  ```json
  {
    "member": { "id": 7, "nickname": "용감한수달3921", "signupStatus": "PENDING_PHONE", "provider": "KAKAO" },
    "accessToken": "eyJ...",
    "refreshToken": "eyJ..."
  }
  ```
- 주요 에러: `AUTH_CODE_INVALID`, `MEMBER_SUSPENDED`

### 2.7 GET /members/me — 내 정보
- 인증: 로그인
- 응답 `200`
  ```json
  {
    "id": 7,
    "email": null,
    "nickname": "용감한수달3921",
    "provider": "KAKAO",
    "signupStatus": "ACTIVE",
    "phoneVerified": true,
    "hideFromContacts": false,
    "pointBalance": 12,
    "createdAt": "2026-09-27T17:00:00+09:00"
  }
  ```
- `provider` 는 이메일 가입이면 `"EMAIL"`. `pointBalance` 는 지갑이 없으면 0. 휴대폰 번호는 내려주지 않는다

### 2.8 PATCH /members/me — 내 정보 수정
- 인증: 로그인
- 요청 (바꿀 필드만)
  ```json
  { "nickname": "새닉네임" }
  ```
- 응답 `200`: 2.7 과 같은 형식
- 주요 에러: `VALIDATION_ERROR`, `MEMBER_NICKNAME_DUPLICATE`

---

## 3. 휴대폰 인증 (SMS OTP)

### 3.1 보안 규칙
| 규칙 | 값 | 구현 |
|---|---|---|
| 인증번호 | 숫자 6자리, `SecureRandom` | 응답에 절대 포함하지 않음 |
| 유효 시간 | 3분 | Redis TTL 180초 |
| 확인 시도 | 5회 | 초과 시 코드 폐기, 재발송 필요 |
| 재발송 쿨다운 | 같은 번호 1분 | Redis 키 TTL 60초 |
| 일일 발송 한도 | 같은 번호 5회, 같은 회원 10회 | Redis 카운터, TTL 자정 |
| 저장 방식 | 코드 원문 저장 안 함 | `SHA-256(code + salt)`, salt 는 요청마다 생성해 함께 저장 |
| 번호 처리 | E.164 정규화 후 HMAC | Redis 키에도 원본 번호 대신 `phone_hmac` 사용 |

Redis 키 설계
| 키 | 값 | TTL |
|---|---|---|
| `otp:{phoneHmac}` | `{ hash, salt, attempts, memberId }` (hash) | 180초 |
| `otp:cooldown:{phoneHmac}` | 1 | 60초 |
| `otp:daily:phone:{phoneHmac}:{yyyyMMdd}` | 발송 횟수 | 자정까지 |
| `otp:daily:member:{memberId}:{yyyyMMdd}` | 발송 횟수 | 자정까지 |

### 3.2 SmsSender 분리
```
interface SmsSender { void send(String phoneE164, String message); }
```
| 구현 | 프로필 | 동작 |
|---|---|---|
| `LoggingSmsSender` | local, test | 실제 발송 없이 로그로 출력. 로그에는 번호를 마스킹(`+8210****5678`)하고 **코드는 DEBUG 레벨에만** 남긴다 |
| 실제 발송 구현 | prod | 이후 문자 발송 업체 SDK 로 구현. API 키는 환경변수 |

테스트에서는 `LoggingSmsSender` 를 감싼 스파이로 발송된 코드를 가로채 확인 API 를 호출한다.

### 3.3 POST /phone-verifications — 인증번호 발송
- 인증: 로그인 (`PENDING_PHONE` 회원이 주로 호출)
- 요청
  ```json
  { "phone": "010-1234-5678" }
  ```
- 응답 `202`
  ```json
  { "expiresInSeconds": 180, "cooldownSeconds": 60 }
  ```
- 처리 순서: 정규화 → 이미 ACTIVE 면 `PHONE_ALREADY_VERIFIED` → 다른 회원의 `phone_hmac` 이면 `PHONE_ALREADY_REGISTERED` → 쿨다운·일일 한도 확인 → 코드 생성·해시 저장 → `SmsSender.send`
- 주요 에러: `VALIDATION_ERROR`, `PHONE_ALREADY_VERIFIED`, `PHONE_ALREADY_REGISTERED`, `OTP_COOLDOWN`, `OTP_DAILY_LIMIT`

### 3.4 POST /phone-verifications/confirm — 인증번호 확인
- 인증: 로그인
- 요청
  ```json
  { "phone": "010-1234-5678", "code": "483920" }
  ```
- 응답 `200`
  ```json
  {
    "member": { "id": 7, "signupStatus": "ACTIVE" },
    "accessToken": "eyJ...",
    "refreshToken": "eyJ..."
  }
  ```
  access 토큰에 `signupStatus` 클레임이 들어 있으므로 상태 변경 후 토큰을 다시 발급한다.
- 처리 (한 트랜잭션):
  1. `otp:{phoneHmac}` 조회. 없으면 `OTP_EXPIRED`
  2. `attempts >= 5` 면 `OTP_ATTEMPT_EXCEEDED`, 아니면 해시 비교. 불일치 시 attempts+1 후 `OTP_INVALID`(응답에 `remainingAttempts`)
  3. `member.phone_encrypted = AES-GCM(phone)`, `member.phone_hmac`, `signup_status = ACTIVE`
  4. `point_wallet` 생성 (balance 0)
  5. `hide_pending` 에서 `phone_hmac` 이 같은 행을 찾아 `hide_relation(owner_id, target_member_id = 나)` 로 옮기고 삭제
  6. Redis 키 삭제, 새 토큰 발급
- 주요 에러: `OTP_INVALID`, `OTP_EXPIRED`, `OTP_ATTEMPT_EXCEEDED`, `PHONE_ALREADY_REGISTERED`(경합 시 `uk_member_phone_hmac` 위반), `PHONE_ALREADY_VERIFIED`

---

## 4. 고민

### 4.1 POST /questions — 고민 등록
- 인증: ACTIVE
- 요청 (텍스트형)
  ```json
  {
    "questionType": "TEXT",
    "content": "소개팅 첫 만남, 카페 vs 밥집?",
    "options": [ { "content": "카페" }, { "content": "밥집" } ]
  }
  ```
- 요청 (사진형)
  ```json
  {
    "questionType": "IMAGE",
    "content": "내일 면접 뭐 입지",
    "options": [ { "imageUrl": "https://cdn.example.com/a.jpg" }, { "imageUrl": "https://cdn.example.com/b.jpg" } ]
  }
  ```
- 응답 `201`
  ```json
  {
    "id": 42,
    "questionType": "TEXT",
    "content": "소개팅 첫 만남, 카페 vs 밥집?",
    "status": "ACTIVE",
    "boostedUntil": null,
    "options": [ { "id": 101, "sortOrder": 1, "content": "카페" }, { "id": 102, "sortOrder": 2, "content": "밥집" } ],
    "createdAt": "2026-09-27T17:30:00+09:00"
  }
  ```
- 처리: 선택지 개수·유형 규칙은 API 에서 검증 (erd.md 설계 메모). `sort_order` 는 요청 순서대로 1부터 부여
- 이미지 업로드 자체는 1차 범위 밖이다. 클라이언트가 접근 가능한 URL 을 넘긴다고 가정하고, 이후 presigned URL 발급 API 를 추가한다
- 주요 에러: `VALIDATION_ERROR`, `QUESTION_OPTION_COUNT_INVALID`, `QUESTION_OPTION_TYPE_MISMATCH`, `SIGNUP_INCOMPLETE`

### 4.2 GET /questions/feed — 투표 피드
- 인증: ACTIVE
- 요청: `?cursor=&size=20`
- 응답 `200`
  ```json
  {
    "items": [
      {
        "id": 42,
        "questionType": "TEXT",
        "content": "소개팅 첫 만남, 카페 vs 밥집?",
        "boosted": true,
        "options": [ { "id": 101, "sortOrder": 1, "content": "카페" }, { "id": 102, "sortOrder": 2, "content": "밥집" } ],
        "author": { "nickname": "고민많은사람" },
        "createdAt": "2026-09-27T17:30:00+09:00"
      }
    ],
    "nextCursor": "eyJ...",
    "hasNext": true
  }
  ```
  피드에는 결과(득표수)를 넣지 않는다. 투표 전 결과를 보면 편향되기 때문이며, 투표 응답에서 즉시 돌려준다.

- **정렬**: `boosted_until > NOW()` 인 고민 우선, 그 안에서 `created_at DESC, id DESC`. 커서는 `(boosted 여부, created_at, id)`

- **필터링**: 아래 다섯 조건을 모두 만족하는 고민만 내려준다.
  | # | 제외 대상 | 근거 테이블 |
  |---|---|---|
  | 1 | 내가 쓴 고민 | `question.member_id = 나` |
  | 2 | 이미 투표한 고민 | `vote(member_id = 나, question_id)` 존재 (`uk_vote_member_id_question_id` 로 인덱스 탐색) |
  | 3 | 내가 차단했거나 나를 차단한 작성자의 고민 | `member_block` 양방향 |
  | 4 | 지인 숨김: 작성자가 나를 숨김 대상으로 등록 | `hide_relation(owner_id = 작성자, target_member_id = 나)` 이고 작성자의 `hide_from_contacts = true` |
  | 5 | 노출 불가 상태 | `status = 'ACTIVE' AND deleted_at IS NULL` |

  SQL 스케치 (`:me` 는 내 memberId)
  ```sql
  SELECT q.*
  FROM question q
  JOIN member a ON a.id = q.member_id
  WHERE q.status = 'ACTIVE' AND q.deleted_at IS NULL
    AND q.member_id <> :me
    AND NOT EXISTS (SELECT 1 FROM vote v
                    WHERE v.member_id = :me AND v.question_id = q.id)
    AND NOT EXISTS (SELECT 1 FROM member_block b
                    WHERE (b.blocker_id = :me AND b.blocked_id = q.member_id)
                       OR (b.blocker_id = q.member_id AND b.blocked_id = :me))
    AND NOT (a.hide_from_contacts = b'1' AND EXISTS (
             SELECT 1 FROM hide_relation h
             WHERE h.owner_id = q.member_id AND h.target_member_id = :me))
    -- 커서 조건
    AND ( (q.boosted_until > NOW()) < :cursorBoosted            -- boosted 그룹이 끝났으면 일반 그룹
       OR ((q.boosted_until > NOW()) = :cursorBoosted
           AND (q.created_at, q.id) < (:cursorCreatedAt, :cursorId)) )
  ORDER BY (q.boosted_until > NOW()) DESC, q.created_at DESC, q.id DESC
  LIMIT :size + 1;
  ```
  `LIMIT size + 1` 로 한 건 더 읽어 `hasNext` 를 판단한다. 조건 4 의 `hide_relation` 은 `idx_hide_relation_target_member_id` 를 탄다. 성능 개선 단계에서 Redis 로 "내가 투표한 question_id 집합" 을 캐싱하는 방안을 검토한다.

### 4.3 GET /questions/{id} — 고민 상세
- 인증: ACTIVE
- 응답 `200`
  ```json
  {
    "id": 42,
    "questionType": "TEXT",
    "content": "소개팅 첫 만남, 카페 vs 밥집?",
    "status": "ACTIVE",
    "boosted": false,
    "options": [ { "id": 101, "sortOrder": 1, "content": "카페" }, { "id": 102, "sortOrder": 2, "content": "밥집" } ],
    "author": { "nickname": "고민많은사람" },
    "isMine": false,
    "myVote": { "optionId": 101 },
    "result": { "totalVotes": 38, "options": [ { "optionId": 101, "count": 25, "percent": 65.8 }, { "optionId": 102, "count": 13, "percent": 34.2 } ] },
    "createdAt": "2026-09-27T17:30:00+09:00"
  }
  ```
- `myVote` 와 `result` 는 내가 투표했거나 내 고민일 때만 채워지고, 아니면 null
- 주요 에러: `QUESTION_NOT_FOUND`(삭제·HIDDEN 포함. 차단 관계면 역시 404 로 숨김)

### 4.4 GET /members/me/questions — 내 고민 목록
- 인증: ACTIVE
- 요청: `?cursor=&size=20` (정렬 `created_at DESC`, `idx_question_member_id_created_at`)
- 응답 `200`
  ```json
  {
    "items": [
      {
        "id": 42,
        "questionType": "TEXT",
        "content": "소개팅 첫 만남, 카페 vs 밥집?",
        "status": "ACTIVE",
        "boostedUntil": "2026-09-28T17:30:00+09:00",
        "totalVotes": 38,
        "options": [ { "id": 101, "content": "카페", "count": 25, "percent": 65.8 }, { "id": 102, "content": "밥집", "count": 13, "percent": 34.2 } ],
        "createdAt": "2026-09-27T17:30:00+09:00"
      }
    ],
    "nextCursor": null,
    "hasNext": false
  }
  ```
- 삭제된 고민은 제외, HIDDEN(신고 누적) 은 상태와 함께 보여준다

### 4.5 DELETE /questions/{id} — 고민 삭제
- 인증: ACTIVE, 작성자만
- 응답 `204`
- 처리: `deleted_at = NOW()` 소프트 삭제. 투표·원장은 남긴다 (포인트 불변식 유지). boost 잔여 시간은 환불하지 않는다
- 주요 에러: `QUESTION_NOT_FOUND`, `FORBIDDEN`

---

## 5. 투표

### 5.1 POST /questions/{id}/votes — 투표하기
- 인증: ACTIVE
- 요청
  ```json
  { "optionId": 101 }
  ```
- 응답 `201`
  ```json
  {
    "voteId": 9001,
    "result": {
      "totalVotes": 39,
      "myOptionId": 101,
      "options": [ { "optionId": 101, "count": 26, "percent": 66.7 }, { "optionId": 102, "count": 13, "percent": 33.3 } ]
    },
    "pointReward": { "earned": true, "amount": 1, "reason": null }
  }
  ```
  일일 상한에 걸리면 투표는 성공하고 `pointReward` 만 달라진다.
  ```json
  "pointReward": { "earned": false, "amount": 0, "reason": "DAILY_LIMIT_REACHED" }
  ```

- **트랜잭션 흐름** (하나라도 실패하면 전체 롤백)
  1. 고민 조회: 없음·삭제·HIDDEN → `QUESTION_NOT_FOUND`, CLOSED → `QUESTION_CLOSED`, 내 고민 → `VOTE_OWN_QUESTION`
  2. `INSERT vote(question_id, option_id, member_id)`
     - `uk_vote_member_id_question_id` 위반 → `VOTE_ALREADY_VOTED`
     - `fk_vote_option` (option_id, question_id) 위반 → `VOTE_OPTION_MISMATCH`. 애플리케이션에서도 먼저 검사하지만 DB 가 최종 방어선이다
  3. 일일 적립 상한 확인: `point:daily:{memberId}:{yyyyMMdd}` 가 상한 이상이면 4~5 건너뜀 (`earned=false`)
  4. `point_wallet` 을 `version` 낙관적 락으로 읽어 `balance + 1`. 충돌(`OptimisticLockException`) 시 최대 3회 재시도
  5. `INSERT point_ledger(amount=1, balance_after, tx_type='VOTE_REWARD', ref_type='VOTE', ref_id=voteId, idempotency_key='vote:'+voteId)`
  6. 커밋 후 Redis 일일 카운터 +1 (커밋 이후에 하므로 롤백 시 증가하지 않음)
  7. 결과 집계 `SELECT option_id, COUNT(*) FROM vote WHERE question_id = ? GROUP BY option_id` (`idx_vote_question_id_option_id`)

  투표와 적립을 한 트랜잭션에 묶는 이유: 투표는 됐는데 포인트가 안 들어오거나, 포인트만 들어오고 투표가 안 되는 상태를 만들지 않기 위해서다. 원장의 `idempotency_key='vote:{voteId}'` 유니크가 같은 투표에 두 번 적립되는 것을 막는다.

- 주요 에러: `VOTE_ALREADY_VOTED`, `VOTE_OPTION_MISMATCH`, `VOTE_OWN_QUESTION`, `QUESTION_NOT_FOUND`, `QUESTION_CLOSED`, `POINT_WALLET_NOT_FOUND`

### 5.2 GET /questions/{id}/results — 결과 조회
- 인증: ACTIVE. 그 고민에 투표한 사람 또는 작성자만
- 응답 `200`
  ```json
  {
    "questionId": 42,
    "totalVotes": 39,
    "myOptionId": 101,
    "options": [
      { "optionId": 101, "sortOrder": 1, "content": "카페", "count": 26, "percent": 66.7 },
      { "optionId": 102, "sortOrder": 2, "content": "밥집", "count": 13, "percent": 33.3 }
    ]
  }
  ```
- `percent` 는 소수점 1자리. 반올림 합이 100 이 아니면 가장 큰 항목에서 보정한다. 작성자는 `myOptionId = null`
- 주요 에러: `QUESTION_NOT_FOUND`, `RESULT_NOT_ALLOWED`

---

## 6. 포인트

### 6.1 GET /points/balance — 잔액
- 인증: ACTIVE
- 응답 `200`
  ```json
  { "balance": 12, "todayEarned": 12, "dailyEarnLimit": 50 }
  ```

### 6.2 GET /points/ledger — 내역
- 인증: ACTIVE
- 요청: `?cursor=&size=20` (정렬 `created_at DESC`, `idx_point_ledger_member_id_created_at`)
- 응답 `200`
  ```json
  {
    "items": [
      { "id": 501, "amount": -100, "balanceAfter": 12, "txType": "BOOST_USE", "refType": "QUESTION", "refId": 42, "createdAt": "2026-09-27T18:00:00+09:00" },
      { "id": 500, "amount": 1, "balanceAfter": 112, "txType": "VOTE_REWARD", "refType": "VOTE", "refId": 9001, "createdAt": "2026-09-27T17:55:00+09:00" }
    ],
    "nextCursor": "eyJ...",
    "hasNext": true
  }
  ```

### 6.3 POST /questions/{id}/boosts — 상단 노출 사용
- 인증: ACTIVE, 작성자만
- 헤더: `Idempotency-Key: <UUID>` 필수
- 요청 본문 없음
- 응답 `200`
  ```json
  {
    "questionId": 42,
    "boostedUntil": "2026-09-28T18:00:00+09:00",
    "cost": 100,
    "balanceAfter": 12,
    "ledgerId": 501
  }
  ```
- **트랜잭션 흐름**
  1. `point_ledger` 에서 `idempotency_key` 조회. 있으면 그 행이 가리키는 결과(`ref_id` 의 고민 `boosted_until`, `balance_after`)를 그대로 200 반환. `ref_id` 가 다른 고민이면 `IDEMPOTENCY_KEY_CONFLICT`
  2. 고민 조회: 없음·삭제 → `QUESTION_NOT_FOUND`, 남의 것 → `FORBIDDEN`, CLOSED/HIDDEN → `QUESTION_CLOSED`
  3. `point_wallet` 낙관적 락으로 읽어 `balance < 100` 이면 `POINT_INSUFFICIENT`, 아니면 `balance - 100`. 경합으로 음수가 되려 하면 `chk_point_wallet_balance` 가 막고 `POINT_INSUFFICIENT` 로 변환
  4. `INSERT point_ledger(amount=-100, tx_type='BOOST_USE', ref_type='QUESTION', ref_id=questionId, idempotency_key=헤더값)`
  5. `question.boosted_until = GREATEST(NOW(), boosted_until) + 24h` (이미 노출 중이면 남은 시간에 이어 붙임)
- 주요 에러: `IDEMPOTENCY_KEY_REQUIRED`, `IDEMPOTENCY_KEY_CONFLICT`, `POINT_INSUFFICIENT`, `QUESTION_NOT_FOUND`, `FORBIDDEN`, `QUESTION_CLOSED`

---

## 7. 지인에게 숨기기

원본 연락처는 저장하지 않는다. 서버가 번호를 E.164 로 정규화해 HMAC-SHA256 만 남긴다. 클라이언트에서 HMAC 을 만들지 않는 이유는 HMAC 키가 서버 비밀이기 때문이다. 요청 본문은 액세스 로그에 남기지 않는다.

### 7.1 PUT /members/me/contacts — 연락처 업로드
- 인증: ACTIVE
- 요청 (최대 5,000건, 초과 시 `CONTACTS_TOO_MANY`)
  ```json
  { "phones": [ "010-1111-2222", "+82 10 3333 4444" ] }
  ```
- 응답 `200`
  ```json
  { "received": 2, "matchedMembers": 1, "pending": 1 }
  ```
- 처리 (한 트랜잭션, 전체 교체):
  1. 정규화 → HMAC. 내 번호는 제외
  2. 기존 `hide_relation(owner_id = 나)`, `hide_pending(owner_id = 나)` 삭제
  3. HMAC 이 `member.phone_hmac` 과 일치하는 회원 → `hide_relation(owner_id = 나, target_member_id)`. 자기 자신은 `chk_hide_relation_not_self` 가 막는다
  4. 일치하지 않는 HMAC → `hide_pending(owner_id = 나, phone_hmac)`. 그 번호가 나중에 가입해 휴대폰 인증을 마치면 3.4 의 5단계에서 `hide_relation` 으로 옮겨진다
- 관계 데이터는 `hide_from_contacts` 플래그와 무관하게 유지된다. 플래그가 꺼져 있으면 피드 필터에서 무시할 뿐이다
- 주요 에러: `VALIDATION_ERROR`, `CONTACTS_TOO_MANY`

### 7.2 PUT /members/me/hide-from-contacts — 켜기/끄기
- 인증: ACTIVE
- 요청
  ```json
  { "enabled": true }
  ```
- 응답 `200`
  ```json
  { "enabled": true, "hiddenMembers": 1, "pending": 1 }
  ```
- 처리: `member.hide_from_contacts` 만 변경. 켜는 순간 피드 필터 조건 4 가 즉시 적용된다. 연락처를 업로드하지 않은 채 켜면 200 이지만 `hiddenMembers = 0`

---

## 8. 차단·신고

### 8.1 POST /members/{id}/blocks — 차단
- 인증: ACTIVE
- 요청 본문 없음
- 응답 `201`
  ```json
  { "blockedMemberId": 15, "createdAt": "2026-09-27T18:10:00+09:00" }
  ```
- 처리: `member_block(blocker_id = 나, blocked_id = id)`. 자기 자신은 API 와 `chk_member_block_not_self` 양쪽에서 막는다. 차단 즉시 양쪽 피드에서 서로의 고민이 사라진다 (4.2 조건 3)
- 주요 에러: `BLOCK_SELF`, `BLOCK_ALREADY_EXISTS`, `MEMBER_NOT_FOUND`

### 8.2 DELETE /members/{id}/blocks — 차단 해제
- 인증: ACTIVE
- 응답 `204` (없어도 204)

### 8.3 GET /members/me/blocks — 차단 목록
- 인증: ACTIVE
- 응답 `200`
  ```json
  { "items": [ { "memberId": 15, "nickname": "누군가", "createdAt": "2026-09-27T18:10:00+09:00" } ] }
  ```
  차단 수는 많지 않으므로 페이징 없이 전체를 내려준다

### 8.4 POST /questions/{id}/reports — 고민 신고
- 인증: ACTIVE
- 요청
  ```json
  { "reason": "PERSONAL_INFO", "detail": "카톡 캡처에 이름이 보여요" }
  ```
  `reason`: `ABUSE` / `PERSONAL_INFO` / `SPAM` / `ETC`. `detail` 은 선택, 200자
- 응답 `201`
  ```json
  { "reportId": 77, "status": "RECEIVED" }
  ```
- 처리: `report(question_id, reporter_id, reason, status='RECEIVED')`. 같은 고민 재신고는 `uk_report_reporter_id_question_id` 로 409
- **자동 숨김 정책**: 같은 고민의 `RECEIVED` 신고가 5건(설정값 `pickone.report.auto-hide-threshold`) 이상이면 `question.status = 'HIDDEN'` 으로 바꾼다. 이후 운영자가 `ACCEPTED`(유지) / `REJECTED`(ACTIVE 복구) 로 처리한다. 운영자 API 는 1차 범위 밖
- 주요 에러: `REPORT_DUPLICATE`, `REPORT_OWN_QUESTION`, `QUESTION_NOT_FOUND`, `VALIDATION_ERROR`

---

## 9. 2차 기능 설계 (구현 범위 아님)

### 9.1 프로필 속성
회원이 성별·출생연도·MBTI·학력을 직접 입력한다. 지금은 본인 입력이지만 나중에 본인인증(PASS 등)과 연동하면 검증된 값으로 승격할 수 있도록 속성별 `verified` 플래그를 둔다.

- 테이블 초안 `member_profile` (V3)
  | 컬럼 | 타입 | 설명 |
  |---|---|---|
  | member_id | BIGINT PK, FK | |
  | gender | VARCHAR(10) NULL | MALE / FEMALE / NONE |
  | gender_verified | BIT(1) | 본인인증으로 확인됨 |
  | birth_year | SMALLINT NULL | |
  | birth_year_verified | BIT(1) | |
  | mbti | CHAR(4) NULL | |
  | education | VARCHAR(20) NULL | HIGH_SCHOOL / COLLEGE / UNIVERSITY / GRADUATE 등 |
  | updated_at | DATETIME(6) | |
- API 초안: `GET /members/me/profile`, `PUT /members/me/profile`
- 개인정보 원칙: 결과 화면에 개별 투표자의 속성을 절대 노출하지 않는다. 속성은 집계에만 쓴다

### 9.2 질문 대상 지정
작성자가 "20대 여성에게만 물어보기" 처럼 대상을 정한다.

- 테이블 초안 `question_target` (V4)
  | 컬럼 | 타입 | 설명 |
  |---|---|---|
  | question_id | BIGINT PK, FK | 행이 없으면 조건 없음 |
  | gender | VARCHAR(10) NULL | |
  | birth_year_from / birth_year_to | SMALLINT NULL | |
  | mbti_list | VARCHAR(80) NULL | 콤마 구분, 최대 16개 |
  | education | VARCHAR(20) NULL | |
- 등록 요청에 `target` 객체를 선택으로 추가한다
  ```json
  { "questionType": "TEXT", "content": "...", "options": [ ... ],
    "target": { "gender": "FEMALE", "birthYearFrom": 1997, "birthYearTo": 2006 } }
  ```
- **익명성 보호**: 조건에 맞는 ACTIVE 회원이 N명(설정값, 초안 30명) 미만이면 400 `TARGET_TOO_NARROW`. 등록 전에 `GET /questions/target-estimate?gender=FEMALE&birthYearFrom=1997&birthYearTo=2006` 으로 `{ "count": 128, "allowed": true }` 를 확인할 수 있다. 정확한 수 대신 구간(예: "100명 이상")으로 내려주는 것도 검토
- 피드 필터에 조건 6 추가: `question_target` 이 있으면 내 `member_profile` 이 조건에 맞아야 한다. 프로필을 입력하지 않은 회원에게는 대상 지정 고민을 보여주지 않는다

### 9.3 속성별 결과 분석
- API 초안: `GET /questions/{id}/results/breakdown?by=gender|ageGroup|mbti|education`
- 응답 초안
  ```json
  { "by": "gender", "groups": [
      { "key": "FEMALE", "totalVotes": 31, "options": [ { "optionId": 101, "count": 20 }, { "optionId": 102, "count": 11 } ] },
      { "key": "MALE", "hidden": true, "reason": "TOO_FEW" } ] }
  ```
- **k-익명성**: 구간 인원이 k명(설정값, 초안 5명) 미만이면 그 구간은 숫자 없이 `hidden: true` 로 내려준다. 전체 결과에서 다른 구간을 빼는 방식으로 역산되지 않도록, 숨긴 구간이 하나라도 있으면 그 축의 `totalVotes` 도 숨긴다
- 집계는 `vote JOIN member_profile` 로 계산하되, 트래픽이 커지면 투표 시점의 속성을 `vote` 에 스냅샷하는 방안을 검토한다 (프로필을 나중에 바꿔도 과거 결과가 흔들리지 않게)

### 9.4 2차 API 요약
| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | /members/me/profile | 내 속성 조회 |
| PUT | /members/me/profile | 내 속성 입력/수정 |
| GET | /questions/target-estimate | 대상 인원 추정 |
| POST | /questions (target 확장) | 대상 지정 등록 |
| GET | /questions/{id}/results/breakdown | 속성별 결과 |

---

## 10. DB 마이그레이션 계획

V1 은 이미 push 되었으므로 수정하지 않는다. 아래는 계획이며 SQL 파일은 각 기능 구현 시점에 만든다.

### V2 — 소셜 로그인·가입 상태 (1차 기능용)
파일명 예: `V2__social_login_and_signup_status.sql`

| 대상 | 변경 | 이유 |
|---|---|---|
| `member.email` | NULL 허용 (유니크 유지) | 카카오는 이메일을 제공하지 않을 수 있음. MariaDB 유니크는 NULL 을 여러 개 허용 |
| `member.password_hash` | NULL 허용 | 소셜 가입자는 비밀번호 없음 |
| `member.phone_encrypted`, `member.phone_hmac` | NULL 허용 (유니크 유지) | 휴대폰 인증 전 `PENDING_PHONE` 상태 허용 |
| `member.signup_status` | `VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'` 추가 | PENDING_PHONE / ACTIVE. 기존 행은 이미 휴대폰이 있으므로 ACTIVE 가 맞고, 신규 가입 로직이 PENDING_PHONE 을 명시적으로 넣는다. 추가 후 DEFAULT 제거 여부는 구현 시 결정 |
| `member_social_account` | 신규 테이블 | 아래 |

```
member_social_account
  id               BIGINT AUTO_INCREMENT PK
  member_id        BIGINT NOT NULL, FK → member.id
  provider         VARCHAR(20) NOT NULL   -- KAKAO / GOOGLE (/ NAVER 확장)
  provider_user_id VARCHAR(100) NOT NULL  -- 제공자 사용자 식별자
  email            VARCHAR(100) NULL      -- 제공자가 준 이메일 (참고용)
  created_at       DATETIME(6) NOT NULL
  UNIQUE uk_member_social_account_provider_user (provider, provider_user_id)
  INDEX  idx_member_social_account_member_id (member_id)
```

- 한 회원이 여러 제공자를 연동할 수 있도록 member 1 : N social_account 로 둔다 (1차에서는 연동 기능이 없어 실제로는 1:1)
- OTP, refresh 토큰, 소셜 일회용 code 는 Redis 만 쓰므로 테이블이 없다
- 엔티티 매핑 주의: `signup_status` 는 enum → `VARCHAR`, `hide_from_contacts` 는 BIT(1) → `boolean`

### V3 — 프로필 속성 (2차)
파일명 예: `V3__member_profile.sql`. 9.1 의 `member_profile` 테이블 생성

### V4 — 질문 대상 지정 (2차)
파일명 예: `V4__question_target.sql`. 9.2 의 `question_target` 테이블 생성

### 이후 후보
- 랭킹·알림·카테고리: 기획서 "이후 추가 기능"
- `vote` 속성 스냅샷 컬럼 (9.3 성능 대안)

---

## 11. 부록

### 11.1 엔드포인트 요약 (1차)
| 메서드 | 경로 | 인증 | 설명 |
|---|---|---|---|
| POST | /auth/signup | 공개 | 이메일 가입 |
| POST | /auth/login | 공개 | 이메일 로그인 |
| POST | /auth/refresh | 공개 | 토큰 재발급 (rotation) |
| POST | /auth/logout | 로그인 | refresh 폐기 |
| POST | /auth/token | 공개 | 소셜 일회용 code → JWT |
| GET | /oauth2/authorization/{provider} | 공개 | 소셜 로그인 시작 (Spring Security) |
| GET | /members/me | 로그인 | 내 정보 |
| PATCH | /members/me | 로그인 | 닉네임 변경 |
| POST | /phone-verifications | 로그인 | 인증번호 발송 |
| POST | /phone-verifications/confirm | 로그인 | 인증번호 확인 → ACTIVE |
| POST | /questions | ACTIVE | 고민 등록 |
| GET | /questions/feed | ACTIVE | 피드 (커서) |
| GET | /questions/{id} | ACTIVE | 상세 |
| GET | /members/me/questions | ACTIVE | 내 고민 목록 (커서) |
| DELETE | /questions/{id} | ACTIVE | 삭제 |
| POST | /questions/{id}/votes | ACTIVE | 투표 |
| GET | /questions/{id}/results | ACTIVE | 결과 |
| GET | /points/balance | ACTIVE | 잔액 |
| GET | /points/ledger | ACTIVE | 내역 (커서) |
| POST | /questions/{id}/boosts | ACTIVE | 상단 노출 (Idempotency-Key) |
| PUT | /members/me/contacts | ACTIVE | 연락처 업로드 |
| PUT | /members/me/hide-from-contacts | ACTIVE | 지인 숨기기 켜기/끄기 |
| POST | /members/{id}/blocks | ACTIVE | 차단 |
| DELETE | /members/{id}/blocks | ACTIVE | 차단 해제 |
| GET | /members/me/blocks | ACTIVE | 차단 목록 |
| POST | /questions/{id}/reports | ACTIVE | 신고 |

### 11.2 열거형
| 이름 | 값 | 저장 위치 |
|---|---|---|
| signupStatus | PENDING_PHONE, ACTIVE | `member.signup_status` (V2) |
| memberStatus | ACTIVE, SUSPENDED | `member.status` |
| provider | EMAIL(응답 전용), KAKAO, GOOGLE, (NAVER) | `member_social_account.provider` (V2) |
| questionType | TEXT, IMAGE | `question.question_type` |
| questionStatus | ACTIVE, HIDDEN, CLOSED | `question.status` |
| reportReason | ABUSE, PERSONAL_INFO, SPAM, ETC | `report.reason` |
| reportStatus | RECEIVED, ACCEPTED, REJECTED | `report.status` |
| txType | VOTE_REWARD, BOOST_USE | `point_ledger.tx_type` |
| refType | VOTE, QUESTION | `point_ledger.ref_type` |

### 11.3 Redis 키 요약
| 키 | 용도 | TTL |
|---|---|---|
| `refresh:{memberId}:{jti}` | 유효한 refresh | 14일 |
| `refresh:used:{jti}` | 교체된 refresh (재사용 감지) | 원래 만료 시각까지 |
| `oauth:code:{code}` | 소셜 로그인 일회용 code | 60초 |
| `otp:{phoneHmac}` | 인증번호 해시·시도 횟수 | 180초 |
| `otp:cooldown:{phoneHmac}` | 재발송 쿨다운 | 60초 |
| `otp:daily:phone:{phoneHmac}:{date}`, `otp:daily:member:{memberId}:{date}` | 일일 발송 한도 | 자정까지 |
| `point:daily:{memberId}:{date}` | 일일 투표 적립 합계 | 자정까지 |
