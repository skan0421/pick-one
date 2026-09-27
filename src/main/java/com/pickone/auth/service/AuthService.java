package com.pickone.auth.service;

import com.pickone.auth.dto.LoginRequest;
import com.pickone.auth.dto.SignupRequest;
import com.pickone.auth.dto.TokenResponse;
import com.pickone.global.error.BusinessException;
import com.pickone.global.error.ErrorCode;
import com.pickone.global.security.jwt.JwtTokenProvider;
import com.pickone.member.domain.Member;
import com.pickone.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

	private final MemberRepository memberRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtTokenProvider jwtTokenProvider;

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

		return TokenResponse.of(member, jwtTokenProvider.createAccessToken(member.getId(), member.getSignupStatus()));
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

		return TokenResponse.of(member, jwtTokenProvider.createAccessToken(member.getId(), member.getSignupStatus()));
	}

}
