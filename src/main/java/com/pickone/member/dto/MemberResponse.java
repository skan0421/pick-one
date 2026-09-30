package com.pickone.member.dto;

import com.pickone.member.domain.Member;
import com.pickone.member.domain.SignupStatus;
import java.time.LocalDateTime;

/**
 * 내 정보 응답 (docs/api.md 2.7). 휴대폰 번호는 내려주지 않는다.
 * provider 는 소셜 로그인 구현 전이라 항상 EMAIL.
 * pointBalance 는 지갑 잔액이며 GET /points/balance 의 balance 와 같은 값이다. 지갑이 없으면(PENDING_PHONE) 0.
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

	public static MemberResponse of(Member member, long pointBalance) {
		return new MemberResponse(
				member.getId(),
				member.getEmail(),
				member.getNickname(),
				"EMAIL",
				member.getSignupStatus(),
				member.isSignupCompleted(),
				member.isHideFromContacts(),
				pointBalance,
				member.getCreatedAt());
	}

}
