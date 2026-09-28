package com.pickone.global.error;

import org.hibernate.exception.ConstraintViolationException;

/** 예외 cause 체인에서 위반된 DB 제약 이름을 꺼낸다 (GlobalExceptionHandler, 재시도 실행기 공용) */
public final class ConstraintViolations {

	private ConstraintViolations() {
	}

	/**
	 * Hibernate ConstraintViolationException#getConstraintName().
	 * MariaDB 방언은 유니크(1062)·FK(1452)·CHECK(4025) 위반에서 모두 이름을 추출한다.
	 * (CHECK 는 MariaDB Connector/J 가 메시지 앞에 "(conn=N) " 를 붙여 주는 덕에 " CONSTRAINT `" 템플릿과 맞는다 — 드라이버 의존)
	 */
	public static String constraintName(Throwable e) {
		Throwable cause = e;
		while (cause != null) {
			if (cause instanceof ConstraintViolationException cve) {
				return cve.getConstraintName();
			}
			cause = cause.getCause();
		}
		return null;
	}

	/** 위반된 제약이 주어진 이름인지 (테이블 접두사 "point_ledger.uk_..." 허용) */
	public static boolean violates(Throwable e, String constraintName) {
		String name = constraintName(e);
		return name != null && name.toLowerCase().endsWith(constraintName);
	}

}
