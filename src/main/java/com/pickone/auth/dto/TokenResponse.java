package com.pickone.auth.dto;

import com.pickone.member.domain.Member;
import com.pickone.member.domain.SignupStatus;

/** 가입/로그인/재발급 응답 (docs/api.md 2.2 ~ 2.4) */
public record TokenResponse(MemberSummary member, String accessToken, String refreshToken) {

	public static TokenResponse of(Member member, String accessToken, String refreshToken) {
		return new TokenResponse(
				new MemberSummary(member.getId(), member.getNickname(), member.getSignupStatus()),
				accessToken, refreshToken);
	}

	public record MemberSummary(Long id, String nickname, SignupStatus signupStatus) {
	}

}
