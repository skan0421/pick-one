package com.pickone.global.error;

import java.util.Arrays;
import java.util.Optional;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * API 에러 코드. docs/api.md 1.4 의 표와 1:1 로 맞춘다.
 * constraintName 이 있는 코드는 DB 유니크 제약 위반을 409 로 번역할 때 쓴다.
 */
@Getter
public enum ErrorCode {

	// 공통
	VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
	FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
	RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),

	// 인증
	AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
	AUTH_INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다."),
	AUTH_EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "토큰이 만료되었습니다."),
	AUTH_REFRESH_REUSED(HttpStatus.UNAUTHORIZED, "이미 사용된 토큰입니다. 다시 로그인해 주세요."),
	AUTH_CODE_INVALID(HttpStatus.UNAUTHORIZED, "로그인 코드가 유효하지 않습니다."),
	SIGNUP_INCOMPLETE(HttpStatus.FORBIDDEN, "휴대폰 인증을 완료해야 이용할 수 있습니다."),

	// 회원
	MEMBER_EMAIL_DUPLICATE(HttpStatus.CONFLICT, "이미 가입된 이메일입니다.", "uk_member_email"),
	MEMBER_NICKNAME_DUPLICATE(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다.", "uk_member_nickname"),
	MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다."),
	MEMBER_SUSPENDED(HttpStatus.FORBIDDEN, "정지된 회원입니다."),

	// 휴대폰 인증
	PHONE_INVALID_FORMAT(HttpStatus.BAD_REQUEST, "휴대폰 번호 형식이 올바르지 않습니다."),
	PHONE_ALREADY_REGISTERED(HttpStatus.CONFLICT, "이미 등록된 휴대폰 번호입니다.", "uk_member_phone_hmac"),
	PHONE_ALREADY_VERIFIED(HttpStatus.CONFLICT, "이미 휴대폰 인증을 완료했습니다."),
	OTP_INVALID(HttpStatus.BAD_REQUEST, "인증번호가 올바르지 않습니다."),
	OTP_EXPIRED(HttpStatus.BAD_REQUEST, "인증번호가 만료되었습니다. 다시 요청해 주세요."),
	OTP_ATTEMPT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "인증 시도 횟수를 초과했습니다. 다시 요청해 주세요."),
	OTP_COOLDOWN(HttpStatus.TOO_MANY_REQUESTS, "잠시 후 다시 요청해 주세요."),
	OTP_DAILY_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "오늘 인증번호 발송 한도를 초과했습니다."),

	// 고민
	QUESTION_NOT_FOUND(HttpStatus.NOT_FOUND, "고민을 찾을 수 없습니다."),
	QUESTION_OPTION_COUNT_INVALID(HttpStatus.BAD_REQUEST, "선택지 개수가 올바르지 않습니다."),
	QUESTION_OPTION_TYPE_MISMATCH(HttpStatus.BAD_REQUEST, "고민 유형과 선택지 형식이 맞지 않습니다."),
	QUESTION_CLOSED(HttpStatus.CONFLICT, "종료된 고민입니다."),

	// 투표
	VOTE_ALREADY_VOTED(HttpStatus.CONFLICT, "이미 투표한 고민입니다.", "uk_vote_member_id_question_id"),
	VOTE_OPTION_MISMATCH(HttpStatus.BAD_REQUEST, "해당 고민의 선택지가 아닙니다."),
	VOTE_OWN_QUESTION(HttpStatus.FORBIDDEN, "자신의 고민에는 투표할 수 없습니다."),
	RESULT_NOT_ALLOWED(HttpStatus.FORBIDDEN, "투표한 사람만 결과를 볼 수 있습니다."),

	// 포인트
	POINT_INSUFFICIENT(HttpStatus.CONFLICT, "포인트가 부족합니다."),
	POINT_WALLET_NOT_FOUND(HttpStatus.CONFLICT, "포인트 지갑이 없습니다."),
	IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "Idempotency-Key 헤더가 필요합니다."),
	IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, "같은 키로 다른 요청이 처리되었습니다."),

	// 차단·신고
	BLOCK_SELF(HttpStatus.BAD_REQUEST, "자기 자신을 차단할 수 없습니다."),
	BLOCK_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 차단한 사용자입니다."),
	REPORT_DUPLICATE(HttpStatus.CONFLICT, "이미 신고한 고민입니다.", "uk_report_reporter_id_question_id"),
	REPORT_OWN_QUESTION(HttpStatus.BAD_REQUEST, "자신의 고민은 신고할 수 없습니다."),

	// 지인 숨기기
	CONTACTS_TOO_MANY(HttpStatus.BAD_REQUEST, "연락처는 최대 5,000건까지 업로드할 수 있습니다.");

	private final HttpStatus status;
	private final String message;
	/** 이 에러로 번역할 DB 유니크 제약 이름 (없으면 null) */
	private final String constraintName;

	ErrorCode(HttpStatus status, String message) {
		this(status, message, null);
	}

	ErrorCode(HttpStatus status, String message, String constraintName) {
		this.status = status;
		this.message = message;
		this.constraintName = constraintName;
	}

	/**
	 * 위반된 제약 이름으로 에러 코드를 찾는다.
	 * MySQL/MariaDB 는 "member.uk_member_email" 처럼 테이블 접두사를 붙일 수 있어 endsWith 로 비교한다.
	 */
	public static Optional<ErrorCode> fromConstraintName(String violatedConstraint) {
		if (violatedConstraint == null) {
			return Optional.empty();
		}
		String name = violatedConstraint.toLowerCase();
		return Arrays.stream(values())
				.filter(code -> code.constraintName != null && name.endsWith(code.constraintName))
				.findFirst();
	}

}
