package com.pickone.point.service;

import com.pickone.global.error.BusinessException;
import com.pickone.global.error.ConstraintViolations;
import com.pickone.global.error.ErrorCode;
import com.pickone.point.PointProperties;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 지갑(point_wallet) 낙관적 락 충돌 시 트랜잭션 전체를 다시 실행한다.
 *
 * 구조
 * - 호출자는 트랜잭션이 없는 서비스여야 하고, 인자로 받는 Supplier 는 @Transactional 빈의 메서드 호출이어야 한다.
 *   그래야 시도마다 새 트랜잭션(새 스냅샷, 새 영속성 컨텍스트)이 열리고, 실패한 시도는 롤백으로 완전히 버려진다.
 *   같은 트랜잭션 안에서 재시도하면 이미 rollback-only 가 된 트랜잭션에 합류하므로 금지한다 (진입 시 가드).
 * - 재시도 대상: 커밋 시 version 불일치(OptimisticLockingFailureException), InnoDB 데드락(CannotAcquireLockException).
 *   비즈니스 예외·그 밖의 제약 위반은 재시도하지 않고 그대로 던진다.
 * - 총 시도 횟수 = 1 + pickone.point.wallet-max-retries. 소진하면 409 POINT_WALLET_CONFLICT.
 * - 재시도 전에 짧은 무작위 백오프를 둔다. 같은 지갑 행을 기다리던 트랜잭션들은 이긴 쪽이 커밋하는 순간 한꺼번에 실패하고
 *   한꺼번에 다시 부딪히므로(재시도 폭주), 출발 시점을 흩어 놓으면 진 쪽 대부분이 재시도 한 번에 성공한다.
 * - 예외: idempotency_key 유니크 위반(원장, 고민)은 "같은 키의 다른 요청이 먼저 커밋됐다" 는 뜻이므로
 *   한 번만 다시 실행해 준다. 다시 실행된 트랜잭션은 첫 단계에서 그 행을 찾아 멱등 응답을 돌려준다.
 */
@Slf4j
@Component
@EnableConfigurationProperties(PointProperties.class)
public class OptimisticRetryExecutor {

	/** 상단 노출(point_ledger)과 고민 등록(question)의 Idempotency-Key 유니크 제약 */
	static final List<String> IDEMPOTENCY_KEY_CONSTRAINTS =
			List.of("uk_point_ledger_idempotency_key", "uk_question_idempotency_key");
	private static final long BACKOFF_MIN_MS = 5;
	private static final long BACKOFF_MAX_MS = 30;

	private final int maxAttempts;

	public OptimisticRetryExecutor(PointProperties properties) {
		this.maxAttempts = 1 + properties.walletMaxRetries();
	}

	public <T> T execute(String label, Supplier<T> transaction) {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("재시도 실행기는 트랜잭션 밖에서 호출해야 합니다: " + label);
		}
		boolean idempotencyRerunUsed = false;
		for (int attempt = 1; ; attempt++) {
			try {
				return transaction.get();
			}
			catch (OptimisticLockingFailureException | CannotAcquireLockException e) {
				if (attempt >= maxAttempts) {
					log.warn("낙관적 락 재시도 소진: label={}, attempts={}, cause={}", label, attempt, e.getClass().getSimpleName());
					throw new BusinessException(ErrorCode.POINT_WALLET_CONFLICT);
				}
				log.warn("낙관적 락 재시도: label={}, attempt={}/{}, cause={}", label, attempt, maxAttempts, e.getClass().getSimpleName());
				backoff(attempt);
			}
			catch (DataIntegrityViolationException e) {
				if (!idempotencyRerunUsed && violatesIdempotencyKey(e)) {
					idempotencyRerunUsed = true;
					log.info("Idempotency-Key 경합, 멱등 응답을 위해 재실행: label={}", label);
					continue;
				}
				throw e;
			}
		}
	}

	private static boolean violatesIdempotencyKey(DataIntegrityViolationException e) {
		return IDEMPOTENCY_KEY_CONSTRAINTS.stream().anyMatch(name -> ConstraintViolations.violates(e, name));
	}

	/** attempt 에 비례하는 무작위 대기 (5~30ms × attempt). 인터럽트되면 즉시 재시도한다 */
	private static void backoff(int attempt) {
		try {
			Thread.sleep(ThreadLocalRandom.current().nextLong(BACKOFF_MIN_MS, BACKOFF_MAX_MS + 1) * attempt);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}
