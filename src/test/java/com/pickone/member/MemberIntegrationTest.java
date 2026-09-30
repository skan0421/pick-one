package com.pickone.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pickone.global.crypto.PhoneNumber;
import com.pickone.support.IntegrationTest;
import com.pickone.support.RecordingSmsSender;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 내 정보 조회·수정 통합 테스트 (docs/api.md 2.7, 2.8). 인증 실패 케이스는 SecurityIntegrationTest 에서 다룬다 */
@IntegrationTest
class MemberIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ObjectMapper objectMapper;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	RecordingSmsSender smsSender;

	@Test
	void 토큰_없이_내_정보를_조회하면_401_AUTH_INVALID_TOKEN() throws Exception {
		mockMvc.perform(get("/api/v1/members/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));
	}

	@Test
	void PENDING_PHONE_회원도_내_정보를_조회할_수_있다() throws Exception {
		String email = uniqueEmail();
		String nickname = uniqueNickname();
		String token = signupAndGetToken(email, nickname);

		mockMvc.perform(get("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value(email))
				.andExpect(jsonPath("$.nickname").value(nickname))
				.andExpect(jsonPath("$.provider").value("EMAIL"))
				.andExpect(jsonPath("$.signupStatus").value("PENDING_PHONE"))
				.andExpect(jsonPath("$.phoneVerified").value(false))
				.andExpect(jsonPath("$.hideFromContacts").value(false))
				.andExpect(jsonPath("$.pointBalance").value(0))
				.andExpect(jsonPath("$.createdAt").isString());
	}

	@Test
	void 닉네임을_변경하면_200과_바뀐_정보를_받는다() throws Exception {
		String token = signupAndGetToken(uniqueEmail(), uniqueNickname());
		String newNickname = uniqueNickname();

		mockMvc.perform(patch("/api/v1/members/me")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"nickname\":\"" + newNickname + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.nickname").value(newNickname));

		mockMvc.perform(get("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(jsonPath("$.nickname").value(newNickname));
	}

	@Test
	void 다른_회원이_쓰는_닉네임으로_바꾸면_409_MEMBER_NICKNAME_DUPLICATE() throws Exception {
		String takenNickname = uniqueNickname();
		signupAndGetToken(uniqueEmail(), takenNickname);
		String token = signupAndGetToken(uniqueEmail(), uniqueNickname());

		mockMvc.perform(patch("/api/v1/members/me")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"nickname\":\"" + takenNickname + "\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("MEMBER_NICKNAME_DUPLICATE"));
	}

	@Test
	void 닉네임_형식이_잘못되면_400_VALIDATION_ERROR() throws Exception {
		String token = signupAndGetToken(uniqueEmail(), uniqueNickname());

		mockMvc.perform(patch("/api/v1/members/me")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"nickname\":\"a\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[0].field").value("nickname"));
	}

	// =============================== pointBalance ===============================

	@Nested
	class 포인트_잔액 {

		@Test
		void 휴대폰_인증을_마친_직후에는_지갑이_생기고_pointBalance_는_0이다() throws Exception {
			Session me = activeMember();

			getMe(me).andExpect(status().isOk())
					.andExpect(jsonPath("$.signupStatus").value("ACTIVE"))
					.andExpect(jsonPath("$.phoneVerified").value(true))
					.andExpect(jsonPath("$.pointBalance").value(0));
			assertThat(walletCount(me.memberId)).isEqualTo(1);
		}

		@Test
		void 투표로_적립하면_pointBalance_가_원장_합계와_같다() throws Exception {
			Session me = activeMember();
			Session author = activeMember();
			for (int i = 0; i < 3; i++) {
				vote(me, createQuestion(author, "적립 " + i)).andExpect(status().isCreated());
			}

			getMe(me).andExpect(status().isOk()).andExpect(jsonPath("$.pointBalance").value(3));
			assertPointBalanceMatchesLedger(me, 3);
		}

		@Test
		void 상단_노출로_차감한_뒤에도_pointBalance_가_원장_합계와_같다() throws Exception {
			Session me = activeMember();
			Session author = activeMember();
			vote(me, createQuestion(author, "적립")).andExpect(status().isCreated());
			vote(me, createQuestion(author, "적립 둘")).andExpect(status().isCreated());
			grant(me, 150); // 2 + 150 = 152
			JsonNode mine = createQuestion(me, "내 고민");

			mockMvc.perform(post("/api/v1/questions/" + mine.get("id").asLong() + "/boosts")
							.header(HttpHeaders.AUTHORIZATION, bearer(me))
							.header("Idempotency-Key", UUID.randomUUID().toString()))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.balanceAfter").value(52));

			getMe(me).andExpect(status().isOk()).andExpect(jsonPath("$.pointBalance").value(52));
			assertPointBalanceMatchesLedger(me, 52);
		}

		@Test
		void 남의_포인트는_내_pointBalance_에_섞이지_않는다() throws Exception {
			Session me = activeMember();
			Session other = activeMember();
			vote(other, createQuestion(me, "남이 투표")).andExpect(status().isCreated());

			getMe(me).andExpect(jsonPath("$.pointBalance").value(0));
			getMe(other).andExpect(jsonPath("$.pointBalance").value(1));
		}

		@Test
		void PATCH_응답의_pointBalance_도_실제_잔액이다() throws Exception {
			Session me = activeMember();
			vote(me, createQuestion(activeMember(), "적립")).andExpect(status().isCreated());

			patchMe(me.accessToken, "{\"nickname\":\"" + uniqueNickname() + "\"}")
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.pointBalance").value(1));
		}

		/** GET /members/me 의 pointBalance = GET /points/balance 의 balance = 원장 합계 = 지갑 잔액 */
		private void assertPointBalanceMatchesLedger(Session s, long expected) throws Exception {
			long fromMe = json(getMe(s)).get("pointBalance").asLong();
			long fromPoints = json(mockMvc.perform(get("/api/v1/points/balance").header(HttpHeaders.AUTHORIZATION, bearer(s)))
					.andExpect(status().isOk())).get("balance").asLong();
			Long ledgerSum = jdbc.queryForObject(
					"SELECT COALESCE(SUM(amount), 0) FROM point_ledger WHERE member_id = ?", Long.class, s.memberId);
			Long wallet = jdbc.queryForObject("SELECT balance FROM point_wallet WHERE member_id = ?", Long.class, s.memberId);

			assertThat(fromMe).isEqualTo(expected);
			assertThat(fromMe).as("pointBalance = 원장 합계").isEqualTo(ledgerSum);
			assertThat(fromMe).as("pointBalance = 지갑 잔액").isEqualTo(wallet);
			assertThat(fromMe).as("pointBalance = /points/balance").isEqualTo(fromPoints);
		}

	}

	// =============================== 부분 수정 ===============================

	@Nested
	class 부분_수정 {

		@Test
		void nickname_없이_빈_본문을_보내면_200이고_아무것도_바뀌지_않는다() throws Exception {
			String nickname = uniqueNickname();
			String token = signupAndGetToken(uniqueEmail(), nickname);

			patchMe(token, "{}")
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.nickname").value(nickname))
					.andExpect(jsonPath("$.hideFromContacts").value(false));

			mockMvc.perform(get("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
					.andExpect(jsonPath("$.nickname").value(nickname));
		}

		@Test
		void nickname_없이_다른_필드만_보내도_200이고_닉네임은_그대로다() throws Exception {
			String nickname = uniqueNickname();
			String token = signupAndGetToken(uniqueEmail(), nickname);

			// PATCH 로 바꿀 수 있는 필드는 nickname 뿐이다. 나머지는 받아도 바꾸지 않는다
			patchMe(token, "{\"hideFromContacts\":true,\"pointBalance\":9999,\"email\":\"x@test.com\"}")
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.nickname").value(nickname))
					.andExpect(jsonPath("$.hideFromContacts").value(false))
					.andExpect(jsonPath("$.pointBalance").value(0));
		}

		@Test
		void nickname_이_null_이면_보내지_않은_것으로_본다() throws Exception {
			String nickname = uniqueNickname();
			String token = signupAndGetToken(uniqueEmail(), nickname);

			patchMe(token, "{\"nickname\":null}")
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.nickname").value(nickname));
		}

		@Test
		void nickname_을_다른_필드와_함께_보내면_닉네임만_바뀐다() throws Exception {
			String token = signupAndGetToken(uniqueEmail(), uniqueNickname());
			String newNickname = uniqueNickname();

			patchMe(token, "{\"nickname\":\"" + newNickname + "\",\"hideFromContacts\":true}")
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.nickname").value(newNickname))
					.andExpect(jsonPath("$.hideFromContacts").value(false));
		}

		@Test
		void 지금_쓰는_닉네임을_그대로_보내면_200이다() throws Exception {
			String nickname = uniqueNickname();
			String token = signupAndGetToken(uniqueEmail(), nickname);

			patchMe(token, "{\"nickname\":\"" + nickname + "\"}")
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.nickname").value(nickname));
		}

		@Test
		void nickname_을_빈_문자열이나_공백으로_보내면_400_VALIDATION_ERROR() throws Exception {
			String nickname = uniqueNickname();
			String token = signupAndGetToken(uniqueEmail(), nickname);

			for (String blank : new String[] {"", "  "}) {
				patchMe(token, "{\"nickname\":\"" + blank + "\"}")
						.andExpect(status().isBadRequest())
						.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
						.andExpect(jsonPath("$.errors[0].field").value("nickname"));
			}
			mockMvc.perform(get("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
					.andExpect(jsonPath("$.nickname").value(nickname));
		}

		@Test
		void nickname_을_보내면_길이와_문자_규칙을_그대로_검사한다() throws Exception {
			String token = signupAndGetToken(uniqueEmail(), uniqueNickname());

			// 1자, 31자, 허용하지 않는 문자
			for (String invalid : new String[] {"a", "가".repeat(31), "닉 네임", "nick!"}) {
				patchMe(token, "{\"nickname\":\"" + invalid + "\"}")
						.andExpect(status().isBadRequest())
						.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
						.andExpect(jsonPath("$.errors[0].field").value("nickname"));
			}
			// 경계값 30자는 통과한다
			patchMe(token, "{\"nickname\":\"" + uniqueNickname(30) + "\"}").andExpect(status().isOk());
		}

		@Test
		void 본문이_없으면_400이다() throws Exception {
			String token = signupAndGetToken(uniqueEmail(), uniqueNickname());

			mockMvc.perform(patch("/api/v1/members/me")
							.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
							.contentType(MediaType.APPLICATION_JSON))
					.andExpect(status().isBadRequest());
		}

	}

	// =============================== 헬퍼 ===============================

	private record Session(long memberId, String accessToken) {
	}

	/** 가입 → 인증번호 발송 → 확인까지 API 로 마친 ACTIVE 회원. 지갑은 확인 트랜잭션에서 서버가 만든다 */
	private Session activeMember() throws Exception {
		JsonNode signup = json(mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + uniqueEmail() + "\",\"password\":\"pass1234\",\"nickname\":\"" + uniqueNickname() + "\"}"))
				.andExpect(status().isCreated()));
		long memberId = signup.get("member").get("id").asLong();
		String pendingToken = signup.get("accessToken").asString();
		String phone = uniquePhone();

		mockMvc.perform(post("/api/v1/phone-verifications")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + pendingToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"phone\":\"" + phone + "\"}"))
				.andExpect(status().isAccepted());
		String code = smsSender.lastCodeFor(PhoneNumber.toE164(phone));
		JsonNode tokens = json(mockMvc.perform(post("/api/v1/phone-verifications/confirm")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + pendingToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\"}"))
				.andExpect(status().isOk()));
		return new Session(memberId, tokens.get("accessToken").asString());
	}

	private ResultActions getMe(Session s) throws Exception {
		return mockMvc.perform(get("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, bearer(s)));
	}

	private ResultActions patchMe(String token, String body) throws Exception {
		return mockMvc.perform(patch("/api/v1/members/me")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private JsonNode createQuestion(Session s, String content) throws Exception {
		String body = "{\"questionType\":\"TEXT\",\"content\":\"" + content + "\",\"options\":[{\"content\":\"카페\"},{\"content\":\"밥집\"}]}";
		return json(mockMvc.perform(post("/api/v1/questions")
						.header(HttpHeaders.AUTHORIZATION, bearer(s))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isCreated()));
	}

	/** 고민의 첫 선택지에 투표한다 (+1P) */
	private ResultActions vote(Session s, JsonNode question) throws Exception {
		long optionId = question.get("options").get(0).get("id").asLong();
		return mockMvc.perform(post("/api/v1/questions/" + question.get("id").asLong() + "/votes")
				.header(HttpHeaders.AUTHORIZATION, bearer(s))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"optionId\":" + optionId + "}"));
	}

	/**
	 * 테스트용 포인트 지급 (PointIntegrationTest 와 같은 방식). 일일 적립 상한(50P) 때문에 투표만으로는 100P 를 모을 수 없어,
	 * 지갑과 원장을 함께 올려 불변식(잔액 = 원장 합계)을 유지한다. 테스트 컨테이너의 DB 에만 쓴다
	 */
	private void grant(Session s, long amount) {
		jdbc.update("UPDATE point_wallet SET balance = balance + ?, version = version + 1 WHERE member_id = ?", amount, s.memberId);
		Long balance = jdbc.queryForObject("SELECT balance FROM point_wallet WHERE member_id = ?", Long.class, s.memberId);
		jdbc.update("INSERT INTO point_ledger (member_id, amount, balance_after, tx_type, ref_type, ref_id, idempotency_key, created_at) "
				+ "VALUES (?, ?, ?, 'VOTE_REWARD', NULL, NULL, ?, DATE_SUB(NOW(6), INTERVAL 2 DAY))",
				s.memberId, amount, balance, "test:" + UUID.randomUUID());
	}

	private int walletCount(long memberId) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM point_wallet WHERE member_id = ?", Integer.class, memberId);
	}

	private JsonNode json(ResultActions actions) throws Exception {
		return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
	}

	private static String bearer(Session s) {
		return "Bearer " + s.accessToken;
	}

	private String signupAndGetToken(String email, String nickname) throws Exception {
		String body = "{\"email\":\"" + email + "\",\"password\":\"pass1234\",\"nickname\":\"" + nickname + "\"}";
		String response = mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(response).get("accessToken").asString();
	}

	private static String uniqueEmail() {
		return "m" + UUID.randomUUID().toString().substring(0, 8) + "@test.com";
	}

	private static String uniqueNickname() {
		return "멤" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");
	}

	/** 정확히 length 자인 닉네임 (영문·숫자) */
	private static String uniqueNickname(int length) {
		return UUID.randomUUID().toString().replace("-", "").substring(0, length);
	}

	/** 테스트마다 다른 번호. 010-XXXX-XXXX 형태 */
	private static String uniquePhone() {
		int a = ThreadLocalRandom.current().nextInt(1000, 10000);
		int b = ThreadLocalRandom.current().nextInt(1000, 10000);
		return "010-" + a + "-" + b;
	}

}
