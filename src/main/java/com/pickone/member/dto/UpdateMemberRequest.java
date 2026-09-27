package com.pickone.member.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 내 정보 수정. 지금은 닉네임만 바꿀 수 있다 */
public record UpdateMemberRequest(
		@NotBlank(message = "닉네임을 입력해 주세요.")
		@Size(min = 2, max = 30, message = "닉네임은 2~30자여야 합니다.")
		@Pattern(regexp = "^[가-힣A-Za-z0-9]+$", message = "닉네임은 한글, 영문, 숫자만 사용할 수 있습니다.")
		String nickname
) {
}
