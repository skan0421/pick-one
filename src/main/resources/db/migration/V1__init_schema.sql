-- V1: MVP 초기 스키마 (docs/erd.md 기준)
-- 규칙: PK 는 BIGINT AUTO_INCREMENT, 컬럼명은 snake_case, 문자셋은 utf8mb4
-- 참고: boolean 컬럼은 Hibernate 가 MariaDB 에서 BIT 로 매핑하므로 ddl-auto=validate 통과를 위해 BIT(1) 사용

-- 회원. 휴대폰 번호는 AES 암호화본 + HMAC(검색용) 두 컬럼으로 저장
CREATE TABLE member (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    email              VARCHAR(100) NOT NULL,
    password_hash      VARCHAR(255) NOT NULL COMMENT 'BCrypt',
    nickname           VARCHAR(30)  NOT NULL,
    phone_encrypted    VARCHAR(255) NOT NULL COMMENT 'AES-GCM 암호화 (복호화 가능)',
    phone_hmac         CHAR(64)     NOT NULL COMMENT 'HMAC-SHA256 (검색/매칭 전용)',
    hide_from_contacts BIT(1)       NOT NULL DEFAULT b'0' COMMENT '지인에게 숨기기 ON/OFF',
    status             VARCHAR(20)  NOT NULL COMMENT 'ACTIVE / SUSPENDED',
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    deleted_at         DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_member_email UNIQUE (email),
    CONSTRAINT uk_member_nickname UNIQUE (nickname),
    CONSTRAINT uk_member_phone_hmac UNIQUE (phone_hmac)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 고민
CREATE TABLE question (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    member_id     BIGINT       NOT NULL,
    question_type VARCHAR(10)  NOT NULL COMMENT 'TEXT / IMAGE',
    content       VARCHAR(300) NOT NULL COMMENT '고민 본문',
    status        VARCHAR(20)  NOT NULL COMMENT 'ACTIVE / HIDDEN(신고 누적) / CLOSED',
    boosted_until DATETIME(6)  NULL COMMENT '포인트로 상단 노출 시 만료 시각',
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    deleted_at    DATETIME(6)  NULL,
    PRIMARY KEY (id),
    INDEX idx_question_status_created_at (status, created_at) COMMENT '피드 조회',
    INDEX idx_question_member_id_created_at (member_id, created_at) COMMENT '내 고민 목록',
    CONSTRAINT fk_question_member FOREIGN KEY (member_id) REFERENCES member (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 선택지. TEXT 는 2~4개(content), IMAGE 는 정확히 2개(image_url). 개수 규칙은 등록 API 에서 검증
CREATE TABLE question_option (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    question_id BIGINT       NOT NULL,
    sort_order  TINYINT      NOT NULL COMMENT '1~4',
    content     VARCHAR(20)  NULL COMMENT 'TEXT 유형일 때',
    image_url   VARCHAR(500) NULL COMMENT 'IMAGE 유형일 때',
    PRIMARY KEY (id),
    CONSTRAINT uk_question_option_question_id_sort_order UNIQUE (question_id, sort_order),
    CONSTRAINT uk_question_option_id_question_id UNIQUE (id, question_id) COMMENT 'vote 의 복합 FK 참조 대상',
    CONSTRAINT chk_question_option_sort_order CHECK (sort_order BETWEEN 1 AND 4),
    CONSTRAINT fk_question_option_question FOREIGN KEY (question_id) REFERENCES question (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 투표. question_id 를 중복 저장하는 이유: option_id 만으로는 "한 고민에 한 번" 유니크를 걸 수 없음
-- option 은 (option_id, question_id) 복합 FK 로 참조해 "선택지가 그 고민의 것"임을 DB 가 보장
CREATE TABLE vote (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    question_id BIGINT      NOT NULL,
    option_id   BIGINT      NOT NULL,
    member_id   BIGINT      NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_vote_member_id_question_id UNIQUE (member_id, question_id) COMMENT '중복 투표 방지',
    INDEX idx_vote_question_id_option_id (question_id, option_id) COMMENT '결과 집계',
    CONSTRAINT fk_vote_question FOREIGN KEY (question_id) REFERENCES question (id),
    CONSTRAINT fk_vote_option FOREIGN KEY (option_id, question_id) REFERENCES question_option (id, question_id),
    CONSTRAINT fk_vote_member FOREIGN KEY (member_id) REFERENCES member (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 포인트 잔액. 빠른 조회용, version 으로 낙관적 락
CREATE TABLE point_wallet (
    member_id  BIGINT      NOT NULL,
    balance    BIGINT      NOT NULL DEFAULT 0,
    version    BIGINT      NOT NULL DEFAULT 0 COMMENT '낙관적 락',
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (member_id),
    CONSTRAINT chk_point_wallet_balance CHECK (balance >= 0),
    CONSTRAINT fk_point_wallet_member FOREIGN KEY (member_id) REFERENCES member (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 포인트 원장. 모든 변동의 근거. SUM(amount) = point_wallet.balance 가 항상 성립해야 함
CREATE TABLE point_ledger (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    member_id       BIGINT       NOT NULL,
    amount          BIGINT       NOT NULL COMMENT '적립 +, 차감 -',
    balance_after   BIGINT       NOT NULL COMMENT '처리 후 잔액 (검증용)',
    tx_type         VARCHAR(30)  NOT NULL COMMENT 'VOTE_REWARD / BOOST_USE / SIGNUP_BONUS ...',
    ref_type        VARCHAR(30)  NULL COMMENT 'VOTE / QUESTION ...',
    ref_id          BIGINT       NULL COMMENT '관련 엔티티 ID',
    idempotency_key VARCHAR(100) NOT NULL COMMENT '같은 요청 중복 처리 방지',
    created_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_point_ledger_idempotency_key UNIQUE (idempotency_key),
    INDEX idx_point_ledger_member_id_created_at (member_id, created_at) COMMENT '내 포인트 내역',
    CONSTRAINT fk_point_ledger_member FOREIGN KEY (member_id) REFERENCES member (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 지인 숨김 (가입한 지인). owner 의 고민을 target 에게 숨김
CREATE TABLE hide_relation (
    owner_id         BIGINT      NOT NULL COMMENT '숨기기를 켠 작성자',
    target_member_id BIGINT      NOT NULL COMMENT '고민을 못 보는 지인',
    created_at       DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id, target_member_id),
    INDEX idx_hide_relation_target_member_id (target_member_id) COMMENT '피드 조회 시 제외 대상 검색',
    CONSTRAINT chk_hide_relation_not_self CHECK (owner_id <> target_member_id),
    CONSTRAINT fk_hide_relation_owner FOREIGN KEY (owner_id) REFERENCES member (id),
    CONSTRAINT fk_hide_relation_target FOREIGN KEY (target_member_id) REFERENCES member (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 지인 숨김 대기 (아직 가입 안 한 번호의 HMAC). 가입 시 hide_relation 으로 이동
CREATE TABLE hide_pending (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    owner_id   BIGINT      NOT NULL,
    phone_hmac CHAR(64)    NOT NULL COMMENT '아직 가입 안 한 연락처',
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_hide_pending_owner_id_phone_hmac UNIQUE (owner_id, phone_hmac),
    INDEX idx_hide_pending_phone_hmac (phone_hmac) COMMENT '신규 가입 시 매칭',
    CONSTRAINT fk_hide_pending_owner FOREIGN KEY (owner_id) REFERENCES member (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 사용자 차단
CREATE TABLE member_block (
    blocker_id BIGINT      NOT NULL,
    blocked_id BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (blocker_id, blocked_id),
    CONSTRAINT chk_member_block_not_self CHECK (blocker_id <> blocked_id),
    CONSTRAINT fk_member_block_blocker FOREIGN KEY (blocker_id) REFERENCES member (id),
    CONSTRAINT fk_member_block_blocked FOREIGN KEY (blocked_id) REFERENCES member (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 고민 신고
CREATE TABLE report (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    question_id BIGINT      NOT NULL,
    reporter_id BIGINT      NOT NULL,
    reason      VARCHAR(30) NOT NULL COMMENT 'ABUSE / PERSONAL_INFO / SPAM / ETC',
    status      VARCHAR(20) NOT NULL COMMENT 'RECEIVED / ACCEPTED / REJECTED',
    created_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_report_reporter_id_question_id UNIQUE (reporter_id, question_id) COMMENT '중복 신고 방지',
    CONSTRAINT fk_report_question FOREIGN KEY (question_id) REFERENCES question (id),
    CONSTRAINT fk_report_reporter FOREIGN KEY (reporter_id) REFERENCES member (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
