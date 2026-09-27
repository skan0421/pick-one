package com.pickone.member.dto;

import com.pickone.member.domain.Member;
import com.pickone.member.domain.SignupStatus;
import java.time.LocalDateTime;

/**
 * 내 정보 응답 (docs/api.md 2.7). 휴대폰 번호는 내려주지 않는다.
 * provider 는 소셜 로그인 구현 전이라 항상 EMAIL, pointBalance 는 지갑 구현 전이라 0.
 */
public record MemberResponse(
		Long id,
		String email,
		String nickname,
		String provider,
		SignupStatus signupStatus,
		boolean phoneVerified,
		boolean hideFromContacts,
		long pointBalance,
		LocalDateTime createdAt
) {

	public static MemberResponse from(Member member) {
		return new MemberResponse(
				member.getId(),
				member.getEmail(),
				member.getNickname(),
				"EMAIL",
				member.getSignupStatus(),
				member.isSignupCompleted(),
				member.isHideFromContacts(),
				0L,
				member.getCreatedAt());
	}

}
