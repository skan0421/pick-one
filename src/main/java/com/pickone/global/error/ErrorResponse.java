package com.pickone.global.error;

import java.util.List;

/**
 * 공통 에러 응답. docs/api.md 1.4 형식.
 * errors 는 VALIDATION_ERROR 일 때만 채워지며 null 이면 JSON 에서 생략된다 (application.yml 의 default-property-inclusion).
 */
public record ErrorResponse(String code, String message, List<FieldError> errors) {

	public static ErrorResponse of(ErrorCode errorCode) {
		return new ErrorResponse(errorCode.name(), errorCode.getMessage(), null);
	}

	public static ErrorResponse of(ErrorCode errorCode, List<FieldError> errors) {
		return new ErrorResponse(errorCode.name(), errorCode.getMessage(), errors);
	}

	public record FieldError(String field, String reason) {
	}

}
