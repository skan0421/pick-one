package com.pickone.vote;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 투표·결과 조회 통합 테스트 (docs/api.md 5장) + 상세·내 목록의 투표 결과 반영 (4.3, 4.4) */
@IntegrationTest
class VoteIntegrationTest {

	private static final String TWO_OPTIONS = """
			{"questionType":"TEXT","content":"%s","options":[{"content":"카페"},{"content":"밥집"}]}""";
	private static final String THREE_OPTIONS = """
			{"questionType":"TEXT","content":"%s","options":[{"content":"a"},{"content":"b"},{"content":"c"}]}""";

	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper objectMapper;
	@Autowired JdbcTemplate jdbc;
	@Autowired JwtTokenProvider jwtTokenProvider;
	@Autowired StringRedisTemplate redisTemplate;

	// =============================== 투표 ===============================

	@Nested
	class 투표 {

		@Test
		void 투표하면_201이고_결과_퍼센트와_1P_적립이_돌아온다() throws Exception {
			Session voter = activeMember();
			Created q = create(activeMember(), TWO_OPTIONS.formatted("첫 투표"));

			JsonNode body = json(vote(voter, q.id, q.option(0))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.voteId").isNumber())
					.andExpect(jsonPath("$.result.totalVotes").value(1))
					.andExpect(jsonPath("$.result.myOptionId").value(q.option(0)))
					.andExpect(jsonPath("$.result.options[0].optionId").value(q.option(0)))
					.andExpect(jsonPath("$.result.options[0].count").value(1))
					.andExpect(jsonPath("$.result.options[0].percent").value(100.0))
					.andExpect(jsonPath("$.result.options[1].count").value(0))
					.andExpect(jsonPath("$.result.options[1].percent").value(0.0))
					.andExpect(jsonPath("$.pointReward.earned").value(true))
					.andExpect(jsonPath("$.pointReward.amount").value(1))
					.andExpect(jsonPath("$.pointReward.reason").doesNotExist()));
			long voteId = body.get("voteId").asLong();

			// 지갑 +1, 원장 1행 (vote:{voteId}), 커밋 후 Redis 일일 카운터 +1
			assertThat(balance(voter.memberId)).isEqualTo(1);
			Map<String, Object> ledger = jdbc.queryForMap("SELECT * FROM point_ledger WHERE member_id = ?", voter.memberId);
			assertThat(ledger.get("amount")).isEqualTo(1L);
			assertThat(ledger.get("balance_after")).isEqualTo(1L);
			assertThat(ledger.get("tx_type")).isEqualTo("VOTE_REWARD");
			assertThat(ledger.get("ref_type")).isEqualTo("VOTE");
			assertThat(ledger.get("ref_id")).isEqualTo(voteId);
			assertThat(ledger.get("idempotency_key")).isEqualTo("vote:" + voteId);
			assertThat(redisTemplate.opsForValue().get(PointDailyCounterStore.key(voter.memberId, KstDates.today()))).isEqualTo("1");
			assertWalletEqualsLedger(voter.memberId);
		}

		@Test
		void 같은_고민에_두_번_투표하면_409_VOTE_ALREADY_VOTED_이고_포인트도_한_번만_적립된다() throws Exception {
			Session voter = activeMember();
			Created q = create(activeMember(), TWO_OPTIONS.formatted("중복"));
			vote(voter, q.id, q.option(0)).andExpect(status().isCreated());

			vote(voter, q.id, q.option(1))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("VOTE_ALREADY_VOTED"));

			assertThat(balance(voter.memberId)).isEqualTo(1);
			assertThat(voteCount(q.id)).isEqualTo(1);
			assertWalletEqualsLedger(voter.memberId);
		}

		@Test
		void 다른_고민의_선택지를_고르면_400_VOTE_OPTION_MISMATCH() throws Exception {
			Session voter = activeMember();
			Session author = activeMember();
			Created q1 = create(author, TWO_OPTIONS.formatted("하나"));
			Created q2 = create(author, TWO_OPTIONS.formatted("둘"));

			vote(voter, q1.id, q2.option(0))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("VOTE_OPTION_MISMATCH"));
			assertThat(voteCount(q1.id)).isZero();
			assertThat(balance(voter.memberId)).isZero();
		}

		@Test
		void 자기_고민에_투표하면_403_VOTE_OWN_QUESTION() throws Exception {
			Session me = activeMember();
			Created q = create(me, TWO_OPTIONS.formatted("내 글"));

			vote(me, q.id, q.option(0))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.code").value("VOTE_OWN_QUESTION"));
		}

		@Test
		void 삭제_HIDDEN_차단_관계인_고민은_404_QUESTION_NOT_FOUND() throws Exception {
			Session voter = activeMember();
			Session author = activeMember();
			Created deleted = create(author, TWO_OPTIONS.formatted("삭제"));
			Created hidden = create(author, TWO_OPTIONS.formatted("숨김"));
			Created blocked = create(activeMember(), TWO_OPTIONS.formatted("차단"));
			mockMvc.perform(delete("/api/v1/questions/" + deleted.id).header(HttpHeaders.AUTHORIZATION, bearer(author)))
					.andExpect(status().isNoContent());
			jdbc.update("UPDATE question SET status = 'HIDDEN' WHERE id = ?", hidden.id);
			Long blockedAuthor = jdbc.queryForObject("SELECT member_id FROM question WHERE id = ?", Long.class, blocked.id);
			jdbc.update("INSERT INTO member_block (blocker_id, blocked_id, created_at) VALUES (?, ?, NOW(6))", blockedAuthor, voter.memberId);

			for (Created q : List.of(deleted, hidden, blocked)) {
				vote(voter, q.id, q.option(0))
						.andExpect(status().isNotFound())
						.andExpect(jsonPath("$.code").value("QUESTION_NOT_FOUND"));
			}
			vote(voter, 999_999_999L, 1L).andExpect(status().isNotFound());
		}

		@Test
		void 종료된_고민은_409_QUESTION_CLOSED() throws Exception {
			Session voter = activeMember();
			Created q = create(activeMember(), TWO_OPTIONS.formatted("종료"));
			jdbc.update("UPDATE question SET status = 'CLOSED' WHERE id = ?", q.id);

			vote(voter, q.id, q.option(0))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("QUESTION_CLOSED"));
		}

		@Test
		void 지갑이_없는_회원은_409_POINT_WALLET_NOT_FOUND_이고_투표도_저장되지_않는다() throws Exception {
			Session noWallet = activeMemberWithoutWallet();
			Created q = create(activeMember(), TWO_OPTIONS.formatted("지갑 없음"));

			vote(noWallet, q.id, q.option(0))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("POINT_WALLET_NOT_FOUND"));
			assertThat(voteCount(q.id)).isZero();
		}

		@Test
		void 일일_적립_상한에_도달하면_투표는_되지만_적립은_없다() throws Exception {
			Session voter = activeMember();
			Created q = create(activeMember(), TWO_OPTIONS.formatted("상한"));
			// 오늘 이미 50P 적립한 상태를 원장 + 지갑에 만든다 (판정 근거는 원장 SUM)
			jdbc.update("INSERT INTO point_ledger (member_id, amount, balance_after, tx_type, ref_type, ref_id, idempotency_key, created_at) "
					+ "VALUES (?, 50, 50, 'VOTE_REWARD', 'VOTE', 0, ?, NOW(6))", voter.memberId, "test:" + UUID.randomUUID());
			jdbc.update("UPDATE point_wallet SET balance = 50 WHERE member_id = ?", voter.memberId);

			vote(voter, q.id, q.option(0))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.result.totalVotes").value(1))
					.andExpect(jsonPath("$.pointReward.earned").value(false))
					.andExpect(jsonPath("$.pointReward.amount").value(0))
					.andExpect(jsonPath("$.pointReward.reason").value("DAILY_LIMIT_REACHED"));

			assertThat(balance(voter.memberId)).isEqualTo(50);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_ledger WHERE member_id = ?", Long.class, voter.memberId)).isEqualTo(1);
			assertThat(redisTemplate.hasKey(PointDailyCounterStore.key(voter.memberId, KstDates.today()))).isFalse();
			assertWalletEqualsLedger(voter.memberId);
		}

		@Test
		void optionId가_없으면_400_VALIDATION_ERROR() throws Exception {
			Created q = create(activeMember(), TWO_OPTIONS.formatted("검증"));

			mockMvc.perform(post("/api/v1/questions/" + q.id + "/votes")
					.header(HttpHeaders.AUTHORIZATION, bearer(activeMember()))
					.contentType(MediaType.APPLICATION_JSON).content("{}"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
					.andExpect(jsonPath("$.errors[0].field").value("optionId"));
		}

		@Test
		void 퍼센트는_소수점_1자리이고_반올림_합이_100이_되도록_최다_득표_항목에서_보정한다() throws Exception {
			Session author = activeMember();
			Created q = create(author, THREE_OPTIONS.formatted("3택"));
			for (int i = 0; i < 3; i++) {
				vote(activeMember(), q.id, q.option(i)).andExpect(status().isCreated());
			}

			JsonNode result = json(results(author, q.id).andExpect(status().isOk()));
			List<Double> percents = new ArrayList<>();
			result.get("options").forEach(o -> percents.add(o.get("percent").asDouble()));
			assertThat(percents).containsExactly(33.4, 33.3, 33.3); // 동률이면 앞 순서에서 보정
			assertThat(percents.stream().mapToDouble(Double::doubleValue).sum()).isEqualTo(100.0);
		}

	}

	// =============================== 결과 조회 ===============================

	@Nested
	class 결과_조회 {

		@Test
		void 투표한_사람은_결과와_자기_선택을_본다() throws Exception {
			Session voter = activeMember();
			Created q = create(activeMember(), TWO_OPTIONS.formatted("결과"));
			vote(voter, q.id, q.option(1)).andExpect(status().isCreated());
			vote(activeMember(), q.id, q.option(1)).andExpect(status().isCreated());

			results(voter, q.id)
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.questionId").value(q.id))
					.andExpect(jsonPath("$.totalVotes").value(2))
					.andExpect(jsonPath("$.myOptionId").value(q.option(1)))
					.andExpect(jsonPath("$.options[0].optionId").value(q.option(0)))
					.andExpect(jsonPath("$.options[0].sortOrder").value(1))
					.andExpect(jsonPath("$.options[0].content").value("카페"))
					.andExpect(jsonPath("$.options[0].count").value(0))
					.andExpect(jsonPath("$.options[0].percent").value(0.0))
					.andExpect(jsonPath("$.options[1].count").value(2))
					.andExpect(jsonPath("$.options[1].percent").value(100.0));
		}

		@Test
		void 작성자는_결과를_보지만_myOptionId는_없다() throws Exception {
			Session author = activeMember();
			Created q = create(author, TWO_OPTIONS.formatted("작성자 결과"));
			vote(activeMember(), q.id, q.option(0)).andExpect(status().isCreated());

			results(author, q.id)
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.totalVotes").value(1))
					.andExpect(jsonPath("$.myOptionId").doesNotExist());
		}

		@Test
		void 투표하지_않은_제3자는_403_RESULT_NOT_ALLOWED() throws Exception {
			Created q = create(activeMember(), TWO_OPTIONS.formatted("제3자"));

			results(activeMember(), q.id)
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.code").value("RESULT_NOT_ALLOWED"));
		}

		@Test
		void 삭제된_고민의_결과는_404() throws Exception {
			Session voter = activeMember();
			Session author = activeMember();
			Created q = create(author, TWO_OPTIONS.formatted("삭제 후 결과"));
			vote(voter, q.id, q.option(0)).andExpect(status().isCreated());
			mockMvc.perform(delete("/api/v1/questions/" + q.id).header(HttpHeaders.AUTHORIZATION, bearer(author))).andExpect(status().isNoContent());

			results(voter, q.id).andExpect(status().isNotFound());
		}

	}

	// =============================== 상세·내 목록 반영 ===============================

	@Nested
	class 상세와_내_목록 {

		@Test
		void 투표한_뒤_상세를_보면_myVote와_result가_채워진다() throws Exception {
			Session voter = activeMember();
			Created q = create(activeMember(), TWO_OPTIONS.formatted("상세"));
			vote(voter, q.id, q.option(0)).andExpect(status().isCreated());

			detail(voter, q.id)
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.myVote.optionId").value(q.option(0)))
					.andExpect(jsonPath("$.result.totalVotes").value(1))
					.andExpect(jsonPath("$.result.options[0].optionId").value(q.option(0)))
					.andExpect(jsonPath("$.result.options[0].count").value(1))
					.andExpect(jsonPath("$.result.options[0].percent").value(100.0))
					.andExpect(jsonPath("$.result.options[1].count").value(0));
		}

		@Test
		void 작성자_상세에는_result만_있고_투표_전_제3자에게는_둘_다_없다() throws Exception {
			Session author = activeMember();
			Created q = create(author, TWO_OPTIONS.formatted("작성자 상세"));
			vote(activeMember(), q.id, q.option(1)).andExpect(status().isCreated());

			detail(author, q.id)
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.isMine").value(true))
					.andExpect(jsonPath("$.myVote").doesNotExist())
					.andExpect(jsonPath("$.result.totalVotes").value(1));

			detail(activeMember(), q.id)
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.myVote").doesNotExist())
					.andExpect(jsonPath("$.result").doesNotExist());
		}

		@Test
		void 내_고민_목록에는_고민별_총_투표수와_선택지별_득표가_채워진다() throws Exception {
			Session author = activeMember();
			Created voted = create(author, TWO_OPTIONS.formatted("투표됨"));
			Created untouched = create(author, TWO_OPTIONS.formatted("투표 없음"));
			vote(activeMember(), voted.id, voted.option(0)).andExpect(status().isCreated());
			vote(activeMember(), voted.id, voted.option(0)).andExpect(status().isCreated());
			vote(activeMember(), voted.id, voted.option(1)).andExpect(status().isCreated());

			JsonNode page = json(mockMvc.perform(get("/api/v1/members/me/questions").header(HttpHeaders.AUTHORIZATION, bearer(author)))
					.andExpect(status().isOk()));
			JsonNode first = page.get("items").get(0); // 최신순: untouched
			JsonNode second = page.get("items").get(1);
			assertThat(first.get("id").asLong()).isEqualTo(untouched.id);
			assertThat(first.get("totalVotes").asLong()).isZero();
			assertThat(first.get("options").get(0).get("percent").asDouble()).isEqualTo(0.0);
			assertThat(second.get("id").asLong()).isEqualTo(voted.id);
			assertThat(second.get("totalVotes").asLong()).isEqualTo(3);
			assertThat(second.get("options").get(0).get("count").asLong()).isEqualTo(2);
			assertThat(second.get("options").get(0).get("percent").asDouble()).isEqualTo(66.7);
			assertThat(second.get("options").get(1).get("count").asLong()).isEqualTo(1);
			assertThat(second.get("options").get(1).get("percent").asDouble()).isEqualTo(33.3);
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

	/** 가입 → ACTIVE → 지갑(0P) 생성. 휴대폰 인증 흐름은 PhoneVerificationIntegrationTest 가 검증한다 */
	private Session activeMember() throws Exception {
		Session s = activeMemberWithoutWallet();
		jdbc.update("INSERT INTO point_wallet (member_id, balance, version, updated_at) VALUES (?, 0, 0, NOW(6))", s.memberId);
		return s;
	}

	private Session activeMemberWithoutWallet() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String body = "{\"email\":\"vt" + suffix + "@test.com\",\"password\":\"pass1234\",\"nickname\":\"투표" + suffix + "\"}";
		JsonNode json = json(mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated()));
		long memberId = json.get("member").get("id").asLong();
		jdbc.update("UPDATE member SET signup_status = 'ACTIVE' WHERE id = ?", memberId);
		return new Session(memberId, jwtTokenProvider.createAccessToken(memberId, SignupStatus.ACTIVE));
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

	private ResultActions results(Session s, long questionId) throws Exception {
		return mockMvc.perform(get("/api/v1/questions/" + questionId + "/results").header(HttpHeaders.AUTHORIZATION, bearer(s)));
	}

	private ResultActions detail(Session s, long questionId) throws Exception {
		return mockMvc.perform(get("/api/v1/questions/" + questionId).header(HttpHeaders.AUTHORIZATION, bearer(s)));
	}

	private long balance(long memberId) {
		return jdbc.queryForObject("SELECT balance FROM point_wallet WHERE member_id = ?", Long.class, memberId);
	}

	private long voteCount(long questionId) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM vote WHERE question_id = ?", Long.class, questionId);
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

}
