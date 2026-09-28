-- 신고 상세 사유 (docs/api.md 8.4: detail 은 선택, 200자)
ALTER TABLE report
    ADD COLUMN detail VARCHAR(200) NULL COMMENT '신고 상세 (선택)' AFTER reason;
