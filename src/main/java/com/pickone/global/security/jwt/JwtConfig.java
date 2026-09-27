package com.pickone.global.security.jwt;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * JWT 발급/검증 빈. 별도 JWT 라이브러리 없이 Spring Security 의 Nimbus 지원을 쓴다.
 * 대칭키(HS256) 하나로 발급과 검증을 모두 하되, access 와 refresh 는 typ 클레임으로 구분해
 * 서로 다른 용도로 쓰이지 못하게 각각의 디코더가 typ 을 검증한다.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

	/** signupStatus 클레임을 권한으로 바꿀 때 붙이는 접두사. SecurityConfig 의 hasAuthority 와 맞춘다 */
	public static final String SIGNUP_AUTHORITY_PREFIX = "SIGNUP_";
	public static final String SIGNUP_STATUS_CLAIM = "signupStatus";
	public static final String TOKEN_TYPE_CLAIM = "typ";
	public static final String TOKEN_TYPE_ACCESS = "access";
	public static final String TOKEN_TYPE_REFRESH = "refresh";

	@Bean
	SecretKey jwtSecretKey(JwtProperties properties) {
		return new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
	}

	@Bean
	JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
		return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
	}

	/** Security 리소스 서버가 쓰는 디코더. typ=access 만 통과한다 */
	@Bean
	@Primary
	JwtDecoder jwtDecoder(SecretKey jwtSecretKey) {
		return decoderForType(jwtSecretKey, TOKEN_TYPE_ACCESS);
	}

	/** 재발급·로그아웃에서 refresh 토큰을 검증하는 디코더. typ=refresh 만 통과한다 */
	@Bean
	JwtDecoder refreshTokenDecoder(SecretKey jwtSecretKey) {
		return decoderForType(jwtSecretKey, TOKEN_TYPE_REFRESH);
	}

	private NimbusJwtDecoder decoderForType(SecretKey key, String tokenType) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
		OAuth2TokenValidator<Jwt> typeValidator = new JwtClaimValidator<String>(TOKEN_TYPE_CLAIM, tokenType::equals);
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(), typeValidator));
		return decoder;
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
