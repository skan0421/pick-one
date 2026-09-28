package com.pickone.phone.service;

import com.pickone.auth.dto.TokenResponse;
import com.pickone.auth.service.AuthService;
import com.pickone.global.crypto.PhoneCipher;
import com.pickone.global.crypto.PhoneNumber;
import com.pickone.global.error.BusinessException;
import com.pickone.global.error.ErrorCode;
import com.pickone.global.time.KstDates;
import com.pickone.member.domain.Member;
import com.pickone.member.repository.MemberRepository;
import com.pickone.phone.OtpProperties;
import com.pickone.phone.dto.SendVerificationResponse;
import com.pickone.phone.repository.OtpStore;
import com.pickone.phone.repository.OtpStore.ConfirmResult;
import com.pickone.phone.repository.OtpStore.StoredCode;
import com.pickone.phone.sms.SmsSender;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * SMS OTP 휴대폰 인증 (docs/api.md 3장).
 * 발송: 정규화 → 중복 번호 확인 → 쿨다운(SET NX) → 일일 한도(INCR) → 코드 생성·해시 저장 → 발송
 * 확인: 정규화 → Redis 에서 salt 조회 → 입력 코드 해시 → Lua 로 원자 비교 → 성공 시 DB 트랜잭션(ACTIVE 전환 등) → 새 토큰
 */
@Slf4j
@Service
@EnableConfigurationProperties(OtpProperties.class)
public class PhoneVerificationService {

	private static final int SALT_BYTES = 16;

	private final MemberRepository memberRepository;
	private final OtpStore otpStore;
	private final OtpProperties properties;
	private final PhoneCipher phoneCipher;
	private final SmsSender smsSender;
	private final PhoneVerificationCompleter completer;
	private final AuthService authService;
	private final SecureRandom random = new SecureRandom();

	public PhoneVerificationService(MemberRepository memberRepository, OtpStore otpStore, OtpProperties properties,
			PhoneCipher phoneCipher, SmsSender smsSender, PhoneVerificationCompleter completer, AuthService authService) {
		this.memberRepository = memberRepository;
		this.otpStore = otpStore;
		this.properties = properties;
		this.phoneCipher = phoneCipher;
		this.smsSender = smsSender;
		this.completer = completer;
		this.authService = authService;
	}

	public SendVerificationResponse send(Long memberId, String rawPhone) {
		Member member = getPendingMember(memberId);
		String phone = PhoneNumber.toE164(rawPhone);
		String phoneHmac = phoneCipher.hmac(phone);

		if (memberRepository.existsByPhoneHmac(phoneHmac)) {
			throw new BusinessException(ErrorCode.PHONE_ALREADY_REGISTERED);
		}
		// 동시에 같은 번호로 요청이 와도 SET NX 라 하나만 통과한다
		if (!otpStore.tryAcquireCooldown(phoneHmac, properties.cooldown())) {
			throw new BusinessException(ErrorCode.OTP_COOLDOWN);
		}
		String today = KstDates.today();
		Duration toMidnight = KstDates.untilMidnight();
		if (otpStore.incrementDailyByPhone(phoneHmac, today, toMidnight) > properties.dailyLimitPerPhone()
				|| otpStore.incrementDailyByMember(member.getId(), today, toMidnight) > properties.dailyLimitPerMember()) {
			throw new BusinessException(ErrorCode.OTP_DAILY_LIMIT);
		}

		String code = generateCode();
		String salt = generateSalt();
		otpStore.saveCode(member.getId(), phoneHmac, hash(code, salt), salt, properties.ttl());

		smsSender.send(phone, "[pick-one] 인증번호 " + code + " (" + properties.ttl().toMinutes() + "분 안에 입력해 주세요)");
		log.info("인증번호 발송: memberId={}, phone={}", member.getId(), PhoneNumber.mask(phone));

		return new SendVerificationResponse(properties.ttl().toSeconds(), properties.cooldown().toSeconds());
	}

	public TokenResponse confirm(Long memberId, String rawPhone, String code) {
		Member member = getPendingMember(memberId);
		String phone = PhoneNumber.toE164(rawPhone);
		String phoneHmac = phoneCipher.hmac(phone);

		// 이 회원이 이 번호로 받은 코드가 없으면(다른 회원의 코드 포함) 만료로 본다
		StoredCode stored = otpStore.find(member.getId(), phoneHmac)
				.orElseThrow(() -> new BusinessException(ErrorCode.OTP_EXPIRED));

		ConfirmResult result = otpStore.confirm(member.getId(), phoneHmac, hash(code, stored.salt()), properties.maxAttempts());
		switch (result.status()) {
			case NOT_FOUND -> throw new BusinessException(ErrorCode.OTP_EXPIRED);
			case ATTEMPTS_EXCEEDED -> throw new BusinessException(ErrorCode.OTP_ATTEMPT_EXCEEDED);
			case MISMATCH -> {
				long remaining = properties.maxAttempts() - result.attempts();
				throw new BusinessException(ErrorCode.OTP_INVALID,
						ErrorCode.OTP_INVALID.getMessage() + " (남은 시도 " + remaining + "회)");
			}
			case MATCHED -> { }
		}

		// 코드는 이미 소비됐다. 아래 DB 트랜잭션이 실패(예: 번호 중복 409)하면 코드를 다시 요청해야 한다
		Member completed = completer.complete(member.getId(), phoneCipher.encrypt(phone), phoneHmac);
		log.info("휴대폰 인증 완료: memberId={}, phone={}", completed.getId(), PhoneNumber.mask(phone));

		return authService.issueTokens(completed);
	}

	private Member getPendingMember(Long memberId) {
		Member member = memberRepository.findById(memberId)
				.filter(m -> !m.isDeleted())
				.orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
		if (member.isSignupCompleted()) {
			throw new BusinessException(ErrorCode.PHONE_ALREADY_VERIFIED);
		}
		return member;
	}

	private String generateCode() {
		int bound = (int) Math.pow(10, properties.codeLength());
		return String.format("%0" + properties.codeLength() + "d", random.nextInt(bound));
	}

	private String generateSalt() {
		byte[] bytes = new byte[SALT_BYTES];
		random.nextBytes(bytes);
		return Base64.getEncoder().encodeToString(bytes);
	}

	/** SHA-256(code + salt). 코드 원문은 Redis 에도 남기지 않는다 */
	static String hash(String code, String salt) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest((code + salt).getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

}
