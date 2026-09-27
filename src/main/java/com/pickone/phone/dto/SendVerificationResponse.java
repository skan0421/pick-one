package com.pickone.phone.dto;

/** 발송 응답. 인증번호는 절대 포함하지 않는다 */
public record SendVerificationResponse(long expiresInSeconds, long cooldownSeconds) {
}
