-- 고민 등록의 Idempotency-Key (docs/api.md 1.6, 4.1)
-- 응답을 받지 못한 클라이언트가 같은 요청을 다시 보내도 고민이 한 번만 등록되게 한다.
--  - idempotency_key: 클라이언트가 보낸 헤더값(UUID). 헤더 없이 등록한 고민과 기존 행은 NULL
--    유니크 인덱스는 NULL 을 여러 개 허용하므로 키 없는 등록은 서로 충돌하지 않는다
--  - request_hash: 요청 내용(유형·본문·선택지)의 SHA-256. 같은 키로 다른 내용이 오면 IDEMPOTENCY_KEY_CONFLICT 로 거절하는 데 쓴다
ALTER TABLE question
    ADD COLUMN idempotency_key VARCHAR(100) NULL COMMENT '등록 요청의 Idempotency-Key (없으면 NULL)' AFTER boosted_until,
    ADD COLUMN request_hash    CHAR(64)     NULL COMMENT '등록 요청 내용의 SHA-256 (키가 있을 때만)' AFTER idempotency_key,
    ADD CONSTRAINT uk_question_idempotency_key UNIQUE (idempotency_key);
