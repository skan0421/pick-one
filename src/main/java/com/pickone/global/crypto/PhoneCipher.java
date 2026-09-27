package com.pickone.global.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 휴대폰 번호 보호 (docs/planning.md 개인정보 설계).
 * - encrypt/decrypt: AES-256-GCM. 요청마다 12바이트 IV 를 새로 만들고 "IV || 암호문(+태그)" 을 Base64 로 저장한다 (member.phone_encrypted)
 * - hmac: HMAC-SHA256 → 소문자 hex 64자. 같은 번호는 항상 같은 값이므로 검색·매칭에 쓴다 (member.phone_hmac, hide_pending.phone_hmac)
 * 원본 번호는 어디에도 저장하지 않는다.
 */
@Component
@EnableConfigurationProperties(CryptoProperties.class)
public class PhoneCipher {

	private static final String AES_TRANSFORMATION = "AES/GCM/NoPadding";
	private static final String HMAC_ALGORITHM = "HmacSHA256";
	private static final int IV_BYTES = 12;
	private static final int TAG_BITS = 128;

	private final SecretKey aesKey;
	private final SecretKey hmacKey;
	private final SecureRandom random = new SecureRandom();

	public PhoneCipher(CryptoProperties properties) {
		this.aesKey = new SecretKeySpec(properties.aesKeyBytes(), "AES");
		this.hmacKey = new SecretKeySpec(properties.hmacKeyBytes(), HMAC_ALGORITHM);
	}

	public String encrypt(String plain) {
		try {
			byte[] iv = new byte[IV_BYTES];
			random.nextBytes(iv);
			Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
			cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, iv));
			byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
			ByteBuffer out = ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted);
			return Base64.getEncoder().encodeToString(out.array());
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("휴대폰 번호 암호화에 실패했습니다.", e);
		}
	}

	public String decrypt(String encoded) {
		try {
			byte[] all = Base64.getDecoder().decode(encoded);
			byte[] iv = new byte[IV_BYTES];
			byte[] encrypted = new byte[all.length - IV_BYTES];
			ByteBuffer.wrap(all).get(iv).get(encrypted);
			Cipher cipher = Cipher.getInstance(AES_TRANSFORMATION);
			cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, iv));
			return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
		}
		catch (GeneralSecurityException | IllegalArgumentException e) {
			throw new IllegalStateException("휴대폰 번호 복호화에 실패했습니다.", e);
		}
	}

	public String hmac(String plain) {
		try {
			Mac mac = Mac.getInstance(HMAC_ALGORITHM);
			mac.init(hmacKey);
			return HexFormat.of().formatHex(mac.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("휴대폰 번호 HMAC 계산에 실패했습니다.", e);
		}
	}

}
