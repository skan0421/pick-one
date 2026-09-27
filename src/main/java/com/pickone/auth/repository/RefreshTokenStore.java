package com.pickone.auth.repository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/**
 * Refresh 토큰 저장소 (docs/api.md 1.3).
 *
 * 키 구조
 * - refresh:{memberId}:{jti} = 발급 시각(epoch 초). TTL = refresh 수명(14일). 존재하면 유효한 refresh
 * - refresh:used:{jti}      = memberId. TTL = 그 토큰의 원래 만료 시각까지. rotation 으로 교체된 refresh 의 흔적
 *
 * 재발급은 Lua 스크립트 하나로 "옛 키 삭제 + used 표시 + 새 키 저장" 을 원자적으로 처리하므로
 * 같은 refresh 로 동시에 요청이 와도 정확히 하나만 성공한다.
 */
@Repository
@RequiredArgsConstructor
public class RefreshTokenStore {

	private static final String ACTIVE_KEY = "refresh:%d:%s";
	private static final String USED_KEY = "refresh:used:%s";
	private static final String ACTIVE_PATTERN = "refresh:%d:*";

	/**
	 * KEYS[1] = 옛 active 키, KEYS[2] = 옛 jti 의 used 키, KEYS[3] = 새 active 키
	 * ARGV[1] = memberId, ARGV[2] = used 키 TTL(초), ARGV[3] = 새 키 TTL(초), ARGV[4] = 새 키 값
	 * 반환: 1 = 교체 성공, 2 = 이미 사용된 refresh (재사용), 0 = 존재하지 않음
	 */
	private static final RedisScript<Long> ROTATE_SCRIPT = new DefaultRedisScript<>("""
			if redis.call('EXISTS', KEYS[1]) == 1 then
			  redis.call('DEL', KEYS[1])
			  redis.call('SET', KEYS[2], ARGV[1], 'EX', ARGV[2])
			  redis.call('SET', KEYS[3], ARGV[4], 'EX', ARGV[3])
			  return 1
			end
			if redis.call('EXISTS', KEYS[2]) == 1 then
			  return 2
			end
			return 0
			""", Long.class);

	private final StringRedisTemplate redisTemplate;

	public enum RotateResult { ROTATED, REUSED, NOT_FOUND }

	/** 로그인·가입 시 새 refresh 를 등록한다 */
	public void save(Long memberId, String jti, Instant issuedAt, Duration ttl) {
		redisTemplate.opsForValue().set(activeKey(memberId, jti), String.valueOf(issuedAt.getEpochSecond()), ttl);
	}

	/**
	 * 옛 refresh 를 새 refresh 로 원자적으로 교체한다.
	 * 옛 jti 는 원래 만료 시각까지 used 로 남겨 재사용을 감지한다.
	 */
	public RotateResult rotate(Long memberId, String oldJti, Instant oldExpiresAt,
			String newJti, Instant newIssuedAt, Duration newTtl) {
		long usedTtlSeconds = Math.max(1, Duration.between(Instant.now(), oldExpiresAt).getSeconds());
		Long result = redisTemplate.execute(ROTATE_SCRIPT,
				List.of(activeKey(memberId, oldJti), usedKey(oldJti), activeKey(memberId, newJti)),
				String.valueOf(memberId),
				String.valueOf(usedTtlSeconds),
				String.valueOf(Math.max(1, newTtl.getSeconds())),
				String.valueOf(newIssuedAt.getEpochSecond()));
		if (result == null || result == 0) {
			return RotateResult.NOT_FOUND;
		}
		return result == 1 ? RotateResult.ROTATED : RotateResult.REUSED;
	}

	/** 로그아웃: 해당 refresh 만 삭제한다 (used 로 옮기지 않으므로 이후 재사용은 NOT_FOUND) */
	public void delete(Long memberId, String jti) {
		redisTemplate.delete(activeKey(memberId, jti));
	}

	/** 재사용 감지 시: 그 회원의 모든 refresh 를 폐기한다 (모든 기기 로그아웃) */
	public int revokeAll(Long memberId) {
		List<String> keys = new ArrayList<>();
		ScanOptions options = ScanOptions.scanOptions().match(ACTIVE_PATTERN.formatted(memberId)).count(100).build();
		try (Cursor<String> cursor = redisTemplate.scan(options)) {
			cursor.forEachRemaining(keys::add);
		}
		if (keys.isEmpty()) {
			return 0;
		}
		Long deleted = redisTemplate.delete(keys);
		return deleted == null ? 0 : deleted.intValue();
	}

	public boolean exists(Long memberId, String jti) {
		return Boolean.TRUE.equals(redisTemplate.hasKey(activeKey(memberId, jti)));
	}

	private static String activeKey(Long memberId, String jti) {
		return ACTIVE_KEY.formatted(memberId, jti);
	}

	private static String usedKey(String jti) {
		return USED_KEY.formatted(jti);
	}

}
