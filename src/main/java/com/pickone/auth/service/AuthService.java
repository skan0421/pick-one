package com.pickone.auth.service;

import com.pickone.auth.dto.LoginRequest;
import com.pickone.auth.dto.SignupRequest;
import com.pickone.auth.dto.TokenResponse;
import com.pickone.auth.repository.RefreshTokenStore;
import com.pickone.auth.repository.RefreshTokenStore.RotateResult;
import com.pickone.global.error.BusinessException;
import com.pickone.global.error.ErrorCode;
import com.pickone.global.security.jwt.JwtProperties;
import com.pickone.global.security.jwt.JwtTokenProvider;
import com.pickone.global.security.jwt.JwtTokenProvider.RefreshTokenClaims;
import com.pickone.member.domain.Member;
import com.pickone.member.repository.MemberRepository;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

	private final MemberRepository memberRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtTokenProvider jwtTokenProvider;
	private final JwtProperties jwtProperties;
	private final RefreshTokenStore refreshTokenStore;

	/**
	 * 이메일 가입. 가입 직후는 PENDING_PHONE 이며 휴대폰 인증 후 ACTIVE 가 된다.
	 * 이메일·닉네임 중복은 먼저 검사하지만, 동시 요청이 둘 다 통과하면 커밋 시 유니크 위반이 나고
	 * GlobalExceptionHandler 가 제약 이름으로 같은 409 코드로 번역한다.
	 */
	@Transactional
	public TokenResponse signup(SignupRequest request) {
		if (memberRepository.existsByEmail(request.email())) {
			throw new BusinessException(ErrorCode.MEMBER_EMAIL_DUPLICATE);
		}
		if (memberRepository.existsByNickname(request.nickname())) {
			throw new BusinessException(ErrorCode.MEMBER_NICKNAME_DUPLICATE);
		}

		String passwordHash = passwordEncoder.encode(request.password());
		Member member = memberRepository.save(Member.signupByEmail(request.email(), passwordHash, request.nickname()));

		return issueTokens(member);
	}

	/** 이메일 로그인. 이메일 없음 / 소셜 전용 회원 / 비밀번호 불일치를 구분하지 않고 같은 401 로 응답한다 */
	@Transactional(readOnly = true)
	public TokenResponse login(LoginRequest request) {
		Member member = memberRepository.findByEmailAndDeletedAtIsNull(request.email())
				.orElseThrow(() -> new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS));

		if (!member.hasPassword() || !passwordEncoder.matches(request.password(), member.getPasswordHash())) {
			throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS);
		}
		if (member.isSuspended()) {
			throw new BusinessException(ErrorCode.MEMBER_SUSPENDED);
		}

		return issueTokens(member);
	}

	/**
	 * 재발급 (rotation). docs/api.md 1.3 의 판별 순서:
	 * 1) 옛 jti 가 used 에 있음 → 재사용(탈취) 으로 보고 그 회원의 refresh 전부 폐기, AUTH_REFRESH_REUSED
	 * 2) 옛 jti 가 유효 → 새 refresh 로 원자적으로 교체
	 * 3) 어디에도 없음(만료·로그아웃·위조) → AUTH_INVALID_TOKEN
	 * 새 access 토큰의 signupStatus 는 토큰이 아니라 DB 의 현재 값을 쓴다.
	 */
	@Transactional(readOnly = true)
	public TokenResponse refresh(String refreshToken) {
		RefreshTokenClaims old = jwtTokenProvider.parseRefreshToken(refreshToken);
		RefreshTokenClaims next = jwtTokenProvider.newRefreshTokenClaims(old.memberId());

		RotateResult result = refreshTokenStore.rotate(
				old.memberId(), old.jti(), old.expiresAt(),
				next.jti(), next.issuedAt(), refreshTtl());

		switch (result) {
			case REUSED -> {
				int revoked = refreshTokenStore.revokeAll(old.memberId());
				log.warn("refresh 토큰 재사용 감지: memberId={}, 폐기된 세션 {}건", old.memberId(), revoked);
				throw new BusinessException(ErrorCode.AUTH_REFRESH_REUSED);
			}
			case NOT_FOUND -> throw new BusinessException(ErrorCode.AUTH_INVALID_TOKEN);
			case ROTATED -> { }
		}

		Member member = memberRepository.findById(old.memberId())
				.filter(m -> !m.isDeleted())
				.orElseThrow(() -> new BusinessException(ErrorCode.AUTH_INVALID_TOKEN));
		if (member.isSuspended()) {
			throw new BusinessException(ErrorCode.MEMBER_SUSPENDED);
		}

		String accessToken = jwtTokenProvider.createAccessToken(member.getId(), member.getSignupStatus());
		return TokenResponse.of(member, accessToken, jwtTokenProvider.createRefreshToken(next));
	}

	/** 로그아웃: 요청의 refresh 를 폐기한다. access 는 만료(30분)까지 유효하다 */
	public void logout(Long memberId, String refreshToken) {
		RefreshTokenClaims claims = jwtTokenProvider.parseRefreshToken(refreshToken);
		if (!claims.memberId().equals(memberId)) {
			throw new BusinessException(ErrorCode.AUTH_INVALID_TOKEN);
		}
		refreshTokenStore.delete(memberId, claims.jti());
	}

	private TokenResponse issueTokens(Member member) {
		RefreshTokenClaims refresh = jwtTokenProvider.newRefreshTokenClaims(member.getId());
		refreshTokenStore.save(member.getId(), refresh.jti(), refresh.issuedAt(), refreshTtl());

		String accessToken = jwtTokenProvider.createAccessToken(member.getId(), member.getSignupStatus());
		return TokenResponse.of(member, accessToken, jwtTokenProvider.createRefreshToken(refresh));
	}

	private Duration refreshTtl() {
		return jwtProperties.refreshTokenTtl();
	}

}
