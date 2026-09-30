package com.pickone.point.service;

import com.pickone.global.idempotency.IdempotencyKeys;
import com.pickone.point.dto.BoostResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 상단 노출 API 서비스. 트랜잭션은 BoostTransaction 이 갖고, 여기서는 헤더 검증과 재시도만 한다 (VoteService 와 같은 구조).
 * Idempotency-Key 헤더는 필수이며 검증 규칙은 IdempotencyKeys 에 있다 (고민 등록과 공용).
 */
@Service
@RequiredArgsConstructor
public class BoostService {

	private final BoostTransaction boostTransaction;
	private final OptimisticRetryExecutor retryExecutor;

	public BoostResponse boost(Long memberId, Long questionId, String idempotencyKey) {
		String key = IdempotencyKeys.required(idempotencyKey);
		return retryExecutor.execute("boost question=" + questionId,
				() -> boostTransaction.execute(memberId, questionId, key));
	}

}
