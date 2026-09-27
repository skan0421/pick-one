package com.pickone.member.domain;

/** 가입 진행 상태. 휴대폰 인증을 마치면 ACTIVE 가 된다. */
public enum SignupStatus {
	PENDING_PHONE,
	ACTIVE
}
