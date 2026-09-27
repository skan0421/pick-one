package com.pickone.phone.repository;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/**
 * SMS OTP 저장소 (docs/api.md 3.1).
 *
 * 키 구조
 * - otp:{memberId}:{phoneHmac}                  hash { hash, salt, attempts }  TTL 3분. 코드 원문은 저장하지 않는다
 * - otp:cooldown:{phoneHmac}                    "1"  TTL 1분. SET NX 로 같은 번호 재발송을 원자적으로 막는다
 * - otp:daily:phone:{phoneHmac}:{yyyyMMdd}       카운터, TTL 자정까지
 * - otp:daily:member:{memberId}:{yyyyMMdd}       카운터, TTL 자정까지
 *
 * 원자적 연산이 필요한 지점
 * 1) 쿨다운 획득: SET NX EX — 동시 발송 요청 중 하나만 통과
 * 2) 일일 카운터: INCR — 동시 요청이 한도를 넘겨 세지 않음
 * 3) 코드 확인: Lua 스크립트 — "비교 → 성공 시 삭제 / 실패 시 HINCRBY attempts → 한도 도달 시 삭제" 를 한 번에 처리해
 *    동시에 여러 번 틀려도 시도 횟수가 정확히 세어지고, 맞는 코드가 동시에 들어와도 한 번만 성공한다
 */
@Repository
@RequiredArgsConstructor
public class OtpStore {

	private static final String CODE_KEY = "otp:%d:%s";
	private static final String COOLDOWN_KEY = "otp:cooldown:%s";
	private static final String DAILY_PHONE_KEY = "otp:daily:phone:%s:%s";
	private static final String DAILY_MEMBER_KEY = "otp:daily:member:%d:%s";

	private static final String FIELD_HASH = "hash";
	private static final String FIELD_SALT = "salt";
	private static final String FIELD_ATTEMPTS = "attempts";

	/**
	 * KEYS[1] = 코드 키, ARGV[1] = 입력 코드의 해시, ARGV[2] = 최대 시도 횟수
	 * 반환 {상태, 누적 시도}: 상태 0 = 없음(만료), 1 = 일치(삭제됨), 2 = 시도 초과(삭제됨), 3 = 불일치
	 */
	private static final RedisScript<List> CONFIRM_SCRIPT = new DefaultRedisScript<>("""
			local stored = redis.call('HGET', KEYS[1], 'hash')
			if not stored then
			  return {0, 0}
			end
			if stored == ARGV[1] then
			  redis.call('DEL', KEYS[1])
			  return {1, 0}
			end
			local attempts = redis.call('HINCRBY', KEYS[1], 'attempts', 1)
			if attempts >= tonumber(ARGV[2]) then
			  redis.call('DEL', KEYS[1])
			  return {2, attempts}
			end
			return {3, attempts}
			""", List.class);

	private final StringRedisTemplate redisTemplate;

	public enum ConfirmStatus { NOT_FOUND, MATCHED, ATTEMPTS_EXCEEDED, MISMATCH }

	public record ConfirmResult(ConfirmStatus status, long attempts) {
	}

	public record StoredCode(String hash, String salt, long attempts) {
	}

	/** 쿨다운 키를 원자적으로 선점한다. 이미 있으면 false (다른 요청이 1분 안에 먼저 보냈다) */
	public boolean tryAcquireCooldown(String phoneHmac, Duration cooldown) {
		return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(cooldownKey(phoneHmac), "1", cooldown));
	}

	/** 번호 기준 일일 발송 카운터를 1 올리고 그 값을 돌려준다 */
	public long incrementDailyByPhone(String phoneHmac, String date, Duration ttlToMidnight) {
		return increment(DAILY_PHONE_KEY.formatted(phoneHmac, date), ttlToMidnight);
	}

	/** 회원 기준 일일 발송 카운터를 1 올리고 그 값을 돌려준다 */
	public long incrementDailyByMember(Long memberId, String date, Duration ttlToMidnight) {
		return increment(DAILY_MEMBER_KEY.formatted(memberId, date), ttlToMidnight);
	}

	/** 새 코드(의 해시)를 저장한다. 같은 회원·번호의 이전 코드는 덮어쓴다 */
	public void saveCode(Long memberId, String phoneHmac, String hash, String salt, Duration ttl) {
		String key = codeKey(memberId, phoneHmac);
		redisTemplate.delete(key);
		redisTemplate.opsForHash().putAll(key, Map.of(FIELD_HASH, hash, FIELD_SALT, salt, FIELD_ATTEMPTS, "0"));
		redisTemplate.expire(key, ttl);
	}

	public Optional<StoredCode> find(Long memberId, String phoneHmac) {
		Map<Object, Object> entries = redisTemplate.opsForHash().entries(codeKey(memberId, phoneHmac));
		if (entries == null || entries.isEmpty() || entries.get(FIELD_HASH) == null) {
			return Optional.empty();
		}
		long attempts = Long.parseLong(String.valueOf(entries.getOrDefault(FIELD_ATTEMPTS, "0")));
		return Optional.of(new StoredCode((String) entries.get(FIELD_HASH), (String) entries.get(FIELD_SALT), attempts));
	}

	/** 입력 코드의 해시를 저장된 해시와 원자적으로 비교한다 */
	@SuppressWarnings("unchecked")
	public ConfirmResult confirm(Long memberId, String phoneHmac, String candidateHash, int maxAttempts) {
		List<Long> result = (List<Long>) redisTemplate.execute(CONFIRM_SCRIPT,
				List.of(codeKey(memberId, phoneHmac)), candidateHash, String.valueOf(maxAttempts));
		if (result == null || result.size() < 2) {
			return new ConfirmResult(ConfirmStatus.NOT_FOUND, 0);
		}
		long attempts = result.get(1);
		return switch (result.get(0).intValue()) {
			case 1 -> new ConfirmResult(ConfirmStatus.MATCHED, attempts);
			case 2 -> new ConfirmResult(ConfirmStatus.ATTEMPTS_EXCEEDED, attempts);
			case 3 -> new ConfirmResult(ConfirmStatus.MISMATCH, attempts);
			default -> new ConfirmResult(ConfirmStatus.NOT_FOUND, attempts);
		};
	}

	public void deleteCode(Long memberId, String phoneHmac) {
		redisTemplate.delete(codeKey(memberId, phoneHmac));
	}

	private long increment(String key, Duration ttl) {
		Long count = redisTemplate.opsForValue().increment(key);
		if (count != null && count == 1L) {
			redisTemplate.expire(key, ttl);
		}
		return count == null ? 0 : count;
	}

	public static String codeKey(Long memberId, String phoneHmac) {
		return CODE_KEY.formatted(memberId, phoneHmac);
	}

	public static String cooldownKey(String phoneHmac) {
		return COOLDOWN_KEY.formatted(phoneHmac);
	}

	public static String dailyPhoneKey(String phoneHmac, String date) {
		return DAILY_PHONE_KEY.formatted(phoneHmac, date);
	}

}
