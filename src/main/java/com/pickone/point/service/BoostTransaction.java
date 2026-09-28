package com.pickone.point.service;

import com.pickone.global.error.BusinessException;
import com.pickone.global.error.ErrorCode;
import com.pickone.point.PointProperties;
import com.pickone.point.domain.PointLedger;
import com.pickone.point.domain.PointWallet;
import com.pickone.point.dto.BoostResponse;
import com.pickone.point.repository.PointLedgerRepository;
import com.pickone.point.repository.PointWalletRepository;
import com.pickone.question.domain.Question;
import com.pickone.question.domain.QuestionStatus;
import com.pickone.question.repository.QuestionRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상단 노출(boost) 1건의 DB 트랜잭션 (docs/api.md 6.3).
 *
 * 1. 원장에서 Idempotency-Key 조회. 있으면 같은 회원·같은 고민의 boost 일 때만 그 행으로 재응답(차감 없음), 아니면 IDEMPOTENCY_KEY_CONFLICT
 * 2. 고민 검증: 없음·삭제 → QUESTION_NOT_FOUND, 남의 것 → FORBIDDEN, CLOSED/HIDDEN → QUESTION_CLOSED
 * 3. 지갑 차감 (읽은 잔액 기준 부족하면 POINT_INSUFFICIENT). 경합으로 음수가 되려 하면 chk_point_wallet_balance 가 막는다
 * 4. 원장 INSERT (idempotency_key = 헤더값, IDENTITY 라 즉시 실행)
 * 5. question.boosted_until = GREATEST(now, boosted_until) + 24h
 *
 * 같은 키로 동시에 두 요청이 오면: 둘 다 1단계를 통과하지만 4단계 INSERT 에서 진 쪽이 유니크 대기 → 이긴 쪽 커밋 후
 * uk_point_ledger_idempotency_key 위반. OptimisticRetryExecutor 가 이 위반을 감지해 이 메서드를 한 번 더 실행하고,
 * 그때는 1단계에서 이긴 쪽의 원장 행을 찾아 같은 응답을 돌려준다. 차감은 한 번만 일어난다.
 * 다른 키로 동시에 오면 지갑 version 충돌로 진 쪽이 재시도하고, 잔액이 모자라면 POINT_INSUFFICIENT.
 */
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(PointProperties.class)
public class BoostTransaction {

	private final QuestionRepository questionRepository;
	private final PointWalletRepository pointWalletRepository;
	private final PointLedgerRepository pointLedgerRepository;
	private final PointProperties properties;

	@Transactional
	public BoostResponse execute(Long memberId, Long questionId, String idempotencyKey) {
		Optional<PointLedger> processed = pointLedgerRepository.findByIdempotencyKey(idempotencyKey);
		if (processed.isPresent()) {
			return replay(processed.get(), memberId, questionId);
		}

		Question question = questionRepository.findById(questionId)
				.filter(q -> !q.isDeleted())
				.orElseThrow(() -> new BusinessException(ErrorCode.QUESTION_NOT_FOUND));
		if (!question.isOwnedBy(memberId)) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
		if (question.getStatus() != QuestionStatus.ACTIVE) {
			throw new BusinessException(ErrorCode.QUESTION_CLOSED);
		}
		PointWallet wallet = pointWalletRepository.findById(memberId)
				.orElseThrow(() -> new BusinessException(ErrorCode.POINT_WALLET_NOT_FOUND));

		long cost = properties.boostCost();
		wallet.use(cost);
		PointLedger ledger = pointLedgerRepository.save(
				PointLedger.boostUse(memberId, cost, wallet.getBalance(), questionId, idempotencyKey));
		question.extendBoost(LocalDateTime.now(), properties.boostDuration());

		return new BoostResponse(questionId, question.getBoostedUntil(), cost, wallet.getBalance(), ledger.getId());
	}

	/** 이미 처리된 키: 최초 처리 결과를 그대로 돌려준다. boostedUntil 은 원장에 없으므로 그 고민의 현재 값 */
	private BoostResponse replay(PointLedger ledger, Long memberId, Long questionId) {
		if (!ledger.isBoostOf(memberId, questionId)) {
			throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
		}
		Question question = questionRepository.findById(questionId)
				.orElseThrow(() -> new BusinessException(ErrorCode.QUESTION_NOT_FOUND));
		return new BoostResponse(questionId, question.getBoostedUntil(), -ledger.getAmount(), ledger.getBalanceAfter(), ledger.getId());
	}

}
