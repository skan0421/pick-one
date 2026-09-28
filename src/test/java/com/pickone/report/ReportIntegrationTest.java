package com.pickone.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pickone.global.security.jwt.JwtTokenProvider;
import com.pickone.member.domain.SignupStatus;
import com.pickone.support.ConcurrencyTestSupport;
import com.pickone.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 고민 신고·자동 숨김 통합 테스트 (docs/api.md 8.4). 자동 숨김 기준은 설정값 5건 */
@IntegrationTest
class ReportIntegrationTest {

	private static final int THRESHOLD = 5;
	private static final String QUESTION_BODY = """
			{"questionType":"TEXT","content":"%s","options":[{"content":"카페"},{"content":"밥집"}]}""";

	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper objectMapper;
	@Autowired JdbcTemplate jdbc;
	@Autowired JwtTokenProvider jwtTokenProvider;

	@Nested
	class 신고 {

		@Test
		void 신고하면_201이고_RECEIVED_상태로_저장된다() throws Exception {
			long questionId = createQuestion(activeMember());

			JsonNode body = json(report(activeMember(), questionId, "{\"reason\":\"PERSONAL_INFO\",\"detail\":\"캡처에 이름이 보여요\"}")
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.reportId").isNumber())
					.andExpect(jsonPath("$.status").value("RECEIVED")));

			Map<String, Object> row = jdbc.queryForMap("SELECT * FROM report WHERE id = ?", body.get("reportId").asLong());
			assertThat(row.get("reason")).isEqualTo("PERSONAL_INFO");
			assertThat(row.get("detail")).isEqualTo("캡처에 이름이 보여요");
			assertThat(row.get("status")).isEqualTo("RECEIVED");
			assertThat(questionStatus(questionId)).isEqualTo("ACTIVE");
		}

		@Test
		void 같은_고민을_다시_신고하면_409_REPORT_DUPLICATE() throws Exception {
			long questionId = createQuestion(activeMember());
			Session reporter = activeMember();
			report(reporter, questionId, "{\"reason\":\"SPAM\"}").andExpect(status().isCreated());

			report(reporter, questionId, "{\"reason\":\"ABUSE\"}")
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("REPORT_DUPLICATE"));
			assertThat(reportCount(questionId)).isEqualTo(1);
		}

		@Test
		void 자기_고민을_신고하면_400_REPORT_OWN_QUESTION() throws Exception {
			Session me = activeMember();
			long questionId = createQuestion(me);

			report(me, questionId, "{\"reason\":\"SPAM\"}")
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("REPORT_OWN_QUESTION"));
		}

		@Test
		void 삭제됐거나_없는_고민은_404_QUESTION_NOT_FOUND() throws Exception {
			Session author = activeMember();
			long deleted = createQuestion(author);
			mockMvc.perform(delete("/api/v1/questions/" + deleted).header(HttpHeaders.AUTHORIZATION, bearer(author))).andExpect(status().isNoContent());

			report(activeMember(), deleted, "{\"reason\":\"SPAM\"}")
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.code").value("QUESTION_NOT_FOUND"));
			report(activeMember(), 999_999_999L, "{\"reason\":\"SPAM\"}").andExpect(status().isNotFound());
		}

		@Test
		void 사유가_없거나_잘못됐거나_상세가_200자를_넘으면_400_VALIDATION_ERROR() throws Exception {
			long questionId = createQuestion(activeMember());

			report(activeMember(), questionId, "{}")
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
			report(activeMember(), questionId, "{\"reason\":\"WHATEVER\"}")
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
			report(activeMember(), questionId, "{\"reason\":\"ETC\",\"detail\":\"" + "가".repeat(201) + "\"}")
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
			assertThat(reportCount(questionId)).isZero();
		}

	}

	@Nested
	class 자동_숨김 {

		@Test
		void 신고가_기준에_도달하면_HIDDEN이_되고_피드와_상세에서_사라진다() throws Exception {
			Session viewer = activeMember();
			long questionId = createQuestion(activeMember());

			for (int i = 0; i < THRESHOLD - 1; i++) {
				report(activeMember(), questionId, "{\"reason\":\"SPAM\"}").andExpect(status().isCreated());
			}
			assertThat(questionStatus(questionId)).isEqualTo("ACTIVE");
			assertThat(feedIds(viewer)).contains(questionId);

			report(activeMember(), questionId, "{\"reason\":\"SPAM\"}").andExpect(status().isCreated());

			assertThat(questionStatus(questionId)).isEqualTo("HIDDEN");
			assertThat(feedIds(viewer)).doesNotContain(questionId);
			mockMvc.perform(get("/api/v1/questions/" + questionId).header(HttpHeaders.AUTHORIZATION, bearer(viewer)))
					.andExpect(status().isNotFound());
			// 이미 숨겨진 고민도 신고는 접수된다
			report(activeMember(), questionId, "{\"reason\":\"ABUSE\"}").andExpect(status().isCreated());
			assertThat(reportCount(questionId)).isEqualTo(THRESHOLD + 1);
		}

		@Test
		void 기준_직전에_여러_사람이_동시에_신고해도_정확히_HIDDEN으로_바뀐다() throws Exception {
			long questionId = createQuestion(activeMember());
			int existing = THRESHOLD - 2; // 3건 있는 상태에서 3건 동시 → 5건째가 동시 요청 안에서 겹친다
			for (int i = 0; i < existing; i++) {
				report(activeMember(), questionId, "{\"reason\":\"SPAM\"}").andExpect(status().isCreated());
			}
			List<Callable<MockHttpServletResponse>> tasks = new ArrayList<>();
			for (int i = 0; i < 3; i++) {
				Session reporter = activeMember();
				tasks.add(() -> report(reporter, questionId, "{\"reason\":\"SPAM\"}").andReturn().getResponse());
			}

			List<MockHttpServletResponse> responses = ConcurrencyTestSupport.runConcurrently(tasks);

			assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsOnly(201);
			assertThat(reportCount(questionId)).isEqualTo(existing + 3);
			assertThat(questionStatus(questionId)).isEqualTo("HIDDEN");
			System.out.printf("[동시성] 신고 %d건 있는 고민에 동시 신고 3건 → 성공 3건, status=%s%n", existing, questionStatus(questionId));
		}

		@Test
		void 처음부터_기준_수만큼_동시에_신고해도_HIDDEN으로_바뀐다() throws Exception {
			long questionId = createQuestion(activeMember());
			List<Callable<MockHttpServletResponse>> tasks = new ArrayList<>();
			for (int i = 0; i < THRESHOLD; i++) {
				Session reporter = activeMember();
				tasks.add(() -> report(reporter, questionId, "{\"reason\":\"ABUSE\"}").andReturn().getResponse());
			}

			List<MockHttpServletResponse> responses = ConcurrencyTestSupport.runConcurrently(tasks);

			assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsOnly(201);
			assertThat(reportCount(questionId)).isEqualTo(THRESHOLD);
			assertThat(questionStatus(questionId)).isEqualTo("HIDDEN");
		}

		@Test
		void 기준_미만이면_동시에_신고해도_ACTIVE로_남는다() throws Exception {
			long questionId = createQuestion(activeMember());
			List<Callable<MockHttpServletResponse>> tasks = new ArrayList<>();
			for (int i = 0; i < THRESHOLD - 1; i++) {
				Session reporter = activeMember();
				tasks.add(() -> report(reporter, questionId, "{\"reason\":\"ETC\"}").andReturn().getResponse());
			}

			ConcurrencyTestSupport.runConcurrently(tasks);

			assertThat(reportCount(questionId)).isEqualTo(THRESHOLD - 1);
			assertThat(questionStatus(questionId)).isEqualTo("ACTIVE");
		}

	}

	// =============================== 헬퍼 ===============================

	private record Session(long memberId, String accessToken) {
	}

	private Session activeMember() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String body = "{\"email\":\"rp" + suffix + "@test.com\",\"password\":\"pass1234\",\"nickname\":\"신고" + suffix + "\"}";
		JsonNode json = json(mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated()));
		long memberId = json.get("member").get("id").asLong();
		jdbc.update("UPDATE member SET signup_status = 'ACTIVE' WHERE id = ?", memberId);
		return new Session(memberId, jwtTokenProvider.createAccessToken(memberId, SignupStatus.ACTIVE));
	}

	private long createQuestion(Session s) throws Exception {
		return json(mockMvc.perform(post("/api/v1/questions")
				.header(HttpHeaders.AUTHORIZATION, bearer(s))
				.contentType(MediaType.APPLICATION_JSON)
				.content(QUESTION_BODY.formatted("신고 대상 " + UUID.randomUUID()))).andExpect(status().isCreated())).get("id").asLong();
	}

	private ResultActions report(Session s, long questionId, String body) throws Exception {
		return mockMvc.perform(post("/api/v1/questions/" + questionId + "/reports")
				.header(HttpHeaders.AUTHORIZATION, bearer(s))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private List<Long> feedIds(Session viewer) throws Exception {
		List<Long> ids = new ArrayList<>();
		String cursor = null;
		do {
			String url = "/api/v1/questions/feed?size=50" + (cursor == null ? "" : "&cursor=" + cursor);
			JsonNode page = json(mockMvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, bearer(viewer))).andExpect(status().isOk()));
			page.get("items").forEach(item -> ids.add(item.get("id").asLong()));
			cursor = page.get("hasNext").asBoolean() ? page.get("nextCursor").asString() : null;
		} while (cursor != null);
		return ids;
	}

	private String questionStatus(long questionId) {
		return jdbc.queryForObject("SELECT status FROM question WHERE id = ?", String.class, questionId);
	}

	private long reportCount(long questionId) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM report WHERE question_id = ? AND status = 'RECEIVED'", Long.class, questionId);
	}

	private static String bearer(Session s) {
		return "Bearer " + s.accessToken;
	}

	private JsonNode json(ResultActions actions) throws Exception {
		return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
	}

}
