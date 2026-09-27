package com.pickone.global.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ErrorCodeTest {

	@Test
	void 제약_이름으로_에러_코드를_찾는다() {
		assertThat(ErrorCode.fromConstraintName("uk_member_email")).contains(ErrorCode.MEMBER_EMAIL_DUPLICATE);
		assertThat(ErrorCode.fromConstraintName("uk_member_nickname")).contains(ErrorCode.MEMBER_NICKNAME_DUPLICATE);
	}

	@Test
	void 테이블_접두사가_붙은_제약_이름도_찾는다() {
		assertThat(ErrorCode.fromConstraintName("member.uk_member_email")).contains(ErrorCode.MEMBER_EMAIL_DUPLICATE);
		assertThat(ErrorCode.fromConstraintName("MEMBER.UK_MEMBER_NICKNAME")).contains(ErrorCode.MEMBER_NICKNAME_DUPLICATE);
	}

	@Test
	void 모르는_제약이나_null_은_빈_값이다() {
		assertThat(ErrorCode.fromConstraintName("fk_question_member")).isEmpty();
		assertThat(ErrorCode.fromConstraintName(null)).isEmpty();
	}

}
