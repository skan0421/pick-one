# 트러블슈팅 기록

구현 중 만난 버그·성능·동시성 문제와 해결 과정을 남긴다. 각 항목은 **문제 상황 / 원인 / 해결 / 결과(수치) / 관련 커밋** 순서로 적는다.
수치는 Testcontainers(MariaDB 11.4, Redis 7.4) 통합 테스트 또는 로컬 Compose DB 에서 실측한 값이다.

---

## 1. 동시 가입 시 이메일·닉네임 유니크 경합

**문제 상황**
가입 API 는 `existsByEmail` / `existsByNickname` 로 먼저 검사한 뒤 INSERT 한다. 같은 이메일로 두 요청이 동시에 오면 둘 다 선검사를 통과하고, 뒤에 커밋하는 쪽이 `uk_member_email` 위반으로 500 `INTERNAL_ERROR` 를 받았다.

**원인**
check-then-act 경합. 선검사와 INSERT 사이에 다른 트랜잭션이 커밋될 수 있고, 유니크 제약 위반은 Spring 이 `DataIntegrityViolationException` 으로 던지는데 이를 처리하는 핸들러가 없었다.

**해결**
- 선검사는 빠른 실패용으로 두고, DB 유니크 제약을 최종 방어선으로 삼는다.
- `GlobalExceptionHandler` 가 `DataIntegrityViolationException` 의 cause 체인에서 Hibernate `ConstraintViolationException#getConstraintName()` 을 꺼내 `ErrorCode.fromConstraintName()` 으로 번역한다 (`uk_member_email` → 409 `MEMBER_EMAIL_DUPLICATE`, `uk_member_nickname` → 409 `MEMBER_NICKNAME_DUPLICATE`).
- MariaDB 는 제약 이름을 `member.uk_member_email` 처럼 테이블 접두사와 함께 주는 경우가 있어 `endsWith` 로 비교한다.

**결과**
같은 이메일·같은 닉네임 각각 2스레드 동시 가입 → `{201, 409}` (AuthIntegrationTest). 이후 휴대폰 번호(`uk_member_phone_hmac`), 투표(`uk_vote_member_id_question_id`), 신고(`uk_report_reporter_id_question_id`)도 같은 방식으로 번역한다.

**관련 커밋** `0b15bfa`

---

## 2. Refresh 토큰 재발급 동시성 (rotation)

**문제 상황**
같은 refresh 토큰으로 동시에 두 번 재발급을 요청하면, "키 존재 확인 → 옛 키 삭제 → 새 키 저장" 을 여러 명령으로 처리할 경우 둘 다 확인을 통과해 refresh 가 두 개 발급될 수 있다. 재사용 감지(탈취 대응)도 무력화된다.

**원인**
Redis 명령 사이에 다른 요청이 끼어드는 check-then-act 경합.

**해결**
- Lua 스크립트 하나로 "`refresh:{memberId}:{jti}` 삭제 + `refresh:used:{jti}` 표시(TTL = 원래 만료 시각까지) + 새 키 저장" 을 원자 처리한다. 진 쪽은 이미 `used` 가 된 jti 를 든 것이므로 재사용으로 판정한다.
- 재사용 판정 시 그 회원의 refresh 를 전부 삭제한다 (모든 기기 로그아웃).

**결과**
같은 refresh 2스레드 동시 재발급 → `{200, 401 AUTH_REFRESH_REUSED}`, 이긴 쪽의 새 refresh 까지 폐기됨 (RefreshTokenIntegrationTest). 클라이언트가 재발급을 직렬화해야 한다는 제약을 api.md 1.3 에 명시했다.

**관련 커밋** `7d3fbc7`, `22989c7`

---

## 3. SMS OTP 발송·확인의 원자 처리

**문제 상황**
- 같은 번호로 동시에 발송 요청이 오면 쿨다운 검사를 모두 통과해 문자가 여러 통 나갈 수 있다.
- 틀린 코드를 동시에 여러 번 넣으면 "읽고 +1 해서 쓰는" 방식에서는 시도 횟수가 덜 세어져 5회 제한이 뚫린다. 맞는 코드가 동시에 오면 두 번 성공할 수 있다.

**원인**
Redis 읽기와 쓰기가 분리된 비원자 연산.

**해결**
| 지점 | 연산 |
|---|---|
| 재발송 쿨다운 | `SET otp:cooldown:{phoneHmac} 1 NX EX 60` — 한 요청만 통과 |
| 일일 한도 | `INCR` 후 값 비교 — 거절된 요청도 1회로 센다 |
| 코드 확인 | Lua: 해시 비교 → 일치면 `DEL`, 불일치면 `HINCRBY attempts` → 5 도달 시 `DEL` |

코드는 Lua 에서 소비되므로 그 뒤 DB 트랜잭션이 실패하면(같은 번호를 다른 회원이 먼저 인증 → `uk_member_phone_hmac` → 409) 코드를 다시 요청해야 한다는 점을 문서에 남겼다.

**결과**
같은 번호 동시 발송 3건 → `{202, 429, 429}`. 두 회원이 같은 번호를 동시에 확인 → `{200, 409 PHONE_ALREADY_REGISTERED}`. 틀린 코드 동시 입력 시 시도 횟수가 정확히 증가 (PhoneVerificationIntegrationTest).

**관련 커밋** `4aabf42`, `ab48edb`

---

## 4. 피드 조회 EXPLAIN — JOIN 때문에 filesort, 상단 노출 인덱스

**문제 상황**
피드 SQL 에 지인 숨김 필터를 `JOIN member a ON a.id = h.owner_id` 로 본문에 두자 `ANALYZE FORMAT=JSON` 에서 `Using temporary; Using filesort` 가 나왔다. 상단 노출 단계(`boosted_until > NOW()`)는 인덱스가 없어 3,000행을 전부 읽고 정렬했다.

**원인**
- 옵티마이저가 작은 `member` 테이블을 조인 버퍼(BNL)로 처리하면서 `ORDER BY created_at DESC, id DESC` 에 맞는 `idx_question_status_created_at` 역순 스캔을 버렸다.
- `ORDER BY` 에 계산식(`boosted_until > NOW()`)이 있으면 인덱스로 정렬할 수 없다.

**해결**
- 지인 숨김 조인을 `NOT EXISTS (... JOIN member ...)` 서브쿼리 안으로 옮겨 본문에는 `question` 만 남긴다. 차단 조건도 방향별 `NOT EXISTS` 두 개로 나눠 각자 `member_block` PK 를 타게 했다.
- 피드를 "상단 노출 단계(B) → 일반 단계(N)" 두 번의 키셋 조회로 나누고, B 단계용 `idx_question_status_boosted_until` 을 V3 로 추가했다.
- 페이지당 SQL 이 2~3회(ID 조회 + fetch join)로 고정되는지 `FeedQueryCountTest` 가 검증한다.

**결과** (question 3,000행, 상단 노출 30행)
| 단계 | key | 실제 읽은 rows | filesort |
|---|---|---|---|
| B 첫 페이지 | `idx_question_status_boosted_until` | 30 | 30행 priority queue |
| N 첫 페이지 | `idx_question_status_created_at` 역순 | **21** (조인 시 3,000) | 없음 |
| N 커서 이후 | `idx_question_status_created_at` | **22** | 없음 |

**관련 커밋** `719c570`, `3773a8f`

---

## 5. 일일 적립 상한 판정 — Redis 카운터에서 원장 SUM 으로

**문제 상황**
명세 초안은 투표 트랜잭션 안에서 Redis 카운터 `point:daily:{memberId}:{date}` 를 읽어 50P 상한을 판정하고, 커밋 후 카운터를 올리는 구조였다. 설계 검토에서 49P 상태의 회원이 동시에 두 건 투표하면 둘 다 49 < 50 을 읽어 51P 가 적립될 수 있음을 확인했다. 카운터 유실이나 커밋~INCR 사이 장애도 초과 적립으로 이어진다.

**원인**
판정 근거(Redis)와 커밋 대상(DB)이 다른 저장소라 트랜잭션 격리가 적용되지 않는다.

**해결**
- 판정을 트랜잭션 안의 원장 `SUM(amount) WHERE member_id=? AND tx_type='VOTE_REWARD' AND created_at >= KST 오늘 자정` 으로 바꿨다 (`idx_point_ledger_member_id_created_at`, 하루 최대 50행).
- 적립하는 투표는 모두 같은 `point_wallet` 행을 UPDATE 하므로 낙관적 락이 회원 단위로 적립을 직렬화한다. 진 쪽은 재시도에서 SUM=50 을 보고 `earned=false` 가 된다.
- Redis 카운터는 `GET /points/balance` 의 `todayEarned` 표시용 캐시로 격하하고, 유실되면 같은 SUM 으로 다시 채운다.

**결과**
오늘 49P 상태에서 서로 다른 고민 3개에 동시 투표 → 201 3건, 적립 정확히 1건(잔액 50), 낙관적 락 재시도 2회 (PointConcurrencyTest 케이스 6). 추가 비용은 인덱스 range 조회 1회.

**관련 커밋** `66b365c`, `cd806ee`

---

## 6. REPEATABLE READ 스냅샷 때문에 투표 결과 집계 누락

**문제 상황**
서로 다른 회원 5명이 같은 고민에 동시 투표하는 테스트에서 "마지막 응답의 `totalVotes` 는 5" 를 기대했는데 모든 응답이 1 이었다.

**원인**
결과 집계(`GROUP BY option_id`)를 투표 트랜잭션 안에서 했다. InnoDB REPEATABLE READ 는 트랜잭션의 첫 consistent read 시점에 스냅샷을 고정하므로, 그 뒤 커밋된 다른 회원의 표는 보이지 않는다.

**해결**
집계를 트랜잭션 밖(커밋 이후, `VoteService`)으로 옮겨 새 스냅샷으로 읽는다. 명세 5.1 의 "7. 커밋 후 결과 집계" 순서와도 일치한다. 재시도가 일어나도 집계는 한 번만 한다.

**결과**
같은 테스트에서 마지막 커밋 응답의 `totalVotes = 5`. 트랜잭션에서 SELECT 하나가 빠져 X 락 보유 구간도 짧아졌다.

**관련 커밋** `b5dbd28`

---

## 7. 지갑 경합의 재시도 폭주와 백오프

**문제 상황**
같은 회원이 서로 다른 고민 4개에 동시에 투표하면 지갑 `version` 충돌로 재시도가 일어난다. 실측 재시도 횟수가 이론 상한인 6회(= 4·3/2)까지 올라가, 마지막 스레드가 총 시도 4회(1 + 재시도 3)를 전부 소진했다.

**원인**
같은 `point_wallet` 행의 X 락을 기다리던 트랜잭션들이 이긴 쪽 커밋 순간 **한꺼번에** `WHERE version=?` 0행으로 실패하고, **한꺼번에** 다시 부딪힌다 (thundering herd). 매 라운드 1개만 성공하므로 N개 스레드는 N 라운드가 필요하다.

**해결**
- 재시도 전 `5~30ms × attempt` 무작위 백오프를 넣어 출발 시점을 흩는다.
- 재시도는 트랜잭션 전체를 새로 시작하는 구조(`OptimisticRetryExecutor` 가 `@Transactional` 프록시 바깥에서 재실행)로 두고, 트랜잭션 안에서 호출되면 `IllegalStateException` 으로 막는다.
- "총 시도 A ≥ 경쟁 스레드 N 이면 전부 성공" 을 논증했다: 각 실패는 다른 스레드의 커밋 1건이 원인이고 그 커밋은 최대 N-1개. 동시성 테스트의 스레드 수를 4(= 총 시도)로 고정해 보장 범위 안에서 검증한다.

**결과**
4스레드 지갑 경합 → 201 4건, 잔액 4, 원장 `balance_after` 1·2·3·4, 재시도 4~6회 (백오프 후에도 트랜잭션이 수 ms 라 상한에 닿는 경우가 있음). 재시도 소진 시 응답은 409 `POINT_WALLET_CONFLICT`. 경합이 심해지면 `UPDATE point_wallet SET balance = balance + 1` 원자 갱신으로 바꾸는 선택지를 문서에 남겼다.

**관련 커밋** `b5dbd28`, `cd806ee`

---

## 8. boost 멱등 재응답의 `boostedUntil` 이 최초 응답과 불일치 (나노초 vs 마이크로초)

**문제 상황**
같은 `Idempotency-Key` 로 boost 를 다시 요청하면 최초 응답을 그대로 돌려줘야 하는데, 테스트에서 두 본문이 달랐다.
```
expected: "boostedUntil":"2026-09-29T15:36:06.1125994"
 but was: "boostedUntil":"2026-09-29T15:36:06.112599"
```

**원인**
최초 응답은 메모리의 `LocalDateTime.now() + 24h` (이 JVM 에서 100ns 단위, 7자리)를 그대로 내려주고, 재응답은 DB `DATETIME(6)` 에서 읽은 마이크로초(6자리) 값을 내려줬다.

**해결**
`Question.extendBoost()` 에서 `truncatedTo(ChronoUnit.MICROS)` 로 잘라 저장값과 응답값을 같게 만들었다.

**결과**
같은 키 재요청·동시 3건 요청 모두 응답 본문(ledgerId, balanceAfter, boostedUntil)이 동일 (PointIntegrationTest, PointConcurrencyTest 케이스 4).

**관련 커밋** `d51a1ac`

---

## 9. FK·CHECK 제약 위반이 500 으로 떨어지는 문제

**문제 상황**
유니크 제약만 에러 코드로 번역하고 있어, `fk_vote_option`(다른 고민의 선택지) 이나 `chk_point_wallet_balance`(잔액 음수) 위반은 500 이 될 상황이었다. Hibernate MariaDB 방언이 FK/CHECK 위반에서도 제약 이름을 추출하는지 확실하지 않았다.

**원인**
`ErrorCode` 에 두 제약 이름이 없었고, 추출 여부가 방언·드라이버 구현에 달려 있었다.

**해결**
- hibernate-core 7.4.5 의 `MariaDBDialect` 를 확인: 1452(FK)·4025(CHECK) 모두 `" CONSTRAINT `"` 템플릿으로 이름을 추출한다. CHECK 는 MariaDB Connector/J 가 메시지 앞에 `(conn=N) ` 를 붙여 주기 때문에 템플릿이 맞는다 (드라이버 의존성을 주석으로 남김).
- `VOTE_OPTION_MISMATCH` ← `fk_vote_option`, `POINT_INSUFFICIENT` ← `chk_point_wallet_balance` 매핑을 추가하고, 실제 MariaDB 에서 위반을 일으켜 추출·번역을 검증하는 `ConstraintTranslationTest` 를 두었다.

**결과**
두 제약 모두 이름이 추출되고 각각 400 / 409 로 번역된다. 정규식 보조 추출은 불필요했다.

**관련 커밋** `eff477b`, `66b365c`

---

## 10. 동시 차단에서 UPSERT 뒤 SELECT 가 0행 — REPEATABLE READ 스냅샷

**문제 상황**
같은 상대를 3스레드가 동시에 차단하는 테스트에서 응답이 `[500, 201, 500]` 이었다. 로그는 `EmptyResultDataAccessException: Incorrect result size: expected 1, actual 0` — `INSERT ... ON DUPLICATE KEY UPDATE` 직후 `SELECT created_at FROM member_block WHERE ...` 가 행을 못 찾았다.

**원인**
진 쪽 트랜잭션은 UPSERT 전에 `memberRepository.findById(target)` 로 이미 consistent read 를 했고, 그 시점에 REPEATABLE READ 스냅샷이 고정됐다. UPSERT 는 이긴 쪽의 행 락을 기다렸다가 "중복 → 무변경 UPDATE" 경로로 정상 종료했지만, 이어지는 일반 SELECT 는 옛 스냅샷이라 이긴 쪽이 막 커밋한 행이 보이지 않았다. (6번과 같은 원리인데 방향이 반대다: 6번은 다른 사람의 새 행이 안 보였고, 여기서는 내가 방금 "있음" 을 확인한 행이 안 보인다.)

**해결**
`created_at` 을 `SELECT ... FOR UPDATE` 로 읽는다. 잠금 읽기는 스냅샷이 아니라 최신 커밋 버전을 읽고, 행 락은 UPSERT 가 이미 잡고 있어 추가 대기가 없다.

**결과**
동시 차단 3건 → 201 3건, `member_block` 1행, `created_at` 동일 (MemberBlockIntegrationTest).

**관련 커밋** `7467eed`

---

## 11. 연락처 5,000건 저장 — IDENTITY 엔티티의 JPA saveAll 은 건별 INSERT

**문제 상황**
연락처 업로드는 한 번에 최대 5,000건을 `hide_relation` / `hide_pending` 에 넣는다. `hide_pending` 은 IDENTITY 전략이라 Hibernate 가 INSERT 를 배치로 묶지 못하고(`hibernate.jdbc.batch_size` 도 IDENTITY 에는 무효) 건마다 DB 를 왕복한다.

**원인**
IDENTITY 는 INSERT 를 실행해야 PK 를 알 수 있어 Hibernate 가 persist 시점에 즉시 실행한다. 5,000건이면 5,000회 왕복 + 5,000개 엔티티 상태 관리.

**해결**
- 저장은 `JdbcTemplate.batchUpdate` (1,000건 단위 분할) 로, 회원 매칭은 엔티티 대신 `SELECT id, phone_hmac FROM member WHERE phone_hmac IN (...)` (1,000개씩 분할, `uk_member_phone_hmac`) 로 처리한다. 같은 `@Transactional` 커넥션을 쓰므로 실패 시 함께 롤백된다.
- 테스트에서 같은 5,000건을 `hidePendingRepository.saveAll` 로 넣는 시간을 함께 재서 비교했다.

**결과** (Testcontainers MariaDB, 정규화 + HMAC 계산 포함)
| 방식 | 5,000건 |
|---|---|
| API 최초 업로드 (`batchUpdate`) | 352 ~ 392 ms |
| API 재교체 (삭제 5,000 + 삽입 5,000) | 163 ~ 249 ms |
| JPA `saveAll` (IDENTITY, 건별 INSERT) | 3,068 ~ 6,675 ms |

약 9~17배 차이. 로컬 컨테이너라 절대값보다 비율이 의미 있다.

**관련 커밋** `aadc1a0`

---

## 12. 동시 신고에서 N건째 자동 숨김 전환 누락 가능성

**문제 상황**
신고 누적 5건이면 HIDDEN 으로 바꿔야 한다. "INSERT 후 COUNT" 를 각자 트랜잭션에서 하면, 3건 있는 고민에 2건이 동시에 올 때 둘 다 자기 스냅샷에서 4를 세어 전환을 놓친다 (설계 단계에서 확인).

**원인**
REPEATABLE READ 스냅샷은 다른 트랜잭션이 동시에 넣은 행을 보여주지 않는다. 6·10번과 같은 뿌리다.

**해결**
고민 행을 `SELECT ... FOR UPDATE` 로 먼저 잠가 같은 고민의 신고를 직렬화한다. 잠금 읽기는 read view 를 만들지 않으므로, 그 뒤의 COUNT 가 첫 consistent read 가 되어 락을 얻은 시점까지 커밋된 신고가 모두 보인다. 이미 HIDDEN 인 고민도 신고는 접수한다.

**결과**
3건 있는 고민에 동시 신고 3건 → 201 3건, HIDDEN. 0건에서 동시 5건 → HIDDEN. 동시 4건 → ACTIVE 유지 (ReportIntegrationTest).

**관련 커밋** `09ccb1c`

---

## 13. 테스트가 개발자 PC 의 application-local.yml 을 읽어 CI 와 설정이 달라짐

**문제 상황**
Swagger 기본값을 꺼짐(`${SWAGGER_ENABLED:false}`)으로 바꾸고 로컬은 `application-local.yml` 에서 켜도록 했더니, "아무 설정 없이 뜬 컨텍스트에서 `/v3/api-docs` 가 401" 을 검증하는 `SwaggerDisabledIntegrationTest` 가 개발자 PC 에서는 Swagger 가 켜진 채로 떴다. CI(로컬 파일 없음)와 결과가 달라지는 상태였고, 그동안은 로컬 파일의 값(DB 접속 정보, 키)이 모두 Testcontainers·`TestSecretsConfiguration` 에 덮여 드러나지 않았다.

**원인**
`application.yml` 의 `spring.profiles.default: local`. 프로필을 지정하지 않으면 앱뿐 아니라 테스트 컨텍스트도 `local` 프로필로 떠서 git 미추적 파일인 `src/main/resources/application-local.yml` 을 읽는다.

**해결**
- `@IntegrationTest` 메타 애너테이션에 `@ActiveProfiles("test")` 를 붙여 모든 통합 테스트의 프로필을 고정했다. `test` 프로필용 설정 파일은 두지 않는다. DB·Redis 는 `@ServiceConnection`, 비밀값은 `TestSecretsConfiguration` 이 주므로 `application.yml` 기본값만으로 뜬다.
- 임시로 넣었던 `SwaggerDisabledIntegrationTest` 의 `spring.profiles.active=test` 우회는 제거했다.
- 통합 테스트가 아닌 테스트(PhoneCipher, PhoneNumber, ErrorCode, LoggingSmsSender, OptimisticRetryExecutor)는 스프링 컨텍스트를 띄우지 않아 프로필과 무관함을 확인했다.

**결과**
로컬 `application-local.yml` 에 `pickone.swagger.enabled: true` 가 있는 상태에서 전체 188건 통과, `SwaggerDisabledIntegrationTest` 는 401 확인. 테스트 결과가 개발자 PC 의 로컬 파일 유무와 무관해졌다.

**관련 커밋** `1bf1cd6`(Swagger 기본값 변경으로 드러남), 이 항목을 추가한 fix 커밋

---

## 14. MinIO 공식 Docker 이미지를 받을 수 없음 (`minio/minio`, `quay.io/minio/minio` 404)

**문제 상황**
사진 업로드용 S3 호환 저장소로 MinIO 를 docker-compose 와 Testcontainers 에 넣었는데, 통합 테스트 컨텍스트가 `Can't get Docker image: minio/minio:RELEASE.2025-09-07T16-13-09Z ... pull access denied for minio/minio, repository does not exist` 로 뜨지 않았다. 태그를 바꿔도(`RELEASE.2025-04-22T22-12-26Z`, `latest`), `quay.io/minio/minio` 로 바꿔도 같았고 Docker Hub API 도 저장소에 404 를 돌려줬다.

**원인**
2026-09 기준 MinIO 의 공식 컨테이너 이미지 저장소(Docker Hub `minio/minio`, `minio/mc`, quay.io)가 공개 pull 이 되지 않는다. Testcontainers 의 `MinIOContainer` 모듈은 이 공식 이미지의 실행 명령(`server --console-address :9001 /data`)을 전제한다.

**해결**
- 받아지는 MinIO 이미지 중 `bitnamilegacy/minio:2025.7.23-debian-12-r5`(+ `bitnamilegacy/minio-client:2025.7.21-debian-12-r3`)를 compose 와 테스트 양쪽에 같은 태그로 쓴다. 데이터 경로는 `/bitnami/minio/data`, 헬스체크는 `/minio/health/live`.
- 테스트는 `MinIOContainer` 대신 `GenericContainer` 로 띄우고(`MINIO_ROOT_USER/PASSWORD`, 9000 노출, HTTP 헬스체크 대기) `DynamicPropertyRegistrar` 로 `pickone.storage.*` 를 주입한다. 버킷 생성과 `images/*` 익명 읽기 정책은 `TestStorageConfiguration` 이 컨텍스트 기동 시 적용한다.
- 앱 코드는 `ImageStorage` 포트 뒤의 AWS SDK v2 구현이라 이미지가 무엇이든(어떤 S3 호환 저장소든) 영향이 없다. Bitnami legacy 이미지는 갱신이 멈춘 이미지이므로, 운영은 S3 를 쓰고 로컬 이미지는 compose 의 `image:` 한 줄만 바꾸면 된다.

**결과**
업로드 통합 테스트 8건 통과: 발급 → 실제 presigned PUT(200) → 사진형 고민 등록, 형식·크기 불일치 PUT 은 403, 익명 GET 은 `images/*` 만 200 이고 버킷 목록·`private/*` 는 403.

**관련 커밋** 사진 업로드 feat 커밋

---

## 15. AWS SDK v2: `ForcePathStyle has been configured on both S3Configuration and the client`

**문제 상황**
`S3Client.builder().serviceConfiguration(S3Configuration.pathStyleAccessEnabled(true)).forcePathStyle(true)` 로 만들자 기동 시 `IllegalStateException: ForcePathStyle has been configured on both S3Configuration and the client/global level` 이 났다.

**원인**
SDK 2.x 는 path-style 을 `S3Configuration`(구 방식)과 클라이언트 빌더의 `forcePathStyle`(신 방식) 두 곳에서 받는데, 둘 다 주면 거부한다. `S3Presigner.Builder` 에는 `forcePathStyle` 이 없어 `serviceConfiguration` 만 쓸 수 있다.

**해결**
클라이언트는 `forcePathStyle(true)` 만, 프리사이너는 `serviceConfiguration(S3Configuration.pathStyleAccessEnabled(true))` 만 설정한다. MinIO 는 virtual-host 스타일이 기본이 아니라 path-style(`{endpoint}/{bucket}/{key}`)이 필요하다.

**결과**
컨텍스트 기동 정상, presigned URL 이 `http://host:port/{bucket}/images/...` 형태로 생성되어 MinIO 가 서명을 검증한다.

**관련 커밋** 사진 업로드 feat 커밋

---

## 16. EXPLAIN 테스트가 데이터가 적을 때 풀스캔을 보여줌 (인덱스 검증의 함정)

**문제 상황**
내가 투표한 고민 목록용 인덱스 `idx_vote_member_id_created_at`(V5)을 추가하고, 실제 서비스 SQL 을 `EXPLAIN` 하는 테스트를 만들었더니 `type=ALL, key=null, Extra=Using where; Using filesort` 로 인덱스를 전혀 쓰지 않았다.

**원인**
테스트 DB 의 vote 테이블에는 이 회원의 표 40행이 거의 전부였다. `member_id = :me` 가 테이블의 대부분을 만족하면 옵티마이저는 인덱스 ref + 되돌아 읽기보다 풀스캔 + filesort 를 싸다고 판단한다. 인덱스가 잘못된 게 아니라 데이터 분포가 실제와 달랐다.

**해결**
테스트에서 다른 회원 60명을 DB 에 직접 만들고 같은 고민들에 투표한 행 2,400건을 `batchUpdate` 로 넣어(내 표는 전체의 1.6%) `ANALYZE TABLE` 뒤에 EXPLAIN 한다. EXPLAIN 으로 인덱스를 검증할 때는 "필터 조건이 걸러내는 비율" 이 실제 서비스와 비슷해야 한다는 점을 테스트 주석과 api.md 5.3 에 남겼다.

**결과**
첫 페이지 `type=ref, key=idx_vote_member_id_created_at, rows=40, Extra=Using where`, 커서 이후 `type=range, rows=20`. 둘 다 filesort 없음 (MyVotesExplainTest 가 단정).

**관련 커밋** 내가 투표한 고민 목록 feat 커밋

---

## 17. KST 자정 이후 일일 적립 상한 테스트가 실패 — DB 시계(UTC)와 JVM 시계(KST) 불일치

**문제 상황**
전체 테스트를 KST 자정이 지난 시각에 돌리자 `일일_적립_상한에_도달하면_투표는_되지만_적립은_없다` 와 동시성 테스트 `일일_상한_직전에_동시에_투표하면_…` 이 실패했다(`earned` 가 true, 적립 3건). 낮에는 항상 통과하던 테스트다.

**원인**
두 테스트는 "오늘 이미 적립한" 원장 행을 `INSERT ... created_at = NOW(6)` 으로 넣는다. Testcontainers MariaDB 는 기본 시간대가 UTC 라 `NOW()` 가 UTC 시각인데, 앱은 `@CreationTimestamp`(JVM, KST) 로 쓰고 `KstDates.startOfTodayInSystemZone()`(KST 자정) 과 비교한다. KST 00:00~09:00 에는 DB 의 `NOW()` 가 아직 "어제" 라 상한 판정에서 빠졌다. 서비스 코드는 항상 JVM 시계로 쓰므로 실제 데이터에는 문제가 없고, 테스트 픽스처만 다른 시계를 썼다.

**해결**
- 픽스처의 `created_at` 을 `LocalDateTime.now()` 파라미터로 넣어 앱과 같은 시계를 쓴다.
- Testcontainers MariaDB 에 `TZ=Asia/Seoul` 을 줘 docker-compose 와 시간대를 맞춘다 (compose 는 이미 `TZ: Asia/Seoul`).
- 원칙: "오늘" 을 판정하는 코드와 그 테스트 픽스처는 같은 시계를 써야 한다. DB 함수(`NOW()`)로 시각을 만드는 픽스처는 시간대가 다른 환경에서 시간대 경계 근처에 깨진다.

**결과**
자정 이후에도 전체 테스트 통과. 같은 회귀를 막기 위해 픽스처 주석에 근거를 남겼다.

**관련 커밋** 내가 투표한 고민 목록 feat 커밋

---

## 18. 앱 웹 버전이 빈 화면 — 플랫폼별 파일(.web.ts)이 자기 자신을 import

**문제 상황**
앱(`app/`)의 타입 검사·린트·단위 테스트 17건이 모두 통과한 상태에서 웹 버전(`npx expo start --web`)을 열자 화면이 비어 있었다. 브라우저 콘솔에는 `Maximum call stack size exceeded`, 번들러 로그에는 `Require cycle: src/auth/tokenStorage.web.ts -> src/auth/tokenStorage.web.ts` 가 찍혔다.

**원인**
토큰 저장소는 앱용 `tokenStorage.ts`(expo-secure-store)와 웹용 `tokenStorage.web.ts`(sessionStorage)로 나뉘어 있고, 번들러(Metro)가 `./tokenStorage` 를 플랫폼에 맞는 파일로 바꿔 준다. 웹용 파일이 공용 타입과 `parseTokens` 를 `./tokenStorage` 에서 가져왔는데, 웹에서는 이 경로가 앱용 파일이 아니라 **웹용 파일 자신**으로 해석되어 자기 자신을 끝없이 불러왔다.
검사가 잡지 못한 이유: 타입 검사(tsc)는 플랫폼 접미사를 모르므로 `./tokenStorage` 를 항상 `tokenStorage.ts` 로 해석하고, 단위 테스트(jest-expo)는 기본 플랫폼이 iOS 인 데다 저장소 모듈을 모의 객체로 바꿨다. 즉 웹용 파일을 실제로 실행한 검사가 하나도 없었다.

**해결**
- 공용 타입·함수·키를 플랫폼 접미사가 없는 다른 이름의 파일 `auth/tokens.ts` 로 옮기고, 두 저장소 파일 모두 거기서 가져온다.
- 원칙: `.web.ts` 파일에서 같은 이름의 기본 파일을 import 하지 않는다. 두 파일에 같은 주의 주석을 남겼다.
- 플랫폼별 파일은 정적 검사로 검증되지 않으므로, 웹 버전을 실제 브라우저로 여는 확인을 작업 완료 조건에 넣는다.

**결과**
헤드리스 브라우저로 실제 백엔드에 붙여 가입 → 휴대폰 인증 → 피드 → 재발급 → 탭 이동 → 로그아웃 → 재로그인을 확인한 18개 항목 통과(수정 전 0/18, 첫 화면에서 중단). 만료 401 이후 `/auth/refresh` 호출은 1회.

**관련 커밋** 앱 뼈대 화면 feat 커밋

---

## 19. 샘플 고민이 피드에 다 나오지 않음 — 오래된 올린 시각이 기존 데이터 뒤로 밀림

**문제 상황**
로컬 샘플 데이터(`scripts/local/sample-data.sql`, 고민 24개)를 넣고 앱 피드에서 카드 52장을 넘기며 확인했는데 샘플 고민이 17개만 나왔다. 나머지 7개는 피드에서 사라진 것처럼 보였다.

**원인**
화면의 시각 표시("3분 전", "2일 전", "9월 20일")를 모두 보려고 샘플의 `created_at` 을 2분 전부터 9일 전까지 흩어 놓았다. 피드는 `created_at DESC` 로 정렬하는데(`docs/api.md` 4.2), 로컬 DB 에는 이전 성능 확인 때 넣은 고민이 3,000개 넘게 남아 있었다. 하루 넘게 지난 샘플 7개는 그 뒤로 밀려 수천 장을 넘겨야 나오는 위치에 있었다.
빈 DB 를 전제로 샘플을 만든 것이 잘못이었다. 단위 테스트와 SQL 실행 결과(건수)로는 드러나지 않고, 실제 피드를 넘겨 봐야 보이는 문제였다.

**해결**
- 샘플의 `created_at` 을 모두 최근 23시간 안(2분 전 ~ 23시간 전)으로 모았다. 기존 데이터가 많아도 피드 앞쪽에 나온다.
- 하루 이상 지난 시각의 표시는 화면 대신 단위 테스트(`time.test.ts`)의 경계값으로 확인한다.
- 시간이 지나 샘플이 뒤로 밀리면 스크립트를 다시 실행하면 된다는 점을 `app/README.md` 에 적었다.

**결과**
카드 52장 안에 나온 샘플 고민이 17개 → 24개. 같은 확인에서 다음 쪽 요청은 17번째·37번째 카드에서 각각 1회, 51번째 투표부터 "오늘 적립 한도(50P) 도달" 안내가 나오는 것까지 헤드리스 브라우저 163개 항목 통과.

**관련 커밋** 로컬 샘플 데이터 스크립트 chore 커밋

---

## 20. 올리기 버튼을 빠르게 두 번 누르면 고민이 두 개 등록 — 상태 값은 다시 그려진 뒤에야 버튼을 막음

**문제 상황**
앱 올리기 화면에서 제출 버튼을 같은 순간에 두 번 누르면 `POST /questions` 가 2회 나가고 같은 고민이 두 개 등록됐다. 버튼에는 `disabled={submitting}` 이 걸려 있었다.

**원인**
`setSubmitting(true)` 는 값을 바로 바꾸지 않는다. React 는 상태 변경을 모아 두었다가 화면을 다시 그릴 때 반영하므로, 첫 번째 클릭이 `setSubmitting(true)` 를 부른 직후에도 두 번째 클릭이 보는 `submitting` 은 아직 `false` 다. 버튼이 실제로 막히는 것은 다시 그려진 뒤다.
서버에는 고민 등록의 중복을 막는 장치가 없다(투표는 유니크 제약이 있지만 같은 내용의 고민을 두 번 올리는 것은 정상 요청이다). 그래서 클라이언트가 막아야 한다.

**해결**
- `useRef` 로 제출 잠금을 두었다. ref 의 값은 바꾸는 즉시 반영된다. 제출 함수 첫 줄에서 잠금을 확인하고, 요청이 끝나면(성공·실패 모두) 푼다.
- 화면 표시용 `submitting` 상태는 그대로 두어 버튼의 진행 표시와 비활성을 맡긴다.
- 피드의 투표는 같은 문제가 없었다. 투표 상태를 React 밖의 객체(`FeedController`)가 들고 있어 변경이 즉시 반영되기 때문이다.

**결과**
같은 순간의 클릭 2회에 대한 등록 요청 2회 → 1회 (글형·사진형 모두, 헤드리스 브라우저로 확인).

**관련 커밋** 앱 올리기 탭(글형) feat 커밋

---

## 21. 웹에서 HEIC 사진을 고르면 아무 반응이 없음 — 사진 선택 단계의 예외를 잡지 않음

**문제 상황**
웹(Windows 의 Chrome)에서 사진 칸을 눌러 `.HEIC` 파일을 고르면 사진도 오류 문구도 나오지 않았다. 사용자는 고른 것이 무시됐다는 사실을 알 수 없었다.

**원인**
변환에 실패했을 때의 안내는 만들어 두었지만, 실패는 그보다 앞 단계에서 났다. 브라우저가 형식을 모르는 파일은 `File.type` 이 빈 문자열이고, `expo-image-picker` 의 웹 구현은 형식이 `image/` 로 시작하지 않으면 `Unsupported file type` 예외를 던진다. 사진 선택 호출이 `try` 밖에 있어 이 예외가 어디에도 잡히지 않았다.
단위 테스트는 "변환 단계의 실패"만 다뤘고 선택 단계는 기기 기능이라 테스트 대상이 아니었다. 실제 브라우저에서 파일을 골라 보고서야 드러났다.

**해결**
- 사진 선택 호출을 `try` 로 감싸고, 실패하면 변환 실패와 같은 안내("이 기기에서 변환할 수 없는 사진 형식이에요")를 그 칸 아래에 보여 준다. 칸에 있던 사진은 그대로 둔다.
- HEIC 를 풀지 못하는 브라우저에서는 변환 자체가 불가능하다는 점을 `app/README.md` 에 적었다. 휴대폰 앱에서는 기기가 변환한다(실제 기기로는 확인하지 못함).

**결과**
HEIC 선택 시 안내 문구 표시: 없음 → 있음. 사진형 올리기 검증 29개 항목 통과(발급과 PUT 의 형식·크기 일치, 21.5MB PNG → 1.0MB JPEG, 한 장 실패 시 등록 0회, 재시도 시 실패한 사진만 발급·PUT 각 1회).

**관련 커밋** 앱 올리기 탭(사진형) feat 커밋

---

## 22. 연달아 뜨는 안내가 바로 사라짐 — 앞 안내의 타이머가 새 안내를 닫음

**문제 상황**
내 고민 탭에서 고민을 삭제한 직후(안내 "삭제했습니다." 가 떠 있는 동안) 다른 고민의 삭제가 실패하면, 실패 안내가 뜨자마자 사라져 읽을 수 없었다.

**원인**
안내(Snackbar)는 보이기 시작할 때 닫기 타이머(2.5초)를 건다. 안내 문구만 바꾸면 컴포넌트는 그대로이므로 타이머도 그대로다. 앞 안내의 남은 시간이 지나면 새 안내까지 닫힌다.

**해결**
- 안내에 번호(`seq`)를 붙이고 Snackbar 의 `key` 로 쓴다. 번호가 바뀌면 React 가 컴포넌트를 새로 만들어 타이머가 처음부터 다시 시작한다. 피드 화면에서 이미 쓰던 방식이다.
- 닫을 때는 문구만 비우고 번호는 남긴다. 번호까지 지우면 다음 안내가 다시 1번이 되어 새 안내로 구분되지 않는다.

**결과**
안내 사이 간격 0.3초에서 두 번째 안내가 정상 표시(수정 전에는 찾지 못하고 시간 초과). 내 고민 탭 검증 28개 항목 통과.

**관련 커밋** 앱 내 고민 탭 feat 커밋

---

## 23. 웹은 되는데 휴대폰 앱에서만 사진 업로드 403 — 올리는 방식이 서명된 크기를 보장하지 못함

**문제 상황**
웹에서는 사진형 고민이 등록되는데, 실제 안드로이드 폰(Expo Go, Tailscale 경유)에서는 사진 2장 모두 저장소로의 PUT 이 HTTP 403 으로 실패했다. 연결 실패가 아니라 403 이므로 휴대폰은 저장소(MinIO, 9000 포트)에 닿았다. 같은 때 `Response.blob() is using React Native's Blob ... Add expo-blob` 경고가 떴다. 화면 문구는 "사진을 올리지 못했습니다. (HTTP 403)" 뿐이어서 저장소가 무엇을 문제 삼았는지 알 수 없었다.

**원인**
업로드 주소의 서명에는 호스트·`Content-Type`·`Content-Length` 가 들어 있어, 발급 때 말한 값과 하나라도 다르게 보내면 저장소가 거절한다(서버의 보안 장치이며 바꾸지 않는다).
앱은 웹과 휴대폰 모두 같은 코드로 올렸다. 파일을 `fetch(uri).blob()` 으로 메모리에 읽어 Blob 을 만들고, 그 크기를 발급에 보낸 뒤, Blob 을 `fetch` 의 본문으로 PUT 했다. `Content-Length` 는 직접 정하지 않고 "본문 크기로 자동으로 붙는다"고 가정했다.
- 브라우저에서는 이 가정이 맞는다. Blob 의 크기가 그대로 `Content-Length` 가 된다.
- React Native 의 `fetch` 와 Blob 은 브라우저 것이 아니라 자체 구현이다(위 경고가 이를 알려 준다). 본문을 어떤 길이로, 길이를 밝혀서 보내는지를 앱이 정할 수 없다.

즉 "서명된 크기와 실제로 보내는 크기가 같다"를 웹에서는 브라우저가 보장했지만, 휴대폰에서는 아무도 보장하지 않았다. 다만 **403 의 정확한 사유(`SignatureDoesNotMatch` 등)는 확인하지 못했다.** 당시 앱이 응답 본문을 버렸기 때문이며, 위 설명 중 휴대폰 쪽은 추정이다. 전송 방식을 바꾸자 해결됐다는 점이 이 추정을 뒷받침한다(아래 결과).
검사가 잡지 못한 이유: 단위 테스트는 올리는 함수를 가짜로 바꿔 "발급과 PUT 에 같은 값을 넘기는지"만 봤고, 실제 전송은 웹(헤드리스 브라우저)으로만 확인했다. 휴대폰 확인은 "확인하지 못한 것"으로 남아 있었다.

**해결**
- 올리는 코드를 플랫폼별 파일로 나눴다. 휴대폰(`compose/fileTransfer.ts`)은 `expo-file-system` 의 `File.upload` 로 기기의 파일을 그대로 PUT 한다. 안드로이드 구현은 파일 길이를 본문 길이로 쓰므로(`RequestBody.contentLength()`) `Content-Length` 가 파일 크기로 나간다. 웹(`compose/fileTransfer.web.ts`)은 동작하던 방식 그대로다.
- 휴대폰에서는 크기도 Blob 이 아니라 파일에서 읽는다(`File.size`). 파일을 메모리로 읽지 않으므로 위 경고의 원인(`Response.blob()`)도 없어진다. 크기는 전과 같이 변환·축소가 끝난 파일 기준이다.
- PUT 직전에 파일 크기가 발급 때 보낸 크기와 같은지 확인하고, 다르면 올리지 않는다.
- 저장소가 거절하면 응답 본문(XML)의 `<Code>` 를 읽어 화면 문구와 로그에 남긴다: "사진을 올리지 못했습니다. (HTTP 403, SignatureDoesNotMatch)". 업로드 주소는 서명이 들어 있어 로그에 남기지 않는다.
- 두 파일이 함께 쓰는 코드는 다른 이름의 파일(`compose/storageError.ts`)에 두었다(18번의 자기 참조 함정).

**결과**
- 단위 테스트 247건 통과(추가 34건: 플랫폼별 업로드 16, 응답 본문에서 Code 추출 등 17, 크기만 읽는 사진 준비 1). 휴대폰용 파일은 `fetch` 호출 0회, 웹용 파일은 기기 파일 기능 호출 0회.
- 번들 확인: 올리기 화면의 안드로이드·iOS 번들에는 `fileTransfer.ts`, 웹 번들에는 `fileTransfer.web.ts` 만 들어 있다.
- 웹(헤드리스 브라우저, 실제 서버·저장소) 21개 항목 통과: PUT 2회 모두 200, 발급 크기 = 보낸 바이트 = 저장소에서 내려받은 바이트(346,296 / 828,689), 17.2MB PNG → 0.8MB JPEG. 서명을 바꾼 주소로는 403 이고 칸 아래에 `(HTTP 403, SignatureDoesNotMatch)` 표시, 등록 0회.
- 휴대폰(안드로이드, Expo Go, Tailscale 경유): 사진 업로드 성공 2장 중 0장 → 2장 중 2장, 사진형 고민 등록 성공. `expo-blob` 경고 없음.
- 남은 것: 수정 후에는 403 이 나지 않아, 수정 전 403 의 정확한 사유(Code)는 끝내 확인하지 못했다. 올리는 방식만 바꿔 해결됐으므로 원인이 전송 방식에 있었다는 것까지만 확인됐다. 아이폰과 HEIC 변환은 확인하지 않았다.

**관련 커밋** 앱 휴대폰 사진 업로드 fix 커밋

---

## 24. 연결이 끊기면 로딩만 계속 돎 — 요청에 제한 시간이 없음

**문제 상황**
실제 안드로이드 폰(Expo Go, Tailscale 경유)에서 상단 노출 확인 창을 연 뒤 Tailscale 을 끄고 확인을 눌렀다. "다시 시도" 안내가 나오지 않고 확인 버튼의 로딩만 계속 돌았다. 창을 닫을 수도, 다시 누를 수도 없었다.

**원인**
공통 클라이언트(`api/client.ts`)의 `fetch` 에 제한 시간이 없었다. `fetch` 는 기본 제한 시간이 없어, 응답도 예외도 오지 않으면 끝나지 않는다.
- 다시 시도 안내는 이미 만들어 두었다. 다만 `fetch` 가 예외를 던져야(`NETWORK_ERROR`) 그 분기로 간다. 서버가 꺼져 있을 때처럼 연결이 바로 거절되면 예외가 나지만, 이번에는 아무 답도 오지 않았다.
- 답이 오지 않은 이유는 추정이다. Tailscale 을 끄면 서버 주소(100.x.x.x)로 가는 길이 사라지는데, 거절 응답을 돌려줄 상대도 없어 기기는 연결이 끊긴 것을 알지 못하고 기다린다. 기기가 스스로 포기하기까지 걸리는 시간은 재 보지 않았다.
- 단위 테스트가 잡지 못한 이유: 가짜 `fetch` 는 "응답한다"와 "예외를 던진다" 두 가지만 흉내 냈다. "아무 일도 일어나지 않는다"는 경우가 없었다.

상단 노출은 포인트를 쓰는 요청이라 더 문제다. 앱이 기다리는 동안 서버는 이미 100P 를 뺐을 수 있다.

**해결**
- 제한 시간 헬퍼 `api/timeout.ts` 의 `withTimeout` 을 만들었다. `AbortController` 로 요청을 취소하고, 취소 신호를 무시하는 구현을 만나도 끝나도록 타이머와 경주(`Promise.race`)시킨다.
- 공통 클라이언트의 모든 요청에 15초를 적용했다. HTTP 호출 한 번마다 재고, 응답 본문을 다 읽을 때까지 잰다. 토큰 재발급과 재발급 후 재전송도 각각 15초다.
- 시간 초과는 네트워크 오류와 같은 코드(`NETWORK_ERROR`, status 0)로 던진다. 둘 다 "서버가 처리했는지 모른다"는 같은 상황이다. 코드가 같으므로 기존 분기가 그대로 동작한다: 재발급 중이었다면 토큰을 지우지 않고, 상단 노출은 키를 유지한 채 "다시 시도" 를 안내한다. 문구만 다르다("서버가 응답하지 않습니다. 네트워크 연결을 확인해 주세요.").
- 상단 노출은 시간 초과 뒤 다시 눌러도 같은 `Idempotency-Key` 가 나간다. 키 규칙(`mine/boostKeys.ts`)은 바꾸지 않았다. 성공하기 전에는 키를 버리지 않는 규칙이 시간 초과에도 그대로 적용된다. 클라이언트는 시간 초과된 요청을 스스로 다시 보내지 않는다.
- 사진 업로드(PUT)는 제한 시간을 따로 두었다(60초). 파일을 보내는 시간이 들어가기 때문이다. 공통 클라이언트를 거치지 않고 저장소로 직접 가므로 `compose/fileTransfer.ts`(`File.upload` 의 `signal`)와 `compose/fileTransfer.web.ts`(`fetch` 의 `signal`)에 각각 적용했다. 올리는 사진은 줄인 뒤라 1MB 안팎이고, 60초는 업로드 주소의 수명(5분)보다 짧다. 업로드 주소 발급(`POST /uploads/images`)은 일반 요청이라 15초다.

**결과**
- 응답이 없을 때 기다리는 시간: 끝나지 않음 → 15초(사진 PUT 은 60초). 14,999ms 에는 기다리는 중이고 15,000ms 에 끝나는 것을 가짜 시계로 확인했다.
- 단위 테스트 361건 통과(추가 24건: 제한 시간 헬퍼 6, 공통 클라이언트 9, 상단 노출 시간 초과 6, 사진 업로드 3). 타입 검사·린트 통과.
- 상단 노출: 시간 초과 1회 뒤 다시 시도 → 서버로 나간 키 2개가 같음. 3회 연속 시간 초과 뒤 성공 → 키 4개가 같음. 서버가 처리하고 응답만 오지 않은 경우에도 다시 시도 후 차감 횟수 1회(같은 키를 기억하는 가짜 서버로 확인). 성공한 뒤 누르면 새 키.
- 휴대폰(안드로이드, Expo Go, Tailscale 경유): Tailscale 을 끈 상태에서 요청하면 로딩이 끝나지 않음 → 15초 뒤 "다시 시도" 안내. 상단 노출 → 연장 → Tailscale 을 끊고 재시도한 뒤 원장의 차감은 -100P 3건이고 지갑 잔액과 원장 합계가 같다(712P). 이중 차감 없음.
- 확인하지 못한 것: 사진 올리기의 60초 제한은 휴대폰에서 확인하지 않았다(단위 테스트로만 확인). 아이폰은 확인하지 않았다.
- 남은 것: 고민 등록(`POST /questions`)에는 `Idempotency-Key` 가 없다. 시간 초과 뒤 다시 누르면, 서버가 첫 요청을 이미 처리했을 경우 같은 고민이 두 번 등록될 수 있다. 제한 시간이 없던 때에는 드러나지 않던 경우다.

**관련 커밋** 앱 요청 제한 시간 fix 커밋
