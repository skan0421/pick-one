package com.pickone.global.crypto;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * pickone.crypto.* 설정. 두 키 모두 환경변수로 주입하며 기본값이 없다.
 * - phoneAesKey : AES-GCM 키. Base64 로 인코딩된 16/24/32 바이트 (예: openssl rand -base64 32)
 * - phoneHmacKey: HMAC-SHA256 키. 32바이트 이상 임의 문자열
 * 두 키는 서로 달라야 한다. 암호화 키가 유출돼도 HMAC 으로 원본을 역산할 수 없고, 반대도 마찬가지다.
 */
@ConfigurationProperties(prefix = "pickone.crypto")
public record CryptoProperties(String phoneAesKey, String phoneHmacKey) {

	private static final int MIN_HMAC_KEY_BYTES = 32;

	public CryptoProperties {
		byte[] aes = decodeAesKey(phoneAesKey);
		if (aes.length != 16 && aes.length != 24 && aes.length != 32) {
			throw new IllegalStateException("pickone.crypto.phone-aes-key 는 Base64 로 인코딩된 16/24/32 바이트여야 합니다. PHONE_AES_KEY 환경변수를 확인하세요.");
		}
		if (phoneHmacKey == null || phoneHmacKey.getBytes(StandardCharsets.UTF_8).length < MIN_HMAC_KEY_BYTES) {
			throw new IllegalStateException("pickone.crypto.phone-hmac-key 는 32바이트 이상이어야 합니다. PHONE_HMAC_KEY 환경변수를 확인하세요.");
		}
		if (phoneHmacKey.equals(phoneAesKey)) {
			throw new IllegalStateException("pickone.crypto 의 AES 키와 HMAC 키는 서로 달라야 합니다.");
		}
	}

	public byte[] aesKeyBytes() {
		return decodeAesKey(phoneAesKey);
	}

	public byte[] hmacKeyBytes() {
		return phoneHmacKey.getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] decodeAesKey(String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalStateException("pickone.crypto.phone-aes-key 가 비어 있습니다. PHONE_AES_KEY 환경변수를 확인하세요.");
		}
		try {
			return Base64.getDecoder().decode(value);
		}
		catch (IllegalArgumentException e) {
			throw new IllegalStateException("pickone.crypto.phone-aes-key 는 Base64 문자열이어야 합니다.", e);
		}
	}

}
