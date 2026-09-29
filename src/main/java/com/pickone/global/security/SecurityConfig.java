package com.pickone.global.security;

import com.pickone.global.cors.CorsProperties;
import com.pickone.global.openapi.SwaggerProperties;
import com.pickone.global.security.handler.JsonAccessDeniedHandler;
import com.pickone.global.security.handler.JsonAuthenticationEntryPoint;
import com.pickone.global.security.jwt.JwtConfig;
import com.pickone.member.domain.SignupStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import java.util.List;

/**
 * 인증 수준 (docs/api.md 1.2)
 * - 공개: 가입, 로그인, 재발급(access 만료 상태에서 호출되므로 본문의 refresh 로 인증)
 * - 로그인: 토큰만 있으면 됨 (PENDING_PHONE 포함) — 인증, 내 정보, 휴대폰 인증
 * - ACTIVE: 그 외 모든 API. PENDING_PHONE 이면 403 SIGNUP_INCOMPLETE
 * Swagger(/swagger-ui/**, /v3/api-docs/**)는 pickone.swagger.enabled 가 true 일 때만 인증 없이 연다. 꺼져 있으면 다른 경로처럼 401 이다.
 * CORS 는 /api/** 에만, 허용 origin 은 pickone.cors.allowed-origins (docs/api.md 1.9). CorsFilter 가 인가보다 앞이라 preflight 는 토큰 없이 통과한다.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
@EnableConfigurationProperties({SwaggerProperties.class, CorsProperties.class})
public class SecurityConfig {

	private static final String[] SWAGGER_PATHS = {"/swagger-ui/**", "/v3/api-docs/**"};

	private static final String ACTIVE_AUTHORITY = JwtConfig.SIGNUP_AUTHORITY_PREFIX + SignupStatus.ACTIVE.name();

	private final JwtDecoder jwtDecoder;
	private final JwtAuthenticationConverter jwtAuthenticationConverter;
	private final JsonAuthenticationEntryPoint authenticationEntryPoint;
	private final JsonAccessDeniedHandler accessDeniedHandler;
	private final SwaggerProperties swaggerProperties;
	private final CorsProperties corsProperties;

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
				// 토큰 기반이므로 세션·CSRF·폼 로그인은 쓰지 않는다
				.csrf(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable)
				.logout(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.cors(cors -> cors.configurationSource(corsConfigurationSource()))
				.authorizeHttpRequests(auth -> {
					if (swaggerProperties.isEnabled()) {
						auth.requestMatchers(SWAGGER_PATHS).permitAll();
					}
					auth
						.requestMatchers(HttpMethod.POST, "/api/v1/auth/signup", "/api/v1/auth/login", "/api/v1/auth/refresh")
						.permitAll()
						.requestMatchers("/api/v1/auth/**", "/api/v1/members/me", "/api/v1/phone-verifications/**")
						.authenticated()
						.anyRequest().hasAuthority(ACTIVE_AUTHORITY);
				})
				.oauth2ResourceServer(resourceServer -> resourceServer
						.jwt(jwt -> jwt
								.decoder(jwtDecoder)
								.jwtAuthenticationConverter(jwtAuthenticationConverter))
						.authenticationEntryPoint(authenticationEntryPoint)
						.accessDeniedHandler(accessDeniedHandler))
				.exceptionHandling(handling -> handling
						.authenticationEntryPoint(authenticationEntryPoint)
						.accessDeniedHandler(accessDeniedHandler));
		return http.build();
	}

	/** /api/** 만. 쿠키·세션을 쓰지 않으므로 credentials 는 false, Authorization 헤더는 허용 */
	@Bean
	CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration config = new CorsConfiguration();
		config.setAllowedOrigins(corsProperties.allowedOrigins());
		config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
		config.setAllowCredentials(false);
		config.setMaxAge(3600L);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", config);
		return source;
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

}
