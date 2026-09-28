package com.pickone.hide.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 연락처 업로드 (docs/api.md 7.1). 빈 목록이면 기존 숨김 대상을 모두 지운다.
 * 이 본문의 번호는 로그·DB 어디에도 원문으로 남기지 않는다 (toString 도 번호를 내지 않는다).
 */
public record ContactsRequest(@NotNull List<String> phones) {

	@Override
	public String toString() {
		return "ContactsRequest[phones=" + (phones == null ? 0 : phones.size()) + "건]";
	}

}
