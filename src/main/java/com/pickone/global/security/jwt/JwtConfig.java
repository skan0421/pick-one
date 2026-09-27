package com.pickone.global.security.jwt;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * JWT 발급/검증 빈. 별도 JWT 라이브러리 없이 Spring Security 의 Nimbus 지원을 쓴다.
 * 대칭키(HS256) 하나로 발급과 검증을 모두 한다.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

	/** signupStatus 클레임을 권한으로 바꿀 때 붙이는 접두사. SecurityConfig 의 hasAuthority 와 맞춘다 */
	public static final String SIGNUP_AUTHORITY_PREFIX = "SIGNUP_";
	public static final String SIGNUP_STATUS_CLAIM = "signupStatus";

	@Bean
	SecretKey jwtSecretKey(JwtProperties properties) {
		return new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
	}

	@Bean
	JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
		return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
	}

	@Bean
	JwtDecoder jwtDecoder(SecretKey jwtSecretKey) {
		return NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
	}

	/**
	 * 토큰의 signupStatus 클레임을 "SIGNUP_{상태}" 권한으로 변환한다.
	 * 예: PENDING_PHONE → SIGNUP_PENDING_PHONE, ACTIVE → SIGNUP_ACTIVE
	 */
	@Bean
	JwtAuthenticationConverter jwtAuthenticationConverter() {
		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(jwt -> {
			String status = jwt.getClaimAsString(SIGNUP_STATUS_CLAIM);
			if (status == null) {
				return List.<GrantedAuthority>of();
			}
			return List.<GrantedAuthority>of(new SimpleGrantedAuthority(SIGNUP_AUTHORITY_PREFIX + status));
		});
		return converter;
	}

}
