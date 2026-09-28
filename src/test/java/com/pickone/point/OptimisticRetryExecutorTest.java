package com.pickone.point;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pickone.global.error.BusinessException;
import com.pickone.global.error.ErrorCode;
import com.pickone.point.service.OptimisticRetryExecutor;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 재시도 실행기 단위 테스트: 시도 횟수, 재시도 대상 예외, 트랜잭션 안 호출 가드 (스프링 컨텍스트 없음) */
class OptimisticRetryExecutorTest {

	private static final int MAX_RETRIES = 3;

	private final OptimisticRetryExecutor executor =
			new OptimisticRetryExecutor(new PointProperties(null, null, null, null, MAX_RETRIES));

	@AfterEach
	void clearTransactionFlag() {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			TransactionSynchronizationManager.setActualTransactionActive(false);
		}
	}

	@Test
	void 낙관적_락_충돌이_계속되면_총_1_더하기_재시도_횟수만큼_시도하고_POINT_WALLET_CONFLICT() {
		AtomicInteger attempts = new AtomicInteger();

		assertThatThrownBy(() -> executor.execute("test", () -> {
			attempts.incrementAndGet();
			throw new ObjectOptimisticLockingFailureException("point_wallet", 1L);
		}))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.POINT_WALLET_CONFLICT);
		assertThat(attempts.get()).isEqualTo(1 + MAX_RETRIES);
	}

	@Test
	void 충돌_후_두_번째_시도가_성공하면_그_결과를_돌려준다() {
		AtomicInteger attempts = new AtomicInteger();

		String result = executor.execute("test", () -> {
			if (attempts.incrementAndGet() == 1) {
				throw new ObjectOptimisticLockingFailureException("point_wallet", 1L);
			}
			return "ok";
		});

		assertThat(result).isEqualTo("ok");
		assertThat(attempts.get()).isEqualTo(2);
	}

	@Test
	void 데드락도_재시도_대상이다() {
		AtomicInteger attempts = new AtomicInteger();

		String result = executor.execute("test", () -> {
			if (attempts.incrementAndGet() == 1) {
				throw new CannotAcquireLockException("Deadlock found when trying to get lock");
			}
			return "ok";
		});

		assertThat(result).isEqualTo("ok");
		assertThat(attempts.get()).isEqualTo(2);
	}

	@Test
	void 비즈니스_예외는_재시도하지_않고_그대로_던진다() {
		AtomicInteger attempts = new AtomicInteger();

		assertThatThrownBy(() -> executor.execute("test", () -> {
			attempts.incrementAndGet();
			throw new BusinessException(ErrorCode.POINT_INSUFFICIENT);
		}))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.POINT_INSUFFICIENT);
		assertThat(attempts.get()).isEqualTo(1);
	}

	@Test
	void 일반_제약_위반은_재시도하지_않는다() {
		AtomicInteger attempts = new AtomicInteger();

		assertThatThrownBy(() -> executor.execute("test", () -> {
			attempts.incrementAndGet();
			throw violation("uk_vote_member_id_question_id");
		})).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(attempts.get()).isEqualTo(1);
	}

	@Test
	void Idempotency_Key_유니크_위반은_한_번만_재실행한다() {
		AtomicInteger attempts = new AtomicInteger();

		String result = executor.execute("test", () -> {
			if (attempts.incrementAndGet() == 1) {
				throw violation("point_ledger.uk_point_ledger_idempotency_key");
			}
			return "replayed";
		});
		assertThat(result).isEqualTo("replayed");
		assertThat(attempts.get()).isEqualTo(2);

		// 두 번 연속 나면 버그(같은 트랜잭션에서 키 조회를 빠뜨림)이므로 그대로 던진다
		AtomicInteger again = new AtomicInteger();
		assertThatThrownBy(() -> executor.execute("test", () -> {
			again.incrementAndGet();
			throw violation("uk_point_ledger_idempotency_key");
		})).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(again.get()).isEqualTo(2);
	}

	@Test
	void 트랜잭션_안에서_호출하면_IllegalStateException() {
		TransactionSynchronizationManager.setActualTransactionActive(true);

		assertThatThrownBy(() -> executor.execute("test", () -> "never"))
				.isInstanceOf(IllegalStateException.class);
	}

	private static DataIntegrityViolationException violation(String constraintName) {
		return new DataIntegrityViolationException("could not execute statement",
				new ConstraintViolationException("dup", new SQLException("Duplicate entry", "23000", 1062), constraintName));
	}

}
