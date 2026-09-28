package com.pickone.block;

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
import java.util.UUID;
import java.util.concurrent.Callable;
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

/** 차단·해제·목록 통합 테스트 (docs/api.md 8.1~8.3) */
@IntegrationTest
class MemberBlockIntegrationTest {

	private static final String QUESTION_BODY = """
			{"questionType":"TEXT","content":"%s","options":[{"content":"카페"},{"content":"밥집"}]}""";

	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper objectMapper;
	@Autowired JdbcTemplate jdbc;
	@Autowired JwtTokenProvider jwtTokenProvider;

	@Test
	void 차단하면_201이고_목록에_닉네임과_함께_나온다() throws Exception {
		Session me = activeMember();
		Session target = activeMember();

		block(me, target.memberId)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.blockedMemberId").value(target.memberId))
				.andExpect(jsonPath("$.createdAt").isString());

		mockMvc.perform(get("/api/v1/members/me/blocks").header(HttpHeaders.AUTHORIZATION, bearer(me)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].memberId").value(target.memberId))
				.andExpect(jsonPath("$.items[0].nickname").value(target.nickname))
				.andExpect(jsonPath("$.items[0].createdAt").isString());
	}

	@Test
	void 자기_자신을_차단하면_400_BLOCK_SELF_이고_없는_회원은_404() throws Exception {
		Session me = activeMember();

		block(me, me.memberId)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("BLOCK_SELF"));
		block(me, 999_999_999L)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"));
	}

	@Test
	void 이미_차단한_상대를_다시_차단해도_201이고_최초_차단_시각이_유지된다() throws Exception {
		Session me = activeMember();
		Session target = activeMember();
		String first = json(block(me, target.memberId).andExpect(status().isCreated())).get("createdAt").asString();

		String again = json(block(me, target.memberId).andExpect(status().isCreated())).get("createdAt").asString();

		assertThat(again).isEqualTo(first);
		assertThat(blockCount(me.memberId)).isEqualTo(1);
	}

	@Test
	void 같은_상대를_동시에_차단해도_모두_201이고_행은_하나다() throws Exception {
		Session me = activeMember();
		Session target = activeMember();
		List<Callable<MockHttpServletResponse>> tasks = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			tasks.add(() -> block(me, target.memberId).andReturn().getResponse());
		}

		List<MockHttpServletResponse> responses = ConcurrencyTestSupport.runConcurrently(tasks);

		assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsOnly(201);
		assertThat(blockCount(me.memberId)).isEqualTo(1);
	}

	@Test
	void 차단하면_양쪽_피드에서_서로_안_보이고_해제하면_다시_보인다() throws Exception {
		Session a = activeMember();
		Session b = activeMember();
		long byA = createQuestion(a);
		long byB = createQuestion(b);
		assertThat(feedIds(a)).contains(byB);
		assertThat(feedIds(b)).contains(byA);

		block(a, b.memberId).andExpect(status().isCreated());
		assertThat(feedIds(a)).doesNotContain(byB);
		assertThat(feedIds(b)).doesNotContain(byA);
		mockMvc.perform(get("/api/v1/questions/" + byA).header(HttpHeaders.AUTHORIZATION, bearer(b))).andExpect(status().isNotFound());

		mockMvc.perform(delete("/api/v1/members/" + b.memberId + "/blocks").header(HttpHeaders.AUTHORIZATION, bearer(a)))
				.andExpect(status().isNoContent());
		assertThat(feedIds(a)).contains(byB);
		assertThat(feedIds(b)).contains(byA);
		assertThat(blockCount(a.memberId)).isZero();
	}

	@Test
	void 차단하지_않은_상대를_해제해도_204다() throws Exception {
		Session me = activeMember();
		Session other = activeMember();

		mockMvc.perform(delete("/api/v1/members/" + other.memberId + "/blocks").header(HttpHeaders.AUTHORIZATION, bearer(me)))
				.andExpect(status().isNoContent());
	}

	// =============================== 헬퍼 ===============================

	private record Session(long memberId, String nickname, String accessToken) {
	}

	private Session activeMember() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String nickname = "차단" + suffix;
		String body = "{\"email\":\"bk" + suffix + "@test.com\",\"password\":\"pass1234\",\"nickname\":\"" + nickname + "\"}";
		JsonNode json = json(mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated()));
		long memberId = json.get("member").get("id").asLong();
		jdbc.update("UPDATE member SET signup_status = 'ACTIVE' WHERE id = ?", memberId);
		return new Session(memberId, nickname, jwtTokenProvider.createAccessToken(memberId, SignupStatus.ACTIVE));
	}

	private ResultActions block(Session s, long targetId) throws Exception {
		return mockMvc.perform(post("/api/v1/members/" + targetId + "/blocks").header(HttpHeaders.AUTHORIZATION, bearer(s)));
	}

	private long createQuestion(Session s) throws Exception {
		return json(mockMvc.perform(post("/api/v1/questions")
				.header(HttpHeaders.AUTHORIZATION, bearer(s))
				.contentType(MediaType.APPLICATION_JSON)
				.content(QUESTION_BODY.formatted("차단 " + UUID.randomUUID()))).andExpect(status().isCreated())).get("id").asLong();
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

	private long blockCount(long blockerId) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM member_block WHERE blocker_id = ?", Long.class, blockerId);
	}

	private static String bearer(Session s) {
		return "Bearer " + s.accessToken;
	}

	private JsonNode json(ResultActions actions) throws Exception {
		return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
	}

}
