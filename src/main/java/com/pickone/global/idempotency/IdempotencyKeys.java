package com.pickone.global.idempotency;

import com.pickone.global.error.BusinessException;
import com.pickone.global.error.ErrorCode;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Idempotency-Key 헤더 검증 (docs/api.md 1.6). 상단 노출과 고민 등록이 함께 쓴다.
 * 키는 UUID 형식만 받는다. 서버가 만드는 투표 보상 키("vote:{id}")와 키 공간이 겹치지 않게 하기 위해서다.
 */
public final class IdempotencyKeys {

	public static final String HEADER = "Idempotency-Key";

	private static final Pattern UUID_PATTERN =
			Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

	private IdempotencyKeys() {
	}

	/** 헤더가 필수인 API. 없음·공백 → IDEMPOTENCY_KEY_REQUIRED, UUID 형식이 아니면 VALIDATION_ERROR */
	public static String required(String header) {
		return optional(header).orElseThrow(() -> new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED));
	}

	/** 헤더가 선택인 API. 없음·공백이면 비어 있고, 값이 있는데 UUID 형식이 아니면 VALIDATION_ERROR */
	public static Optional<String> optional(String header) {
		if (header == null || header.isBlank()) {
			return Optional.empty();
		}
		String key = header.trim();
		if (!UUID_PATTERN.matcher(key).matches()) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Idempotency-Key 는 UUID 형식이어야 합니다.");
		}
		return Optional.of(key);
	}

}
