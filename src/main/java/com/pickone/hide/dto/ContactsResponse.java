package com.pickone.hide.dto;

/**
 * 연락처 업로드 결과 (docs/api.md 7.1).
 * received: 정규화·중복 제거 후 처리한 번호 수 (내 번호, 휴대폰 형식이 아닌 항목 제외)
 * matchedMembers: 가입·인증을 마친 회원과 일치해 hide_relation 이 된 수, pending: 미가입이라 hide_pending 에 둔 수
 */
public record ContactsResponse(int received, int matchedMembers, int pending) {
}
