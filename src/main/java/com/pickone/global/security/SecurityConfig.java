package com.pickone.global.security;

import com.pickone.global.security.handler.JsonAccessDeniedHandler;
import com.pickone.global.security.handler.JsonAuthenticationEntryPoint;
import com.pickone.global.security.jwt.JwtConfig;
import com.pickone.member.domain.SignupStatus;
import lombok.RequiredArgsConstructor;
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

/**
 * 인증 수준 (docs/api.md 1.2)
 * - 공개: 가입, 로그인, 재발급(access 만료 상태에서 호출되므로 본문의 refresh 로 인증)
 * - 로그인: 토큰만 있으면 됨 (PENDING_PHONE 포함) — 인증, 내 정보, 휴대폰 인증
 * - ACTIVE: 그 외 모든 API. PENDING_PHONE 이면 403 SIGNUP_INCOMPLETE
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

	private static final String ACTIVE_AUTHORITY = JwtConfig.SIGNUP_AUTHORITY_PREFIX + SignupStatus.ACTIVE.name();

	private final JwtDecoder jwtDecoder;
	private final JwtAuthenticationConverter jwtAuthenticationConverter;
	private final JsonAuthenticationEntryPoint authenticationEntryPoint;
	private final JsonAccessDeniedHandler accessDeniedHandler;

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
				// 토큰 기반이므로 세션·CSRF·폼 로그인은 쓰지 않는다
				.csrf(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable)
				.logout(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(HttpMethod.POST, "/api/v1/auth/signup", "/api/v1/auth/login", "/api/v1/auth/refresh")
						.permitAll()
						.requestMatchers("/api/v1/auth/**", "/api/v1/members/me", "/api/v1/phone-verifications/**")
						.authenticated()
						.anyRequest().hasAuthority(ACTIVE_AUTHORITY))
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

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

}
