package com.pickone.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pickone.auth.repository.RefreshTokenStore;
import com.pickone.global.security.jwt.JwtConfig;
import com.pickone.global.security.jwt.JwtTokenProvider;
import com.pickone.support.IntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Refresh 토큰 재발급(rotation)·재사용 감지·로그아웃 통합 테스트 (docs/api.md 1.3) */
@IntegrationTest
class RefreshTokenIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ObjectMapper objectMapper;

	@Autowired
	JwtEncoder jwtEncoder;

	@Autowired
	JwtDecoder jwtDecoder; // @Primary = access 디코더

	@Autowired
	RefreshTokenStore refreshTokenStore;

	@Autowired
	StringRedisTemplate redisTemplate;

	@Autowired
	JdbcTemplate jdbc;

	// ---------- rotation ----------

	@Test
	void 재발급하면_새_access와_새_refresh를_받고_옛_refresh는_used로_남는다() throws Exception {
		JsonNode tokens = signup();
		String oldRefresh = tokens.get("refreshToken").asString();
		long memberId = tokens.get("member").get("id").asLong();
		String oldJti = jtiOf(oldRefresh);

		JsonNode rotated = json(refresh(oldRefresh).andExpect(status().isOk()));
		String newAccess = rotated.get("accessToken").asString();
		String newRefresh = rotated.get("refreshToken").asString();

		assertThat(newRefresh).isNotEqualTo(oldRefresh);
		assertThat(refreshTokenStore.exists(memberId, oldJti)).isFalse();
		assertThat(refreshTokenStore.exists(memberId, jtiOf(newRefresh))).isTrue();
		assertThat(redisTemplate.hasKey("refresh:used:" + oldJti)).isTrue();
		assertThat(redisTemplate.getExpire("refresh:used:" + oldJti, TimeUnit.SECONDS)).isGreaterThan(0);

		mockMvc.perform(get("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + newAccess))
				.andExpect(status().isOk());
	}

	@Test
	void 새_access_토큰은_DB의_최신_signupStatus를_반영한다() throws Exception {
		JsonNode tokens = signup();
		long memberId = tokens.get("member").get("id").asLong();
		// 휴대폰 인증 API 는 아직 없으므로 DB 에서 직접 ACTIVE 로 바꾼다
		jdbc.update("UPDATE member SET signup_status = 'ACTIVE' WHERE id = ?", memberId);

		JsonNode rotated = json(refresh(tokens.get("refreshToken").asString()).andExpect(status().isOk()));

		String status = jwtDecoder.decode(rotated.get("accessToken").asString()).getClaimAsString(JwtConfig.SIGNUP_STATUS_CLAIM);
		assertThat(status).isEqualTo("ACTIVE");
		assertThat(rotated.get("member").get("signupStatus").asString()).isEqualTo("ACTIVE");
	}

	// ---------- 재사용 감지 ----------

	@Test
	void 교체된_옛_refresh를_다시_쓰면_AUTH_REFRESH_REUSED이고_그_회원의_refresh가_전부_폐기된다() throws Exception {
		JsonNode tokens = signup();
		String oldRefresh = tokens.get("refreshToken").asString();
		long memberId = tokens.get("member").get("id").asLong();

		String newRefresh = json(refresh(oldRefresh).andExpect(status().isOk())).get("refreshToken").asString();
		assertThat(refreshTokenStore.exists(memberId, jtiOf(newRefresh))).isTrue();

		refresh(oldRefresh)
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_REFRESH_REUSED"));

		// 정상적으로 교체받았던 새 refresh 도 함께 폐기되어 더 이상 쓸 수 없다
		assertThat(refreshTokenStore.exists(memberId, jtiOf(newRefresh))).isFalse();
		refresh(newRefresh)
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));
	}

	// ---------- 로그아웃 ----------

	@Test
	void 로그아웃하면_그_refresh로_재발급할_수_없다() throws Exception {
		JsonNode tokens = signup();
		String access = tokens.get("accessToken").asString();
		String refreshToken = tokens.get("refreshToken").asString();

		logout(access, refreshToken).andExpect(status().isNoContent());

		refresh(refreshToken)
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));
	}

	@Test
	void 로그아웃은_access_토큰이_있어야_한다() throws Exception {
		JsonNode tokens = signup();

		mockMvc.perform(post("/api/v1/auth/logout")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(tokens.get("refreshToken").asString())))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));
	}

	@Test
	void 다른_회원의_refresh로는_로그아웃할_수_없다() throws Exception {
		JsonNode mine = signup();
		JsonNode others = signup();

		logout(mine.get("accessToken").asString(), others.get("refreshToken").asString())
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));

		// 남의 refresh 는 그대로 살아 있다
		refresh(others.get("refreshToken").asString()).andExpect(status().isOk());
	}

	// ---------- 잘못된 refresh ----------

	@Test
	void 만료된_refresh는_AUTH_INVALID_TOKEN() throws Exception {
		JsonNode tokens = signup();
		long memberId = tokens.get("member").get("id").asLong();
		Instant past = Instant.now().minus(1, ChronoUnit.DAYS);
		String expired = encode(JwtClaimsSet.builder()
				.issuer(JwtTokenProvider.ISSUER)
				.subject(String.valueOf(memberId))
				.id(UUID.randomUUID().toString())
				.issuedAt(past.minus(14, ChronoUnit.DAYS))
				.expiresAt(past)
				.claim(JwtConfig.TOKEN_TYPE_CLAIM, JwtConfig.TOKEN_TYPE_REFRESH)
				.build());

		refresh(expired)
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));
	}

	@Test
	void 서명이_위조된_refresh는_AUTH_INVALID_TOKEN() throws Exception {
		String refreshToken = signup().get("refreshToken").asString();
		String tampered = refreshToken.substring(0, refreshToken.length() - 4) + "abcd";

		refresh(tampered)
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));
	}

	@Test
	void access_토큰을_refresh_자리에_넣으면_AUTH_INVALID_TOKEN() throws Exception {
		String access = signup().get("accessToken").asString();

		refresh(access)
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));
	}

	@Test
	void refresh_토큰을_access_자리에_넣으면_401() throws Exception {
		String refreshToken = signup().get("refreshToken").asString();

		mockMvc.perform(get("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + refreshToken))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));
	}

	// ---------- 동시성 ----------

	@Test
	void 같은_refresh로_동시에_재발급하면_하나만_200이다() throws Exception {
		JsonNode tokens = signup();
		String refreshToken = tokens.get("refreshToken").asString();
		long memberId = tokens.get("member").get("id").asLong();

		List<MockHttpServletResponse> responses = runConcurrently(
				() -> refresh(refreshToken).andReturn().getResponse(),
				() -> refresh(refreshToken).andReturn().getResponse());

		List<Integer> statuses = responses.stream().map(MockHttpServletResponse::getStatus).sorted().toList();
		assertThat(statuses).containsExactly(200, 401);

		// 진 쪽은 "이미 교체된 refresh" 로 판정되어 재사용 감지가 발동하고, 그 결과 이긴 쪽의 새 refresh 도 폐기된다
		MockHttpServletResponse lost = responses.stream().filter(r -> r.getStatus() == 401).findFirst().orElseThrow();
		assertThat(objectMapper.readTree(lost.getContentAsString()).get("code").asString()).isEqualTo("AUTH_REFRESH_REUSED");

		MockHttpServletResponse won = responses.stream().filter(r -> r.getStatus() == 200).findFirst().orElseThrow();
		String wonRefresh = objectMapper.readTree(won.getContentAsString()).get("refreshToken").asString();
		assertThat(refreshTokenStore.exists(memberId, jtiOf(wonRefresh))).isFalse();
	}

	// ---------- 헬퍼 ----------

	private JsonNode signup() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String body = "{\"email\":\"r" + suffix + "@test.com\",\"password\":\"pass1234\",\"nickname\":\"리프" + suffix + "\"}";
		return json(mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated()));
	}

	private ResultActions refresh(String refreshToken) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(body(refreshToken)));
	}

	private ResultActions logout(String accessToken, String refreshToken) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/logout")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(refreshToken)));
	}

	private static String body(String refreshToken) {
		return "{\"refreshToken\":\"" + refreshToken + "\"}";
	}

	private JsonNode json(ResultActions actions) throws Exception {
		return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
	}

	/** refresh 토큰의 jti 를 서명 검증 없이 payload 에서 읽는다 (테스트 편의용) */
	private JsonNode payload(String jwt) throws Exception {
		String payload = jwt.split("\\.")[1];
		return objectMapper.readTree(java.util.Base64.getUrlDecoder().decode(payload));
	}

	private String jtiOf(String refreshToken) throws Exception {
		return payload(refreshToken).get("jti").asString();
	}

	private String encode(JwtClaimsSet claims) {
		return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
	}

	@SafeVarargs
	private List<MockHttpServletResponse> runConcurrently(Callable<MockHttpServletResponse>... tasks) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(tasks.length);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
			for (Callable<MockHttpServletResponse> task : tasks) {
				futures.add(executor.submit(() -> {
					start.await();
					return task.call();
				}));
			}
			start.countDown();
			List<MockHttpServletResponse> responses = new ArrayList<>();
			for (Future<MockHttpServletResponse> future : futures) {
				responses.add(future.get(30, TimeUnit.SECONDS));
			}
			return responses;
		}
		finally {
			executor.shutdownNow();
		}
	}

}
