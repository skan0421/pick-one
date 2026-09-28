package com.pickone.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 동시성 테스트 헬퍼. 스레드를 모두 만든 뒤 래치로 동시에 출발시켜 실제 경합을 만든다.
 * 요청 스레드마다 DB 커넥션 1개를 트랜잭션 동안 점유하므로 HikariCP 기본 풀(10)보다 적게 써야 한다.
 */
public final class ConcurrencyTestSupport {

	private static final long TIMEOUT_SECONDS = 60;

	private ConcurrencyTestSupport() {
	}

	public static <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<T>> futures = new ArrayList<>();
			for (Callable<T> task : tasks) {
				futures.add(executor.submit(() -> {
					start.await();
					return task.call();
				}));
			}
			start.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> future : futures) {
				results.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
			}
			return results;
		}
		finally {
			executor.shutdownNow();
		}
	}

}
