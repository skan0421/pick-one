-- V3: 피드 "상단 노출 단계" 조회용 인덱스 (docs/api.md 4.2)
-- 피드는 (1) boosted_until > NOW() 인 고민 → (2) 그 외 최신순, 두 단계로 조회한다.
-- (2) 는 V1 의 idx_question_status_created_at 을 역순으로 읽어 filesort 없이 LIMIT 에서 멈추지만,
-- (1) 은 boosted_until 범위 조건을 받쳐 줄 인덱스가 없어 옵티마이저가 member_id 인덱스로 전체 ACTIVE 행을 읽고 정렬했다.
-- EXPLAIN (3,000행, 그중 30행 상단 노출): 추가 전 rows=3000 + filesort → 추가 후 rows=30 + 소량 filesort
ALTER TABLE question
    ADD INDEX idx_question_status_boosted_until (status, boosted_until) COMMENT '피드 상단 노출 단계';
