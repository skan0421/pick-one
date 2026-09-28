package com.pickone.phone.controller;

import com.pickone.auth.dto.TokenResponse;
import com.pickone.global.security.LoginMemberId;
import com.pickone.phone.dto.ConfirmVerificationRequest;
import com.pickone.phone.dto.SendVerificationRequest;
import com.pickone.phone.dto.SendVerificationResponse;
import com.pickone.phone.service.PhoneVerificationService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 로그인(PENDING_PHONE 포함) 필요. 요청 본문의 휴대폰 번호는 액세스 로그에 남기지 않는다 */
@Tag(name = "휴대폰 인증", description = "SMS OTP 발송·확인. 확인 성공 시 ACTIVE 전환과 새 토큰 (docs/api.md 3장)")
@RestController
@RequestMapping("/api/v1/phone-verifications")
@RequiredArgsConstructor
public class PhoneVerificationController {

	private final PhoneVerificationService phoneVerificationService;

	@Operation(summary = "인증번호 발송")
	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	public SendVerificationResponse send(@LoginMemberId Long memberId, @Valid @RequestBody SendVerificationRequest request) {
		return phoneVerificationService.send(memberId, request.phone());
	}

	@Operation(summary = "인증번호 확인 → ACTIVE 전환, 새 토큰 발급")
	@PostMapping("/confirm")
	public TokenResponse confirm(@LoginMemberId Long memberId, @Valid @RequestBody ConfirmVerificationRequest request) {
		return phoneVerificationService.confirm(memberId, request.phone(), request.code());
	}

}
