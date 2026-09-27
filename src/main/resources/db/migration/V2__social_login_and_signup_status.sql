-- V2: 소셜 로그인·가입 상태 (docs/api.md 10장 V2 계획)
-- 가입 흐름이 "가입 → PENDING_PHONE → 휴대폰 인증 → ACTIVE" 로 바뀌면서 인증 전에는 비어 있을 수 있는 컬럼을 NULL 허용으로 변경
-- 유니크 제약은 그대로 유지한다 (MariaDB 유니크 인덱스는 NULL 을 여러 개 허용)

ALTER TABLE member
    MODIFY email           VARCHAR(100) NULL COMMENT '카카오는 이메일을 제공하지 않을 수 있음',
    MODIFY password_hash   VARCHAR(255) NULL COMMENT 'BCrypt. 소셜 가입자는 NULL',
    MODIFY phone_encrypted VARCHAR(255) NULL COMMENT 'AES-GCM 암호화 (복호화 가능). 휴대폰 인증 전 NULL',
    MODIFY phone_hmac      CHAR(64)     NULL COMMENT 'HMAC-SHA256 (검색/매칭 전용). 휴대폰 인증 전 NULL',
    ADD COLUMN signup_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'PENDING_PHONE / ACTIVE' AFTER status;
-- 기존 행은 이미 휴대폰이 있으므로 DEFAULT 'ACTIVE' 가 맞다. 신규 가입 로직은 PENDING_PHONE 을 명시적으로 넣는다

-- 소셜 계정 연결. 한 회원이 여러 제공자를 연동할 수 있도록 1:N 으로 둔다 (1차에서는 실제로 1:1)
CREATE TABLE member_social_account (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    member_id        BIGINT       NOT NULL,
    provider         VARCHAR(20)  NOT NULL COMMENT 'KAKAO / GOOGLE (NAVER 확장 가능)',
    provider_user_id VARCHAR(100) NOT NULL COMMENT '제공자 사용자 식별자 (카카오 id, 구글 sub)',
    email            VARCHAR(100) NULL COMMENT '제공자가 준 이메일 (참고용)',
    created_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_member_social_account_provider_user UNIQUE (provider, provider_user_id),
    INDEX idx_member_social_account_member_id (member_id),
    CONSTRAINT fk_member_social_account_member FOREIGN KEY (member_id) REFERENCES member (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
