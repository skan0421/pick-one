package com.pickone.point.service;

import com.pickone.global.error.BusinessException;
import com.pickone.global.error.ErrorCode;
import com.pickone.point.dto.BoostResponse;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 상단 노출 API 서비스. 트랜잭션은 BoostTransaction 이 갖고, 여기서는 헤더 검증과 재시도만 한다 (VoteService 와 같은 구조).
 * Idempotency-Key 는 UUID 형식만 받는다. 서버가 만드는 투표 보상 키("vote:{id}")와 키 공간이 겹치지 않게 하기 위해서다.
 */
@Service
@RequiredArgsConstructor
public class BoostService {

	private static final Pattern UUID_PATTERN =
			Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

	private final BoostTransaction boostTransaction;
	private final OptimisticRetryExecutor retryExecutor;

	public BoostResponse boost(Long memberId, Long questionId, String idempotencyKey) {
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
		}
		String key = idempotencyKey.trim();
		if (!UUID_PATTERN.matcher(key).matches()) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Idempotency-Key 는 UUID 형식이어야 합니다.");
		}
		return retryExecutor.execute("boost question=" + questionId,
				() -> boostTransaction.execute(memberId, questionId, key));
	}

}
