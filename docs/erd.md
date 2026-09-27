# ERD (MVP v1)

![ERD](./erd.svg)

## 테이블 요약

| 테이블 | 역할 |
|---|---|
| `member` | 회원. 휴대폰 번호는 AES 암호화 + HMAC(검색용) 두 컬럼으로 저장 |
| `question` | 고민. 유형(TEXT / IMAGE), 상단 노출 만료 시각 |
| `question_option` | 선택지. TEXT는 2~4개(content), IMAGE는 정확히 2개(image_url) |
| `vote` | 투표. (member_id, question_id) 유니크로 중복 투표 방지 |
| `point_wallet` | 회원별 현재 잔액. version 컬럼으로 낙관적 락 |
| `point_ledger` | 포인트 원장. 모든 적립/차감 내역. idempotency_key 유니크로 중복 처리 방지 |
| `hide_relation` | 지인 숨김 (가입한 지인). 작성자(owner)의 고민을 target에게 숨김 |
| `hide_pending` | 지인 숨김 대기 (아직 가입 안 한 번호의 HMAC). 가입 시 hide_relation으로 이동 |
| `member_block` | 사용자 차단 |
| `report` | 고민 신고 |

## 공통 규칙

- PK: `BIGINT AUTO_INCREMENT`
- 컬럼명: snake_case
- 공통 컬럼: `created_at`, `updated_at` (필요한 테이블만)
- 삭제: 회원·고민은 소프트 삭제(`deleted_at`), 나머지는 실제 삭제
- 원본 연락처 번호는 저장하지 않음 (HMAC만 저장)

## ERD 원본 (dbdiagram.io)

```dbml
Table member {
  id bigint [pk, increment]
  email varchar(100) [not null, unique]
  password_hash varchar(255) [not null, note: 'BCrypt']
  nickname varchar(30) [not null, unique]
  phone_encrypted varchar(255) [not null, note: 'AES-GCM 암호화 (복호화 가능)']
  phone_hmac char(64) [not null, unique, note: 'HMAC-SHA256 (검색/매칭 전용)']
  hide_from_contacts boolean [not null, default: false, note: '지인에게 숨기기 ON/OFF']
  status varchar(20) [not null, note: 'ACTIVE / SUSPENDED']
  created_at datetime [not null]
  updated_at datetime [not null]
  deleted_at datetime
}

Table question {
  id bigint [pk, increment]
  member_id bigint [not null, ref: > member.id]
  question_type varchar(10) [not null, note: 'TEXT / IMAGE']
  content varchar(300) [not null, note: '고민 본문']
  status varchar(20) [not null, note: 'ACTIVE / HIDDEN(신고 누적) / CLOSED']
  boosted_until datetime [note: '포인트로 상단 노출 시 만료 시각']
  created_at datetime [not null]
  updated_at datetime [not null]
  deleted_at datetime

  indexes {
    (status, created_at) [note: '피드 조회']
    (member_id, created_at) [note: '내 고민 목록']
  }
}

Table question_option {
  id bigint [pk, increment]
  question_id bigint [not null, ref: > question.id]
  sort_order tinyint [not null, note: '1~4']
  content varchar(20) [note: 'TEXT 유형일 때']
  image_url varchar(500) [note: 'IMAGE 유형일 때']

  indexes {
    (question_id, sort_order) [unique]
  }
}

Table vote {
  id bigint [pk, increment]
  question_id bigint [not null, ref: > question.id]
  option_id bigint [not null, ref: > question_option.id]
  member_id bigint [not null, ref: > member.id]
  created_at datetime [not null]

  indexes {
    (member_id, question_id) [unique, note: '중복 투표 방지']
    (question_id, option_id) [note: '결과 집계']
  }
}

Table point_wallet {
  member_id bigint [pk, ref: - member.id]
  balance bigint [not null, default: 0]
  version bigint [not null, default: 0, note: '낙관적 락']
  updated_at datetime [not null]
}

Table point_ledger {
  id bigint [pk, increment]
  member_id bigint [not null, ref: > member.id]
  amount bigint [not null, note: '적립 +, 차감 -']
  balance_after bigint [not null, note: '처리 후 잔액 (검증용)']
  tx_type varchar(30) [not null, note: 'VOTE_REWARD / BOOST_USE / SIGNUP_BONUS ...']
  ref_type varchar(30) [note: 'VOTE / QUESTION ...']
  ref_id bigint [note: '관련 엔티티 ID']
  idempotency_key varchar(100) [not null, unique, note: '같은 요청 중복 처리 방지']
  created_at datetime [not null]

  indexes {
    (member_id, created_at) [note: '내 포인트 내역']
  }
}

Table hide_relation {
  owner_id bigint [not null, ref: > member.id, note: '숨기기를 켠 작성자']
  target_member_id bigint [not null, ref: > member.id, note: '고민을 못 보는 지인']
  created_at datetime [not null]

  indexes {
    (owner_id, target_member_id) [pk]
    target_member_id [note: '피드 조회 시 제외 대상 검색']
  }
}

Table hide_pending {
  id bigint [pk, increment]
  owner_id bigint [not null, ref: > member.id]
  phone_hmac char(64) [not null, note: '아직 가입 안 한 연락처']
  created_at datetime [not null]

  indexes {
    (owner_id, phone_hmac) [unique]
    phone_hmac [note: '신규 가입 시 매칭']
  }
}

Table member_block {
  blocker_id bigint [not null, ref: > member.id]
  blocked_id bigint [not null, ref: > member.id]
  created_at datetime [not null]

  indexes {
    (blocker_id, blocked_id) [pk]
  }
}

Table report {
  id bigint [pk, increment]
  question_id bigint [not null, ref: > question.id]
  reporter_id bigint [not null, ref: > member.id]
  reason varchar(30) [not null, note: 'ABUSE / PERSONAL_INFO / SPAM / ETC']
  status varchar(20) [not null, note: 'RECEIVED / ACCEPTED / REJECTED']
  created_at datetime [not null]

  indexes {
    (reporter_id, question_id) [unique, note: '중복 신고 방지']
  }
}
```

## 설계 메모

- **포인트는 잔액(point_wallet) + 원장(point_ledger) 이중 구조.**
  잔액은 빠른 조회용, 원장은 모든 변동의 근거. `SUM(ledger.amount) = wallet.balance` 가 항상 성립해야 함.
- **vote 에 question_id 를 중복 저장**하는 이유: option_id 만으로는 "한 고민에 한 번만 투표" 유니크 제약을 걸 수 없기 때문.
- **선택지 개수 규칙(TEXT 2~4 / IMAGE 2)은 DB가 아니라 등록 API에서 검증.**
- 추후 추가 예정: 랭킹, 알림, 카테고리, 한 줄 조언 → Flyway 버전 파일로 테이블 추가
