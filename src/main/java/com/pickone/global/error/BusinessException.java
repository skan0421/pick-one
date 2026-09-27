package com.pickone.global.error;

import lombok.Getter;

/** 비즈니스 규칙 위반. ErrorCode 가 HTTP 상태와 기본 메시지를 결정하고, 필요하면 메시지를 덧붙일 수 있다 */
@Getter
public class BusinessException extends RuntimeException {

	private final ErrorCode errorCode;

	public BusinessException(ErrorCode errorCode) {
		super(errorCode.getMessage());
		this.errorCode = errorCode;
	}

	/** 기본 메시지 대신 상황 정보를 담은 메시지를 쓸 때 (예: 남은 시도 횟수) */
	public BusinessException(ErrorCode errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}

}
