package com.pickone.global.error;

import lombok.Getter;

/** 비즈니스 규칙 위반. ErrorCode 가 HTTP 상태와 메시지를 결정한다. */
@Getter
public class BusinessException extends RuntimeException {

	private final ErrorCode errorCode;

	public BusinessException(ErrorCode errorCode) {
		super(errorCode.getMessage());
		this.errorCode = errorCode;
	}

}
