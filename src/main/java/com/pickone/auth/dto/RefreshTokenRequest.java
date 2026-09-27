package com.pickone.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** 재발급·로그아웃 요청 본문 */
public record RefreshTokenRequest(
		@NotBlank(message = "refreshToken 을 입력해 주세요.")
		String refreshToken
) {
}
