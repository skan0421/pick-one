package com.pickone.global.security.jwt;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * pickone.jwt.* 설정.
 * secret 은 HS256 용 비밀키 문자열로 32바이트 이상이어야 한다 (환경변수 JWT_SECRET 또는 application-local.yml).
 */
@ConfigurationProperties(prefix = "pickone.jwt")
public record JwtProperties(String secret, Duration accessTokenTtl, Duration refreshTokenTtl) {

	private static final int MIN_SECRET_BYTES = 32;

	public JwtProperties {
		if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
			throw new IllegalStateException("pickone.jwt.secret 은 32바이트 이상이어야 합니다. JWT_SECRET 환경변수를 확인하세요.");
		}
		if (accessTokenTtl == null) {
			accessTokenTtl = Duration.ofMinutes(30);
		}
		if (refreshTokenTtl == null) {
			refreshTokenTtl = Duration.ofDays(14);
		}
	}

}
