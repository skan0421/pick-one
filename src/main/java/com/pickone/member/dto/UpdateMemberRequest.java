package com.pickone.member.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 내 정보 수정 (docs/api.md 2.8). 보낸 필드만 바꾼다. 지금 바꿀 수 있는 필드는 닉네임뿐이다.
 * nickname 이 없으면(null) 검증하지 않고 바꾸지도 않는다. 보냈다면 빈 문자열도 아래 규칙으로 거절된다
 * (@Size, @Pattern 은 null 을 통과시키므로 필수 조건 없이 형식만 검사한다)
 */
public record UpdateMemberRequest(
		@Size(min = 2, max = 30, message = "닉네임은 2~30자여야 합니다.")
		@Pattern(regexp = "^[가-힣A-Za-z0-9]+$", message = "닉네임은 한글, 영문, 숫자만 사용할 수 있습니다.")
		String nickname
) {
}
