-- 로컬 전용 샘플 데이터. 앱의 피드·투표를 시험할 때 쓴다.
--
--  * Flyway 마이그레이션이 아니다. db/migration 밖에 있으므로 서버가 자동으로 실행하지 않는다
--  * 실행: scripts/local/load-sample-data.ps1 (사용법은 app/README.md 의 "로컬 샘플 데이터")
--  * 여러 번 실행해도 된다. 샘플 회원의 고민과 거기 달린 투표·신고를 지우고 다시 넣는다
--    (투표로 받은 포인트는 그대로 남는다. point_ledger 를 건드리지 않아 잔액과 원장 합계가 계속 일치한다)
--  * 샘플 회원은 글 작성자로만 존재한다. 비밀번호가 없어 로그인할 수 없다
--  * 사진은 외부 주소(picsum.photos)라 인터넷이 필요하다.
--    등록 API 는 이런 주소를 거절하지만(IMAGE_URL_INVALID) 조회 쪽은 저장된 값을 그대로 돌려준다
SET NAMES utf8mb4;

START TRANSACTION;

-- 1. 샘플 회원 3명. 이미 있으면 그대로 다시 쓴다.
--    회원을 지우지 않는 이유: 앱에서 샘플 회원을 차단·신고했다면 그 행이 회원을 참조하고 있어 삭제가 실패한다
INSERT INTO member (nickname, hide_from_contacts, status, signup_status, created_at, updated_at)
VALUES ('샘플_민지', b'0', 'ACTIVE', 'ACTIVE', NOW(6), NOW(6)),
       ('샘플_준호', b'0', 'ACTIVE', 'ACTIVE', NOW(6), NOW(6)),
       ('샘플_서연', b'0', 'ACTIVE', 'ACTIVE', NOW(6), NOW(6))
ON DUPLICATE KEY UPDATE updated_at = NOW(6);

SELECT id INTO @m1 FROM member WHERE nickname = '샘플_민지';
SELECT id INTO @m2 FROM member WHERE nickname = '샘플_준호';
SELECT id INTO @m3 FROM member WHERE nickname = '샘플_서연';

-- 2. 이전에 넣은 샘플 고민 정리. FK 때문에 자식 테이블부터 지운다
DELETE FROM report WHERE question_id IN (SELECT id FROM question WHERE member_id IN (@m1, @m2, @m3));
DELETE FROM vote WHERE question_id IN (SELECT id FROM question WHERE member_id IN (@m1, @m2, @m3));
DELETE FROM question_option WHERE question_id IN (SELECT id FROM question WHERE member_id IN (@m1, @m2, @m3));
DELETE FROM question WHERE member_id IN (@m1, @m2, @m3);

-- 3. 고민 24개 (글형 16 + 사진형 8, 그중 상단 노출 2개).
--    피드 한 쪽이 20개이므로 24개면 다음 쪽 요청까지 시험할 수 있다.
--    선택지 개수 규칙(글형 2~4개, 사진형 2개)과 글자 수(선택지 20자)는 DB 가 아니라 등록 API 가 검사하므로 여기서 직접 지킨다.
--    올린 시각은 2분 전부터 23시간 전 사이다. 이미 다른 고민이 많은 DB 에서도 피드 앞쪽에 나오게 하려는 것이다.
--    시간이 지나 뒤로 밀렸다면 이 스크립트를 다시 실행하면 된다
SET @now = NOW(6);

-- 상단 노출 (글형, 선택지 2개)
INSERT INTO question (member_id, question_type, content, status, boosted_until, created_at, updated_at)
VALUES (@m1, 'TEXT', '소개팅 첫 만남, 어디가 좋을까요?', 'ACTIVE', @now + INTERVAL 1 DAY, @now - INTERVAL 3 HOUR, @now - INTERVAL 3 HOUR);
SET @q = LAST_INSERT_ID();
INSERT INTO question_option (question_id, sort_order, content) VALUES (@q, 1, '카페'), (@q, 2, '밥집');

-- 상단 노출 (사진형)
INSERT INTO question (member_id, question_type, content, status, boosted_until, created_at, updated_at)
VALUES (@m2, 'IMAGE', '이번 휴가, 어느 쪽 풍경이 더 끌리나요?', 'ACTIVE', @now + INTERVAL 1 DAY, @now - INTERVAL 5 HOUR, @now - INTERVAL 5 HOUR);
SET @q = LAST_INSERT_ID();
INSERT INTO question_option (question_id, sort_order, image_url)
VALUES (@q, 1, 'https://picsum.photos/seed/pickone-01a/600/800'), (@q, 2, 'https://picsum.photos/seed/pickone-01b/600/800');

-- 글형, 선택지 2개
INSERT INTO question (member_id, question_type, content, status, boosted_until, created_at, updated_at)
VALUES (@m3, 'TEXT', '야식 고민 중입니다. 오늘은 뭘 시킬까요?', 'ACTIVE', NULL, @now - INTERVAL 2 MINUTE, @now - INTERVAL 2 MINUTE);
SET @q = LAST_INSERT_ID();
INSERT INTO question_option (question_id, sort_order, content) VALUES (@q, 1, '치킨'), (@q, 2, '피자');

INSERT INTO question (member_id, question_type, content, status, boosted_until, created_at, updated_at)
VALUES (@m1, 'TEXT', '퇴근 후 운동, 뭐가 더 꾸준히 할 만한가요?', 'ACTIVE', NULL, @now - INTERVAL 15 MINUTE, @now - INTERVAL 15 MINUTE);
SET @q = LAST_INSERT_ID();
INSERT INTO question_option (question_id, sort_order, content) VALUES (@q, 1, '헬스장'), (@q, 2, '러닝');

INSERT INTO question (member_id, question_type, content, status, boosted_until, created_at, updated_at)
VALUES (@m2, 'TEXT', '친구 생일 선물로 뭐가 나을까요? 예산은 3만원입니다.', 'ACTIVE', NULL, @now - INTERVAL 50 MINUTE, @now - INTERVAL 50 MINUTE);
SET @q = LAST_INSERT_ID();
INSERT INTO question_option (question_id, sort_order, content) VALUES (@q, 1, '향수 미니어처'), (@q, 2, '기프티콘');

INSERT INTO question (member_id, question_type, content, status, boosted_until, created_at, updated_at)
VALUES (@m3, 'TEXT', '이직 제안을 받았어요. 연봉은 10% 오르는데 출퇴근이 40분 늘어납니다. 여러분이라면 어떻게 하시겠어요?', 'ACTIVE', NULL, @now - INTERVAL 2 HOUR, @now - INTERVAL 2 HOUR);
SET @q = LAST_INSERT_ID();
INSERT INTO question_option (question_id, sort_order, content) VALUES (@q, 1, '이직한다'), (@q, 2, '남는다');

INSERT INTO question (member_id, question_type, content, status, boosted_until, created_at, updated_at)
VALUES (@m1, 'TEXT', '주말에 비가 온다는데 약속을 어떻게 할까요?', 'ACTIVE', NULL, @now - INTERVAL 7 HOUR, @now - INTERVAL 7 HOUR);
SET @q = LAST_INSERT_ID();
INSERT INTO question_option (question_id, sort_order, content) VALUES (@q, 1, '그대로 만난다'), (@q, 2, '다음 주로 미룬다');

INSERT INTO question (member_id, question_type, content, status, boosted_until, created_at, updated_at)
VALUES (@m2, 'TEXT', '노트북을 새로 사려고 합니다. 주로 개발용인데 어느 쪽이 나을까요?', 'ACTIVE', NULL, @now - INTERVAL 16 HOUR, @now - INTERVAL 16 HOUR);
SET @q = LAST_INSERT_ID();
INSERT INTO question_option (question_id, sort_order, content) VALUES (@q, 1, '맥북'), (@q, 2, '윈도우 노트북');

INSERT INTO question (member_id, question_type, content, status, boosted_until, created_at, updated_at)
VALUES (@m3, 'TEXT', '아침형 인간에 도전합니다. 몇 시 기상이 현실적일까요?', 'ACTIVE', NULL, @now - INTERVAL 22 HOUR, @now - INTERVAL 22 HOUR);
SET @q = LAST_INSERT_ID();
INSERT INTO question_option (question_id, sort_order, content) VALUES (@q, 1, '6시'), (@q, 2, '7시');

-- 글형, 선택지 3개
INSERT INTO question (member_id, question_type, content, status, boosted_until, created_at, updated_at)
VALUES (@m1, 'TEXT', '점심 메뉴 골라 주세요.', 'ACTIVE', NULL, @now - INTERVAL 5 MINUTE, @now - INTERVAL 5 MINUTE);
SET @q = LAST_INSERT_ID();
INSERT INTO question_option (question_id, sort_order, content) VALUES (@q, 1, '김치찌개'), (@q, 2, '돈가스'), (@q, 3, '쌀국수');

INSERT INTO question (member_id, question_type, content, status, boosted_until, created_at, updated_at)
VALUES (@m2, 'TEXT', '첫 해외여행지를 추천해 주세요. 3박 4일 일정입니다.', 'ACTIVE', NULL, @now - INTERVAL 90 MINUTE, @now - INTERVAL 90 MINUTE);
SET @q = LAST_INSERT_ID();
INSERT INTO question_option (question_id, sort_order, content) VALUES (@q, 1, '일본'), (@q, 2, '대만'), (@q, 3, '베트남');

INSERT INTO question (member_id, question_type, content, status, boosted_until, created_at, updated_at)
VALUES (@m3, 'TEXT', '백엔드 개발자입니다. 다음으로 뭘 공부하는 게 좋을까요?', 'ACTIVE', NULL, @now - INTERVAL 10 HOUR, @now - INTERVAL 10 HOUR);
SET @q = LAST_INSERT_ID();
INSERT INTO question_option (question_id, sort_order, content) VALUES (@q, 1, '쿠버네티스'), (@q, 2, '카프카'), (@q, 3, '알고리즘');

INSERT INTO question (member_id, question_type, content, status, boosted_until, created_at, updated_at)
VALUES (@m1, 'TEXT', '부모님 결혼기념일 선물로 뭐가 좋을까요?', 'ACTIVE', NULL, @now - INTERVAL 18 HOUR, @now - INTERVAL 18 HOUR);
SET @q = LAST_INSERT_ID();
INSERT INTO question_option (question_id, sort_order, content) VALUES (@q, 1, '식사권'), (@q, 2, '여행 상품권'), (@q, 3, '현금');

-- 글형, 선택지 4개
INSERT INTO question (member_id, question_type, content, status, boosted_until, created_at, updated_at)
VALUES (@m2, 'TEXT', '새 취미를 시작하려고 합니다. 하나만 골라 주세요.', 'ACTIVE', NULL, @now - INTERVAL 30 MINUTE, @now - INTERVAL 30 MINUTE);
SET @q = LAST_INSERT_ID();
INSERT INTO question_option (question_id, sort_order, content) VALUES (@q, 1, '기타'), (@q, 2, '수영'), (@q, 3, '베이킹'), (@q, 4, '보드게임');

INSERT INTO question (member_id, question_type, content, status, boosted_until, created_at, updated_at)
VALUES (@m3, 'TEXT', '혼자 사는 직장인인데 반려동물을 키워도 될까요?', 'ACTIVE', NULL, @now - INTERVAL 14 HOUR, @now - INTERVAL 14 HOUR);
SET @q = LAST_INSERT_ID();
INSERT INTO question_option (question_id, sort_order, content) VALUES (@q, 1, '강아지'), (@q, 2, '고양이'), (@q, 3, '물고기'), (@q, 4, '안 키운다');

INSERT INTO question (member_id, question_type, content, status, boosted_until, created_at, updated_at)
VALUES (@m1, 'TEXT', '다음 연휴에 뭘 하면 좋을까요?', 'ACTIVE', NULL, @now - INTERVAL 12 HOUR, @now - INTERVAL 12 HOUR);
SET @q = LAST_INSERT_ID();
INSERT INTO question_option (question_id, sort_order, content) VALUES (@q, 1, '집에서 쉰다'), (@q, 2, '국내 여행'), (@q, 3, '해외 여행'), (@q, 4, '밀린 공부');

INSERT INTO question (member_id, question_type, content, status, boosted_until, created_at, updated_at)
VALUES (@m2, 'TEXT', '하루 한 잔, 어떤 커피가 제일 낫나요?', 'ACTIVE', NULL, @now - INTERVAL 23 HOUR, @now - INTERVAL 23 HOUR);
SET @q = LAST_INSERT_ID();
INSERT INTO question_option (question_id, sort_order, content) VALUES (@q, 1, '아메리카노'), (@q, 2, '라떼'), (@q, 3, '콜드브루'), (@q, 4, '안 마신다');

-- 사진형 (선택지는 항상 2개)
INSERT INTO question (member_id, question_type, content, status, boosted_until, created_at, updated_at)
VALUES (@m3, 'IMAGE', '프로필 사진으로 어느 쪽이 나을까요?', 'ACTIVE', NULL, @now - INTERVAL 8 MINUTE, @now - INTERVAL 8 MINUTE);
SET @q = LAST_INSERT_ID();
INSERT INTO question_option (question_id, sort_order, image_url)
VALUES (@q, 1, 'https://picsum.photos/seed/pickone-02a/600/800'), (@q, 2, 'https://picsum.photos/seed/pickone-02b/600/800');

INSERT INTO question (member_id, question_type, content, status, boosted_until, created_at, updated_at)
VALUES (@m1, 'IMAGE', '거실에 걸 액자를 고르는 중입니다.', 'ACTIVE', NULL, @now - INTERVAL 40 MINUTE, @now - INTERVAL 40 MINUTE);
SET @q = LAST_INSERT_ID();
INSERT INTO question_option (question_id, sort_order, image_url)
VALUES (@q, 1, 'https://picsum.photos/seed/pickone-03a/600/800'), (@q, 2, 'https://picsum.photos/seed/pickone-03b/600/800');

INSERT INTO question (member_id, question_type, content, status, boosted_until, created_at, updated_at)
VALUES (@m2, 'IMAGE', '휴대폰 배경화면, 어느 쪽이 덜 질릴까요?', 'ACTIVE', NULL, @now - INTERVAL 4 HOUR, @now - INTERVAL 4 HOUR);
SET @q = LAST_INSERT_ID();
INSERT INTO question_option (question_id, sort_order, image_url)
VALUES (@q, 1, 'https://picsum.photos/seed/pickone-04a/600/800'), (@q, 2, 'https://picsum.photos/seed/pickone-04b/600/800');

INSERT INTO question (member_id, question_type, content, status, boosted_until, created_at, updated_at)
VALUES (@m3, 'IMAGE', '주말 나들이 장소를 골라 주세요.', 'ACTIVE', NULL, @now - INTERVAL 20 HOUR, @now - INTERVAL 20 HOUR);
SET @q = LAST_INSERT_ID();
INSERT INTO question_option (question_id, sort_order, image_url)
VALUES (@q, 1, 'https://picsum.photos/seed/pickone-05a/600/800'), (@q, 2, 'https://picsum.photos/seed/pickone-05b/600/800');

INSERT INTO question (member_id, question_type, content, status, boosted_until, created_at, updated_at)
VALUES (@m1, 'IMAGE', '블로그 대표 사진으로 뭐가 더 눈에 띄나요?', 'ACTIVE', NULL, @now - INTERVAL 9 HOUR, @now - INTERVAL 9 HOUR);
SET @q = LAST_INSERT_ID();
INSERT INTO question_option (question_id, sort_order, image_url)
VALUES (@q, 1, 'https://picsum.photos/seed/pickone-06a/600/800'), (@q, 2, 'https://picsum.photos/seed/pickone-06b/600/800');

INSERT INTO question (member_id, question_type, content, status, boosted_until, created_at, updated_at)
VALUES (@m2, 'IMAGE', '엽서로 뽑을 사진을 하나만 고른다면?', 'ACTIVE', NULL, @now - INTERVAL 21 HOUR, @now - INTERVAL 21 HOUR);
SET @q = LAST_INSERT_ID();
INSERT INTO question_option (question_id, sort_order, image_url)
VALUES (@q, 1, 'https://picsum.photos/seed/pickone-07a/600/800'), (@q, 2, 'https://picsum.photos/seed/pickone-07b/600/800');

INSERT INTO question (member_id, question_type, content, status, boosted_until, created_at, updated_at)
VALUES (@m3, 'IMAGE', '여행 사진 중 어느 쪽이 더 잘 나왔나요?', 'ACTIVE', NULL, @now - INTERVAL 19 HOUR, @now - INTERVAL 19 HOUR);
SET @q = LAST_INSERT_ID();
INSERT INTO question_option (question_id, sort_order, image_url)
VALUES (@q, 1, 'https://picsum.photos/seed/pickone-08a/600/800'), (@q, 2, 'https://picsum.photos/seed/pickone-08b/600/800');

COMMIT;

-- 4. 결과 확인. 회원 3, 고민 24(글형 16 + 사진형 8), 선택지 60, 상단 노출 2 가 나와야 한다.
--    hex 는 한글이 깨지지 않고 들어갔는지 보는 값이다. '샘' 은 UTF-8 로 EC8398 이고, 깨졌다면 3F('?') 가 나온다
SELECT 'members' AS item, COUNT(*) AS cnt, MIN(HEX(LEFT(nickname, 1))) AS hex FROM member WHERE id IN (@m1, @m2, @m3)
UNION ALL
SELECT 'questions_text', COUNT(*), '' FROM question WHERE member_id IN (@m1, @m2, @m3) AND question_type = 'TEXT'
UNION ALL
SELECT 'questions_image', COUNT(*), '' FROM question WHERE member_id IN (@m1, @m2, @m3) AND question_type = 'IMAGE'
UNION ALL
SELECT 'options', COUNT(*), '' FROM question_option WHERE question_id IN (SELECT id FROM question WHERE member_id IN (@m1, @m2, @m3))
UNION ALL
SELECT 'boosted', COUNT(*), '' FROM question WHERE member_id IN (@m1, @m2, @m3) AND boosted_until > NOW(6);
