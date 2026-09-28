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
