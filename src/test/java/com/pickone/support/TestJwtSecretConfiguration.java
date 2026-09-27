package com.pickone.support;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * 테스트용 JWT 비밀키. 저장소에 더미 키를 두지 않기 위해 테스트 JVM 이 뜰 때마다 랜덤으로 만든다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestJwtSecretConfiguration {

	@Bean
	DynamicPropertyRegistrar jwtSecretRegistrar() {
		byte[] bytes = new byte[64];
		new SecureRandom().nextBytes(bytes);
		String secret = Base64.getEncoder().encodeToString(bytes);
		return registry -> registry.add("pickone.jwt.secret", () -> secret);
	}

}
