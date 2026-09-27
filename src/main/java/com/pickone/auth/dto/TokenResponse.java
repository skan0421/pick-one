package com.pickone.auth.dto;

import com.pickone.member.domain.Member;
import com.pickone.member.domain.SignupStatus;

/**
 * 가입/로그인 응답 (docs/api.md 2.2, 2.3).
 * refreshToken 은 Refresh 토큰 구현 단계에서 추가한다.
 */
public record TokenResponse(MemberSummary member, String accessToken) {

	public static TokenResponse of(Member member, String accessToken) {
		return new TokenResponse(
				new MemberSummary(member.getId(), member.getNickname(), member.getSignupStatus()),
				accessToken);
	}

	public record MemberSummary(Long id, String nickname, SignupStatus signupStatus) {
	}

}
