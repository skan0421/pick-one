package com.pickone.phone.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ConfirmVerificationRequest(
		@NotBlank(message = "휴대폰 번호를 입력해 주세요.")
		String phone,

		@NotBlank(message = "인증번호를 입력해 주세요.")
		@Pattern(regexp = "^\\d{6}$", message = "인증번호는 숫자 6자리입니다.")
		String code
) {
}
