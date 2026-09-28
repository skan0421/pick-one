package com.pickone.point;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pickone.global.security.jwt.JwtTokenProvider;
import com.pickone.global.time.KstDates;
import com.pickone.member.domain.SignupStatus;
import com.pickone.point.repository.PointDailyCounterStore;
import com.pickone.support.IntegrationTest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 포인트 잔액·내역·상단 노출(boost) 통합 테스트 (docs/api.md 6장) */
@IntegrationTest
class PointIntegrationTest {

	private static final String TWO_OPTIONS = """
			{"questionType":"TEXT","content":"%s","options":[{"content":"카페"},{"content":"밥집"}]}""";
	private static final long BOOST_COST = 100;

	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper objectMapper;
	@Autowired JdbcTemplate jdbc;
	@Autowired JwtTokenProvider jwtTokenProvider;
	@Autowired StringRedisTemplate redisTemplate;

	// =============================== 잔액 ===============================

	@Nested
	class 잔액 {

		@Test
		void 새_지갑은_잔액_0_오늘_적립_0_상한_50이다() throws Exception {
			mockMvc.perform(get("/api/v1/points/balance").header(HttpHeaders.AUTHORIZATION, bearer(activeMember())))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.balance").value(0))
					.andExpect(jsonPath("$.todayEarned").value(0))
					.andExpect(jsonPath("$.dailyEarnLimit").value(50));
		}

		@Test
		void 투표_적립_후_잔액과_오늘_적립이_늘고_Redis_캐시가_유실되면_원장에서_복구한다() throws Exception {
			Session me = activeMember();
			Created q = create(activeMember(), TWO_OPTIONS.formatted("적립"));
			vote(me, q.id, q.option(0)).andExpect(status().isCreated());

			mockMvc.perform(get("/api/v1/points/balance").header(HttpHeaders.AUTHORIZATION, bearer(me)))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.balance").value(1))
					.andExpect(jsonPath("$.todayEarned").value(1));

			// 캐시 삭제 → 원장 SUM 으로 다시 계산하고 캐시를 채운다
			String key = PointDailyCounterStore.key(me.memberId, KstDates.today());
			redisTemplate.delete(key);
			mockMvc.perform(get("/api/v1/points/balance").header(HttpHeaders.AUTHORIZATION, bearer(me)))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.todayEarned").value(1));
			assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("1");
			assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS)).isBetween(1L, Duration.ofDays(1).toSeconds());
		}

		@Test
		void 지갑이_없으면_409_POINT_WALLET_NOT_FOUND() throws Exception {
			mockMvc.perform(get("/api/v1/points/balance").header(HttpHeaders.AUTHORIZATION, bearer(activeMemberWithoutWallet())))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("POINT_WALLET_NOT_FOUND"));
		}

	}

	// =============================== 내역 ===============================

	@Test
	void 내역은_최신순이고_커서로_이어지며_적립과_차감이_모두_나온다() throws Exception {
		Session me = activeMember();
		Session other = activeMember();
		for (int i = 0; i < 3; i++) {
			Created q = create(other, TWO_OPTIONS.formatted("적립 " + i));
			vote(me, q.id, q.option(0)).andExpect(status().isCreated());
		}
		long grantId = grant(me, 97); // 3 + 97 = 100 → boost 1회 가능 (created_at 은 이틀 전이라 목록 맨 뒤)
		Created mine = create(me, TWO_OPTIONS.formatted("내 글"));
		long ledgerId = json(boost(me, mine.id, UUID.randomUUID().toString()).andExpect(status().isOk())).get("ledgerId").asLong();

		JsonNode page1 = json(mockMvc.perform(get("/api/v1/points/ledger?size=2").header(HttpHeaders.AUTHORIZATION, bearer(me)))
				.andExpect(status().isOk()));
		assertThat(page1.get("items").size()).isEqualTo(2);
		assertThat(page1.get("hasNext").asBoolean()).isTrue();
		JsonNode latest = page1.get("items").get(0);
		assertThat(latest.get("id").asLong()).isEqualTo(ledgerId);
		assertThat(latest.get("amount").asLong()).isEqualTo(-BOOST_COST);
		assertThat(latest.get("balanceAfter").asLong()).isZero();
		assertThat(latest.get("txType").asString()).isEqualTo("BOOST_USE");
		assertThat(latest.get("refType").asString()).isEqualTo("QUESTION");
		assertThat(latest.get("refId").asLong()).isEqualTo(mine.id);
		assertThat(latest.get("createdAt").asString()).isNotBlank();

		List<Long> ids = new ArrayList<>(ids(page1));
		String cursor = page1.get("nextCursor").asString();
		int pages = 1;
		while (cursor != null && pages < 10) {
			JsonNode page = json(mockMvc.perform(get("/api/v1/points/ledger?size=2&cursor=" + cursor).header(HttpHeaders.AUTHORIZATION, bearer(me)))
					.andExpect(status().isOk()));
			ids.addAll(ids(page));
			cursor = page.get("hasNext").asBoolean() ? page.get("nextCursor").asString() : null;
			pages++;
		}
		// 적립 3 + 테스트 지급 1 + boost 1 = 5건, 중복·누락 없이 created_at 내림차순 (boost 가 처음, 이틀 전 지급이 마지막)
		assertThat(ids).hasSize(5).doesNotHaveDuplicates();
		assertThat(ids.get(0)).isEqualTo(ledgerId);
		assertThat(ids.get(4)).isEqualTo(grantId);
		assertWalletEqualsLedger(me.memberId);
	}

	// =============================== 상단 노출 ===============================

	@Nested
	class 상단_노출 {

		@Test
		void 작성자가_100P로_boost하면_24시간_노출되고_원장에_차감이_남는다() throws Exception {
			Session me = activeMember();
			grant(me, 150);
			Created q = create(me, TWO_OPTIONS.formatted("boost"));
			String key = UUID.randomUUID().toString();
			LocalDateTime before = LocalDateTime.now();

			JsonNode body = json(boost(me, q.id, key)
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.questionId").value(q.id))
					.andExpect(jsonPath("$.cost").value(BOOST_COST))
					.andExpect(jsonPath("$.balanceAfter").value(50))
					.andExpect(jsonPath("$.ledgerId").isNumber()));
			LocalDateTime boostedUntil = LocalDateTime.parse(body.get("boostedUntil").asString());
			assertThat(boostedUntil).isBetween(before.plusHours(24).minusMinutes(1), LocalDateTime.now().plusHours(24).plusMinutes(1));

			assertThat(balance(me.memberId)).isEqualTo(50);
			Map<String, Object> ledger = jdbc.queryForMap("SELECT * FROM point_ledger WHERE id = ?", body.get("ledgerId").asLong());
			assertThat(ledger.get("amount")).isEqualTo(-BOOST_COST);
			assertThat(ledger.get("balance_after")).isEqualTo(50L);
			assertThat(ledger.get("tx_type")).isEqualTo("BOOST_USE");
			assertThat(ledger.get("ref_type")).isEqualTo("QUESTION");
			assertThat(ledger.get("ref_id")).isEqualTo(q.id);
			assertThat(ledger.get("idempotency_key")).isEqualTo(key);
			assertThat(jdbc.queryForObject("SELECT boosted_until FROM question WHERE id = ?", LocalDateTime.class, q.id)).isEqualTo(boostedUntil);
			// 상세의 boosted 도 true
			mockMvc.perform(get("/api/v1/questions/" + q.id).header(HttpHeaders.AUTHORIZATION, bearer(me)))
					.andExpect(jsonPath("$.boosted").value(true));
			assertWalletEqualsLedger(me.memberId);
		}

		@Test
		void 같은_키로_다시_요청하면_최초_응답을_그대로_돌려주고_차감하지_않는다() throws Exception {
			Session me = activeMember();
			grant(me, 300);
			Created q = create(me, TWO_OPTIONS.formatted("멱등"));
			String key = UUID.randomUUID().toString();
			JsonNode first = json(boost(me, q.id, key).andExpect(status().isOk()));

			JsonNode again = json(boost(me, q.id, key).andExpect(status().isOk()));

			assertThat(again).isEqualTo(first);
			assertThat(balance(me.memberId)).isEqualTo(200);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_ledger WHERE idempotency_key = ?", Long.class, key)).isEqualTo(1);
			assertWalletEqualsLedger(me.memberId);
		}

		@Test
		void 같은_키를_다른_고민이나_다른_회원이_쓰면_409_IDEMPOTENCY_KEY_CONFLICT() throws Exception {
			Session me = activeMember();
			grant(me, 300);
			Created q1 = create(me, TWO_OPTIONS.formatted("키 1"));
			Created q2 = create(me, TWO_OPTIONS.formatted("키 2"));
			String key = UUID.randomUUID().toString();
			boost(me, q1.id, key).andExpect(status().isOk());

			boost(me, q2.id, key)
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));

			Session other = activeMember();
			grant(other, 100);
			Created others = create(other, TWO_OPTIONS.formatted("남의 키"));
			boost(other, others.id, key)
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
			assertThat(balance(me.memberId)).isEqualTo(200);
			assertThat(balance(other.memberId)).isEqualTo(100);
		}

		@Test
		void 헤더가_없으면_400_IDEMPOTENCY_KEY_REQUIRED_이고_UUID가_아니면_400_VALIDATION_ERROR() throws Exception {
			Session me = activeMember();
			grant(me, 100);
			Created q = create(me, TWO_OPTIONS.formatted("헤더"));

			mockMvc.perform(post("/api/v1/questions/" + q.id + "/boosts").header(HttpHeaders.AUTHORIZATION, bearer(me)))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
			boost(me, q.id, "vote:1")
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
			assertThat(balance(me.memberId)).isEqualTo(100);
		}

		@Test
		void 잔액이_부족하면_409_POINT_INSUFFICIENT_이고_아무것도_바뀌지_않는다() throws Exception {
			Session me = activeMember();
			grant(me, 99);
			Created q = create(me, TWO_OPTIONS.formatted("부족"));

			boost(me, q.id, UUID.randomUUID().toString())
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("POINT_INSUFFICIENT"));

			assertThat(balance(me.memberId)).isEqualTo(99);
			assertThat(jdbc.queryForObject("SELECT boosted_until FROM question WHERE id = ?", LocalDateTime.class, q.id)).isNull();
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_ledger WHERE member_id = ? AND tx_type = 'BOOST_USE'", Long.class, me.memberId)).isZero();
		}

		@Test
		void 남의_고민은_403_삭제된_고민은_404_종료_HIDDEN_고민은_409() throws Exception {
			Session me = activeMember();
			grant(me, 500);
			Created others = create(activeMember(), TWO_OPTIONS.formatted("남의 글"));
			Created deleted = create(me, TWO_OPTIONS.formatted("삭제"));
			Created closed = create(me, TWO_OPTIONS.formatted("종료"));
			Created hidden = create(me, TWO_OPTIONS.formatted("숨김"));
			mockMvc.perform(delete("/api/v1/questions/" + deleted.id).header(HttpHeaders.AUTHORIZATION, bearer(me))).andExpect(status().isNoContent());
			jdbc.update("UPDATE question SET status = 'CLOSED' WHERE id = ?", closed.id);
			jdbc.update("UPDATE question SET status = 'HIDDEN' WHERE id = ?", hidden.id);

			boost(me, others.id, UUID.randomUUID().toString()).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
			boost(me, deleted.id, UUID.randomUUID().toString()).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("QUESTION_NOT_FOUND"));
			boost(me, closed.id, UUID.randomUUID().toString()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUESTION_CLOSED"));
			boost(me, hidden.id, UUID.randomUUID().toString()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUESTION_CLOSED"));
			assertThat(balance(me.memberId)).isEqualTo(500);
		}

		@Test
		void 이미_노출_중이면_남은_시간에_24시간을_이어_붙인다() throws Exception {
			Session me = activeMember();
			grant(me, 200);
			Created q = create(me, TWO_OPTIONS.formatted("연장"));
			LocalDateTime first = LocalDateTime.parse(json(boost(me, q.id, UUID.randomUUID().toString()).andExpect(status().isOk()))
					.get("boostedUntil").asString());

			LocalDateTime second = LocalDateTime.parse(json(boost(me, q.id, UUID.randomUUID().toString()).andExpect(status().isOk()))
					.get("boostedUntil").asString());

			assertThat(second).isEqualTo(first.plusHours(24));
			assertThat(balance(me.memberId)).isZero();
			assertWalletEqualsLedger(me.memberId);
		}

	}

	// =============================== 헬퍼 ===============================

	private record Session(long memberId, String accessToken) {
	}

	private record Created(long id, List<Long> optionIds) {

		long option(int index) {
			return optionIds.get(index);
		}

	}

	private Session activeMember() throws Exception {
		Session s = activeMemberWithoutWallet();
		jdbc.update("INSERT INTO point_wallet (member_id, balance, version, updated_at) VALUES (?, 0, 0, NOW(6))", s.memberId);
		return s;
	}

	private Session activeMemberWithoutWallet() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String body = "{\"email\":\"pt" + suffix + "@test.com\",\"password\":\"pass1234\",\"nickname\":\"포인트" + suffix + "\"}";
		JsonNode json = json(mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated()));
		long memberId = json.get("member").get("id").asLong();
		jdbc.update("UPDATE member SET signup_status = 'ACTIVE' WHERE id = ?", memberId);
		return new Session(memberId, jwtTokenProvider.createAccessToken(memberId, SignupStatus.ACTIVE));
	}

	/** 테스트용 포인트 지급: 지갑과 원장을 함께 올려 불변식(잔액 = 원장 합계)을 유지한다 */
	private long grant(Session s, long amount) {
		jdbc.update("UPDATE point_wallet SET balance = balance + ? WHERE member_id = ?", amount, s.memberId);
		long balance = balance(s.memberId);
		String key = "test:" + UUID.randomUUID();
		jdbc.update("INSERT INTO point_ledger (member_id, amount, balance_after, tx_type, ref_type, ref_id, idempotency_key, created_at) "
				+ "VALUES (?, ?, ?, 'VOTE_REWARD', NULL, NULL, ?, DATE_SUB(NOW(6), INTERVAL 2 DAY))", s.memberId, amount, balance, key);
		return jdbc.queryForObject("SELECT id FROM point_ledger WHERE idempotency_key = ?", Long.class, key);
	}

	private Created create(Session s, String body) throws Exception {
		JsonNode json = json(mockMvc.perform(post("/api/v1/questions")
				.header(HttpHeaders.AUTHORIZATION, bearer(s))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body)).andExpect(status().isCreated()));
		List<Long> optionIds = new ArrayList<>();
		json.get("options").forEach(o -> optionIds.add(o.get("id").asLong()));
		return new Created(json.get("id").asLong(), optionIds);
	}

	private ResultActions vote(Session s, long questionId, long optionId) throws Exception {
		return mockMvc.perform(post("/api/v1/questions/" + questionId + "/votes")
				.header(HttpHeaders.AUTHORIZATION, bearer(s))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"optionId\":" + optionId + "}"));
	}

	private ResultActions boost(Session s, long questionId, String idempotencyKey) throws Exception {
		MockHttpServletRequestBuilder request = post("/api/v1/questions/" + questionId + "/boosts")
				.header(HttpHeaders.AUTHORIZATION, bearer(s));
		if (idempotencyKey != null) {
			request.header("Idempotency-Key", idempotencyKey);
		}
		return mockMvc.perform(request);
	}

	private long balance(long memberId) {
		return jdbc.queryForObject("SELECT balance FROM point_wallet WHERE member_id = ?", Long.class, memberId);
	}

	/** 불변식: 지갑 잔액 = 원장 amount 합계 */
	private void assertWalletEqualsLedger(long memberId) {
		Long sum = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM point_ledger WHERE member_id = ?", Long.class, memberId);
		assertThat(balance(memberId)).as("지갑 잔액 = 원장 합계").isEqualTo(sum);
	}

	private static String bearer(Session s) {
		return "Bearer " + s.accessToken;
	}

	private JsonNode json(ResultActions actions) throws Exception {
		return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
	}

	private static List<Long> ids(JsonNode page) {
		List<Long> ids = new ArrayList<>();
		page.get("items").forEach(item -> ids.add(item.get("id").asLong()));
		return ids;
	}

}
