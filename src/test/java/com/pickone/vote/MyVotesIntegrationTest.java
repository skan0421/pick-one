package com.pickone.vote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pickone.global.security.jwt.JwtTokenProvider;
import com.pickone.member.domain.SignupStatus;
import com.pickone.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

/** 내가 투표한 고민 목록 통합 테스트 (docs/api.md 5.3) */
@IntegrationTest
class MyVotesIntegrationTest {

	private static final String QUESTION_BODY = """
			{"questionType":"TEXT","content":"%s","options":[{"content":"카페"},{"content":"밥집"}]}""";

	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper objectMapper;
	@Autowired JdbcTemplate jdbc;
	@Autowired JwtTokenProvider jwtTokenProvider;

	@Nested
	class 목록과_커서 {

		@Test
		void 최신_투표순으로_고민_요약_내_선택_현재_결과가_나오고_커서로_이어진다() throws Exception {
			Session me = activeMember();
			Session author = activeMember();
			Created q1 = createQuestion(author);
			Created q2 = createQuestion(author);
			Created q3 = createQuestion(author);
			long v1 = vote(me, q1, 0);
			long v2 = vote(me, q2, 1);
			long v3 = vote(me, q3, 0);
			vote(activeMember(), q3, 1); // 다른 사람의 표: 결과에 반영, 목록에는 무관

			JsonNode page1 = json(myVotes(me, "size=2").andExpect(status().isOk()));
			assertThat(ids(page1, "voteId")).containsExactly(v3, v2);
			assertThat(page1.get("hasNext").asBoolean()).isTrue();
			JsonNode first = page1.get("items").get(0);
			assertThat(first.get("question").get("id").asLong()).isEqualTo(q3.id);
			assertThat(first.get("question").get("content").asString()).isEqualTo(q3.content);
			assertThat(first.get("question").get("author").get("nickname").asString()).isEqualTo(author.nickname);
			assertThat(first.get("question").get("options").size()).isEqualTo(2);
			assertThat(first.get("question").get("boosted").asBoolean()).isFalse();
			assertThat(first.get("myOptionId").asLong()).isEqualTo(q3.option(0));
			assertThat(first.get("result").get("totalVotes").asLong()).isEqualTo(2);
			assertThat(first.get("result").get("options").get(0).get("percent").asDouble()).isEqualTo(50.0);
			assertThat(first.get("votedAt").asString()).isNotBlank();

			JsonNode page2 = json(myVotes(me, "size=2&cursor=" + page1.get("nextCursor").asString()).andExpect(status().isOk()));
			assertThat(ids(page2, "voteId")).containsExactly(v1);
			assertThat(page2.get("hasNext").asBoolean()).isFalse();
			assertThat(page2.get("nextCursor")).isNull();
			assertThat(page2.get("items").get(0).get("myOptionId").asLong()).isEqualTo(q1.option(0));
			assertThat(page2.get("items").get(0).get("result").get("totalVotes").asLong()).isEqualTo(1);
		}

		@Test
		void 투표한_적이_없으면_빈_목록이고_잘못된_커서는_400이다() throws Exception {
			Session me = activeMember();

			myVotes(me, "")
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.items").isEmpty())
					.andExpect(jsonPath("$.hasNext").value(false))
					.andExpect(jsonPath("$.nextCursor").doesNotExist());
			myVotes(me, "cursor=not-a-cursor")
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		}

	}

	@Nested
	class 제외_규칙 {

		@Test
		void 삭제_HIDDEN_차단_지인숨김_고민은_빠지고_CLOSED는_남는다() throws Exception {
			Session me = activeMember();
			Session author = activeMember();
			Created visible = createQuestion(author);
			Created deleted = createQuestion(author);
			Created hidden = createQuestion(author);
			Created closed = createQuestion(author);
			Created blockedByMe = createQuestion(activeMember());
			Created blockedMe = createQuestion(activeMember());
			Created hiddenFromMe = createQuestion(activeMember());
			for (Created q : List.of(visible, deleted, hidden, closed, blockedByMe, blockedMe, hiddenFromMe)) {
				vote(me, q, 0);
			}
			mockMvc.perform(delete("/api/v1/questions/" + deleted.id).header(HttpHeaders.AUTHORIZATION, bearer(author))).andExpect(status().isNoContent());
			jdbc.update("UPDATE question SET status = 'HIDDEN' WHERE id = ?", hidden.id);
			jdbc.update("UPDATE question SET status = 'CLOSED' WHERE id = ?", closed.id);
			jdbc.update("INSERT INTO member_block (blocker_id, blocked_id, created_at) VALUES (?, ?, NOW(6))", me.memberId, authorOf(blockedByMe));
			jdbc.update("INSERT INTO member_block (blocker_id, blocked_id, created_at) VALUES (?, ?, NOW(6))", authorOf(blockedMe), me.memberId);
			jdbc.update("INSERT INTO hide_relation (owner_id, target_member_id, created_at) VALUES (?, ?, NOW(6))", authorOf(hiddenFromMe), me.memberId);
			jdbc.update("UPDATE member SET hide_from_contacts = b'1' WHERE id = ?", authorOf(hiddenFromMe));

			JsonNode page = json(myVotes(me, "size=50").andExpect(status().isOk()));

			List<Long> questionIds = ids(page, "question", "id");
			assertThat(questionIds).containsExactly(closed.id, visible.id);
			assertThat(page.get("items").get(0).get("question").get("status").asString()).isEqualTo("CLOSED");
			// 투표·적립 기록은 그대로다
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM vote WHERE member_id = ?", Long.class, me.memberId)).isEqualTo(7);

			// 지인 숨기기를 끄면 다시 보인다
			jdbc.update("UPDATE member SET hide_from_contacts = b'0' WHERE id = ?", authorOf(hiddenFromMe));
			assertThat(ids(json(myVotes(me, "size=50")), "question", "id")).contains(hiddenFromMe.id);
		}

	}

	// =============================== 헬퍼 ===============================

	private record Session(long memberId, String nickname, String accessToken) {
	}

	private record Created(long id, String content, List<Long> optionIds) {

		long option(int index) {
			return optionIds.get(index);
		}

	}

	private Session activeMember() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String nickname = "내투표" + suffix;
		String body = "{\"email\":\"mv" + suffix + "@test.com\",\"password\":\"pass1234\",\"nickname\":\"" + nickname + "\"}";
		JsonNode json = json(mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated()));
		long memberId = json.get("member").get("id").asLong();
		jdbc.update("UPDATE member SET signup_status = 'ACTIVE' WHERE id = ?", memberId);
		jdbc.update("INSERT INTO point_wallet (member_id, balance, version, updated_at) VALUES (?, 0, 0, NOW(6))", memberId);
		return new Session(memberId, nickname, jwtTokenProvider.createAccessToken(memberId, SignupStatus.ACTIVE));
	}

	private Created createQuestion(Session author) throws Exception {
		String content = "고민 " + UUID.randomUUID().toString().substring(0, 8);
		JsonNode json = json(mockMvc.perform(post("/api/v1/questions")
				.header(HttpHeaders.AUTHORIZATION, bearer(author))
				.contentType(MediaType.APPLICATION_JSON)
				.content(QUESTION_BODY.formatted(content))).andExpect(status().isCreated()));
		List<Long> optionIds = new ArrayList<>();
		json.get("options").forEach(o -> optionIds.add(o.get("id").asLong()));
		return new Created(json.get("id").asLong(), content, optionIds);
	}

	private long vote(Session s, Created q, int optionIndex) throws Exception {
		return json(mockMvc.perform(post("/api/v1/questions/" + q.id + "/votes")
				.header(HttpHeaders.AUTHORIZATION, bearer(s))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"optionId\":" + q.option(optionIndex) + "}")).andExpect(status().isCreated())).get("voteId").asLong();
	}

	private ResultActions myVotes(Session s, String query) throws Exception {
		return mockMvc.perform(get("/api/v1/members/me/votes" + (query.isEmpty() ? "" : "?" + query)).header(HttpHeaders.AUTHORIZATION, bearer(s)));
	}

	private long authorOf(Created q) {
		return jdbc.queryForObject("SELECT member_id FROM question WHERE id = ?", Long.class, q.id);
	}

	private static List<Long> ids(JsonNode page, String... path) {
		List<Long> ids = new ArrayList<>();
		for (JsonNode item : page.get("items")) {
			JsonNode node = item;
			for (String p : path) {
				node = node.get(p);
			}
			ids.add(node.asLong());
		}
		return ids;
	}

	private static String bearer(Session s) {
		return "Bearer " + s.accessToken;
	}

	private JsonNode json(ResultActions actions) throws Exception {
		return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
	}

}
