package com.pickone.phone.dto;

import jakarta.validation.constraints.NotBlank;

public record SendVerificationRequest(
		@NotBlank(message = "휴대폰 번호를 입력해 주세요.")
		String phone
) {
}
