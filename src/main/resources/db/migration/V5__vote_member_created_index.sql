-- 내가 투표한 고민 목록 (docs/api.md 5.3): 회원의 투표를 created_at 역순으로 읽는다.
-- V1 의 vote 인덱스(uk_vote_member_id_question_id, idx_vote_question_id_option_id)로는 이 정렬을 받칠 수 없어 filesort 가 난다.
ALTER TABLE vote
    ADD INDEX idx_vote_member_id_created_at (member_id, created_at) COMMENT '내가 투표한 고민 목록';
