package com.pickone.global.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pickone.global.security.jwt.JwtConfig;
import com.pickone.global.security.jwt.JwtTokenProvider;
import com.pickone.member.domain.SignupStatus;
import com.pickone.support.IntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;

/**
 * JWT 인증·인가 규칙 검증. 회원 API 없이 토큰만 만들어 Security 필터 동작을 확인한다.
 * /api/v1/questions/feed 는 아직 없는 경로지만 Security 가 컨트롤러보다 먼저 거르므로 ACTIVE 전용 규칙 검증에 쓴다.
 */
@IntegrationTest
class SecurityIntegrationTest {

	private static final String ACTIVE_ONLY_PATH = "/api/v1/questions/feed";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtTokenProvider jwtTokenProvider;

	@Autowired
	JwtEncoder jwtEncoder;

	@Test
	void 토큰이_없으면_401_AUTH_INVALID_TOKEN() throws Exception {
		mockMvc.perform(get(ACTIVE_ONLY_PATH))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"))
				.andExpect(jsonPath("$.message").isString());
	}

	@Test
	void 서명이_위조된_토큰은_401_AUTH_INVALID_TOKEN() throws Exception {
		String token = jwtTokenProvider.createAccessToken(1L, SignupStatus.ACTIVE);
		String tampered = token.substring(0, token.length() - 4) + "abcd";

		mockMvc.perform(get(ACTIVE_ONLY_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));
	}

	@Test
	void 만료된_토큰은_401_AUTH_EXPIRED_TOKEN() throws Exception {
		Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(JwtTokenProvider.ISSUER)
				.subject("1")
				.issuedAt(past.minus(30, ChronoUnit.MINUTES))
				.expiresAt(past)
				.claim(JwtConfig.SIGNUP_STATUS_CLAIM, SignupStatus.ACTIVE.name())
				.build();
		String expired = jwtEncoder
				.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();

		mockMvc.perform(get(ACTIVE_ONLY_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_EXPIRED_TOKEN"));
	}

	@Test
	void PENDING_PHONE_회원이_ACTIVE_전용_API를_호출하면_403_SIGNUP_INCOMPLETE() throws Exception {
		String token = jwtTokenProvider.createAccessToken(1L, SignupStatus.PENDING_PHONE);

		mockMvc.perform(get(ACTIVE_ONLY_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("SIGNUP_INCOMPLETE"));
	}

	@Test
	void ACTIVE_회원은_ACTIVE_전용_경로를_통과한다_대조군() throws Exception {
		String token = jwtTokenProvider.createAccessToken(1L, SignupStatus.ACTIVE);

		// 인가는 통과하고, 아직 컨트롤러가 없어 404 가 난다 → 403 이 상태 때문이었음을 보여준다
		mockMvc.perform(get(ACTIVE_ONLY_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isNotFound());
	}

}
