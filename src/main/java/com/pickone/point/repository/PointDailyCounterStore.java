package com.pickone.point.repository;

import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 일일 투표 적립 합계 캐시 (docs/api.md 1.7, 11.3).
 * 키: point:daily:{memberId}:{yyyyMMdd}, TTL 자정까지.
 * 적립 상한 판정의 근거는 원장(SUM)이고 이 카운터는 GET /points/balance 의 todayEarned 표시용이다.
 * 투표 트랜잭션이 커밋된 뒤에만 증가시키므로 롤백된 적립이 세어지지 않는다. 유실되면 원장에서 다시 채운다.
 */
@Repository
@RequiredArgsConstructor
public class PointDailyCounterStore {

	private static final String KEY = "point:daily:%d:%s";

	private final StringRedisTemplate redisTemplate;

	public Optional<Long> get(Long memberId, String date) {
		String value = redisTemplate.opsForValue().get(key(memberId, date));
		return value == null ? Optional.empty() : Optional.of(Long.parseLong(value));
	}

	/** INCRBY. 처음 만들어지는 키에만 TTL 을 건다 */
	public long increment(Long memberId, String date, long amount, Duration ttl) {
		String key = key(memberId, date);
		Long value = redisTemplate.opsForValue().increment(key, amount);
		if (value != null && value == amount) {
			redisTemplate.expire(key, ttl);
		}
		return value == null ? 0 : value;
	}

	public void set(Long memberId, String date, long value, Duration ttl) {
		redisTemplate.opsForValue().set(key(memberId, date), String.valueOf(value), ttl);
	}

	public static String key(Long memberId, String date) {
		return KEY.formatted(memberId, date);
	}

}
