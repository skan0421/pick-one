package com.pickone.auth.controller;

import com.pickone.auth.dto.LoginRequest;
import com.pickone.auth.dto.RefreshTokenRequest;
import com.pickone.auth.dto.SignupRequest;
import com.pickone.auth.dto.TokenResponse;
import com.pickone.auth.service.AuthService;
import com.pickone.global.security.LoginMemberId;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "인증", description = "이메일 가입·로그인·토큰 재발급(rotation)·로그아웃 (docs/api.md 2장)")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

	private final AuthService authService;

	@Operation(summary = "이메일 가입 (PENDING_PHONE 상태로 시작)")
	@SecurityRequirements
	@PostMapping("/signup")
	@ResponseStatus(HttpStatus.CREATED)
	public TokenResponse signup(@Valid @RequestBody SignupRequest request) {
		return authService.signup(request);
	}

	@Operation(summary = "이메일 로그인")
	@SecurityRequirements
	@PostMapping("/login")
	public TokenResponse login(@Valid @RequestBody LoginRequest request) {
		return authService.login(request);
	}

	/** 공개 경로. access 가 만료된 상태에서 호출되므로 본문의 refresh 로만 인증한다 */
	@Operation(summary = "토큰 재발급 (rotation, 재사용 감지)")
	@SecurityRequirements
	@PostMapping("/refresh")
	public TokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
		return authService.refresh(request.refreshToken());
	}

	@Operation(summary = "로그아웃 (refresh 폐기)")
	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void logout(@LoginMemberId Long memberId, @Valid @RequestBody RefreshTokenRequest request) {
		authService.logout(memberId, request.refreshToken());
	}

}
