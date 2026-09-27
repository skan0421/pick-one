package com.pickone.support;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * 테스트용 비밀값(JWT 비밀키, 휴대폰 AES/HMAC 키).
 * 저장소에 더미 키를 두지 않기 위해 테스트 JVM 이 뜰 때마다 랜덤으로 만든다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestSecretsConfiguration {

	@Bean
	DynamicPropertyRegistrar testSecretsRegistrar() {
		String jwtSecret = randomBase64(64);
		String aesKey = randomBase64(32);
		String hmacKey = randomBase64(48);
		return registry -> {
			registry.add("pickone.jwt.secret", () -> jwtSecret);
			registry.add("pickone.crypto.phone-aes-key", () -> aesKey);
			registry.add("pickone.crypto.phone-hmac-key", () -> hmacKey);
		};
	}

	private static String randomBase64(int bytes) {
		byte[] b = new byte[bytes];
		new SecureRandom().nextBytes(b);
		return Base64.getEncoder().encodeToString(b);
	}

}
