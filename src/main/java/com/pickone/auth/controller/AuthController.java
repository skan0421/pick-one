package com.pickone.auth.controller;

import com.pickone.auth.dto.LoginRequest;
import com.pickone.auth.dto.RefreshTokenRequest;
import com.pickone.auth.dto.SignupRequest;
import com.pickone.auth.dto.TokenResponse;
import com.pickone.auth.service.AuthService;
import com.pickone.global.security.LoginMemberId;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

	private final AuthService authService;

	@PostMapping("/signup")
	@ResponseStatus(HttpStatus.CREATED)
	public TokenResponse signup(@Valid @RequestBody SignupRequest request) {
		return authService.signup(request);
	}

	@PostMapping("/login")
	public TokenResponse login(@Valid @RequestBody LoginRequest request) {
		return authService.login(request);
	}

	/** 공개 경로. access 가 만료된 상태에서 호출되므로 본문의 refresh 로만 인증한다 */
	@PostMapping("/refresh")
	public TokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
		return authService.refresh(request.refreshToken());
	}

	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void logout(@LoginMemberId Long memberId, @Valid @RequestBody RefreshTokenRequest request) {
		authService.logout(memberId, request.refreshToken());
	}

}
