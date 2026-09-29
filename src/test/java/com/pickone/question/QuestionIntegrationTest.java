package com.pickone.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pickone.global.security.jwt.JwtTokenProvider;
import com.pickone.hide.domain.HideRelation;
import com.pickone.hide.repository.HideRelationRepository;
import com.pickone.member.domain.SignupStatus;
import com.pickone.support.ImageUploadTestSupport;
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

/** 고민 등록·상세·내 목록·삭제·피드 통합 테스트 (docs/api.md 4장) */
@IntegrationTest
class QuestionIntegrationTest {

	private static final String TEXT_BODY = """
			{"questionType":"TEXT","content":"%s","options":[{"content":"카페"},{"content":"밥집"}]}""";
	private static final String IMAGE_BODY = """
			{"questionType":"IMAGE","content":"%s","options":[{"imageUrl":"%s"},{"imageUrl":"%s"}]}""";

	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper objectMapper;
	@Autowired JdbcTemplate jdbc;
	@Autowired JwtTokenProvider jwtTokenProvider;
	@Autowired HideRelationRepository hideRelationRepository;

	// =============================== 등록 ===============================

	@Nested
	class 등록 {

		@Test
		void 텍스트형은_선택지_2개로_등록되고_sort_order가_순서대로_붙는다() throws Exception {
			Session me = activeMember();

			create(me, TEXT_BODY.formatted("소개팅 첫 만남"))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.id").isNumber())
					.andExpect(jsonPath("$.questionType").value("TEXT"))
					.andExpect(jsonPath("$.status").value("ACTIVE"))
					.andExpect(jsonPath("$.boosted").value(false))
					.andExpect(jsonPath("$.isMine").value(true))
					.andExpect(jsonPath("$.options[0].sortOrder").value(1))
					.andExpect(jsonPath("$.options[0].content").value("카페"))
					.andExpect(jsonPath("$.options[1].sortOrder").value(2))
					.andExpect(jsonPath("$.author.nickname").value(me.nickname));
		}

		@Test
		void 사진형은_본인이_업로드한_이미지_2장으로_등록된다() throws Exception {
			Session me = activeMember();
			String a = ImageUploadTestSupport.uploadImage(mockMvc, objectMapper, me.accessToken, "image/jpeg", ImageUploadTestSupport.fakeImage(300));
			String b = ImageUploadTestSupport.uploadImage(mockMvc, objectMapper, me.accessToken, "image/png", ImageUploadTestSupport.fakeImage(300));

			create(me, IMAGE_BODY.formatted("면접 뭐 입지", a, b))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.options[0].imageUrl").value(a))
					.andExpect(jsonPath("$.options[0].content").doesNotExist());
		}

		@Test
		void 텍스트형_선택지가_1개면_QUESTION_OPTION_COUNT_INVALID() throws Exception {
			create(activeMember(), """
					{"questionType":"TEXT","content":"x","options":[{"content":"하나"}]}""")
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("QUESTION_OPTION_COUNT_INVALID"));
		}

		@Test
		void 텍스트형_선택지가_5개면_QUESTION_OPTION_COUNT_INVALID() throws Exception {
			create(activeMember(), """
					{"questionType":"TEXT","content":"x","options":[{"content":"1"},{"content":"2"},{"content":"3"},{"content":"4"},{"content":"5"}]}""")
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("QUESTION_OPTION_COUNT_INVALID"));
		}

		@Test
		void 사진형_선택지가_3개면_QUESTION_OPTION_COUNT_INVALID() throws Exception {
			create(activeMember(), """
					{"questionType":"IMAGE","content":"x","options":[{"imageUrl":"https://a"},{"imageUrl":"https://b"},{"imageUrl":"https://c"}]}""")
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("QUESTION_OPTION_COUNT_INVALID"));
		}

		@Test
		void 텍스트형에_imageUrl을_넣으면_QUESTION_OPTION_TYPE_MISMATCH() throws Exception {
			create(activeMember(), """
					{"questionType":"TEXT","content":"x","options":[{"content":"a"},{"content":"b","imageUrl":"https://b"}]}""")
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("QUESTION_OPTION_TYPE_MISMATCH"));
		}

		@Test
		void 사진형에_imageUrl이_없으면_QUESTION_OPTION_TYPE_MISMATCH() throws Exception {
			create(activeMember(), """
					{"questionType":"IMAGE","content":"x","options":[{"imageUrl":"https://a"},{"content":"글자만"}]}""")
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("QUESTION_OPTION_TYPE_MISMATCH"));
		}

		@Test
		void 선택지가_20자를_넘으면_VALIDATION_ERROR() throws Exception {
			create(activeMember(), """
					{"questionType":"TEXT","content":"x","options":[{"content":"%s"},{"content":"b"}]}""".formatted("가".repeat(21)))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
					.andExpect(jsonPath("$.errors[0].field").value("options[0].content"));
		}

		@Test
		void 본문이_300자를_넘으면_VALIDATION_ERROR() throws Exception {
			create(activeMember(), TEXT_BODY.formatted("가".repeat(301)))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		}

		@Test
		void 휴대폰_인증_전_회원은_SIGNUP_INCOMPLETE() throws Exception {
			Session pending = pendingMember();

			create(pending, TEXT_BODY.formatted("x"))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.code").value("SIGNUP_INCOMPLETE"));
		}

	}

	// =============================== 상세·삭제 ===============================

	@Nested
	class 상세와_삭제 {

		@Test
		void 다른_회원의_고민_상세를_조회하면_isMine이_false다() throws Exception {
			Session author = activeMember();
			Session viewer = activeMember();
			long id = createId(author, TEXT_BODY.formatted("상세"));

			mockMvc.perform(get("/api/v1/questions/" + id).header(HttpHeaders.AUTHORIZATION, bearer(viewer)))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.id").value(id))
					.andExpect(jsonPath("$.isMine").value(false))
					.andExpect(jsonPath("$.author.nickname").value(author.nickname))
					.andExpect(jsonPath("$.options.length()").value(2))
					.andExpect(jsonPath("$.myVote").doesNotExist());
		}

		@Test
		void 작성자가_삭제하면_204이고_이후_상세는_404다() throws Exception {
			Session author = activeMember();
			long id = createId(author, TEXT_BODY.formatted("삭제"));

			mockMvc.perform(delete("/api/v1/questions/" + id).header(HttpHeaders.AUTHORIZATION, bearer(author)))
					.andExpect(status().isNoContent());

			mockMvc.perform(get("/api/v1/questions/" + id).header(HttpHeaders.AUTHORIZATION, bearer(activeMember())))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.code").value("QUESTION_NOT_FOUND"));
			mockMvc.perform(delete("/api/v1/questions/" + id).header(HttpHeaders.AUTHORIZATION, bearer(author)))
					.andExpect(status().isNotFound());
		}

		@Test
		void 작성자가_아니면_삭제할_수_없다_FORBIDDEN() throws Exception {
			long id = createId(activeMember(), TEXT_BODY.formatted("남의 글"));

			mockMvc.perform(delete("/api/v1/questions/" + id).header(HttpHeaders.AUTHORIZATION, bearer(activeMember())))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.code").value("FORBIDDEN"));
		}

		@Test
		void 차단_관계면_상세도_404로_숨긴다() throws Exception {
			Session author = activeMember();
			Session viewer = activeMember();
			long id = createId(author, TEXT_BODY.formatted("차단"));
			block(viewer.memberId, author.memberId);

			mockMvc.perform(get("/api/v1/questions/" + id).header(HttpHeaders.AUTHORIZATION, bearer(viewer)))
					.andExpect(status().isNotFound());
		}

	}

	// =============================== 내 고민 목록 ===============================

	@Test
	void 내_고민_목록은_최신순이고_커서로_이어지며_삭제한_글은_빠진다() throws Exception {
		Session me = activeMember();
		long q1 = createId(me, TEXT_BODY.formatted("첫째"));
		long q2 = createId(me, TEXT_BODY.formatted("둘째"));
		long q3 = createId(me, TEXT_BODY.formatted("셋째"));
		long deleted = createId(me, TEXT_BODY.formatted("지울 것"));
		mockMvc.perform(delete("/api/v1/questions/" + deleted).header(HttpHeaders.AUTHORIZATION, bearer(me))).andExpect(status().isNoContent());

		JsonNode page1 = json(mockMvc.perform(get("/api/v1/members/me/questions?size=2").header(HttpHeaders.AUTHORIZATION, bearer(me)))
				.andExpect(status().isOk()));
		assertThat(ids(page1)).containsExactly(q3, q2);
		assertThat(page1.get("hasNext").asBoolean()).isTrue();
		assertThat(page1.get("items").get(0).get("options").get(0).get("count").asLong()).isZero();

		JsonNode page2 = json(mockMvc.perform(get("/api/v1/members/me/questions?size=2&cursor=" + page1.get("nextCursor").asString())
				.header(HttpHeaders.AUTHORIZATION, bearer(me))).andExpect(status().isOk()));
		assertThat(ids(page2)).containsExactly(q1);
		assertThat(page2.get("hasNext").asBoolean()).isFalse();
		assertThat(page2.get("nextCursor")).isNull();
	}

	// =============================== 피드 ===============================

	@Nested
	class 피드_필터링 {

		@Test
		void 내가_쓴_고민은_피드에_나오지_않는다() throws Exception {
			Session me = activeMember();
			long mine = createId(me, TEXT_BODY.formatted("내 글"));
			long others = createId(activeMember(), TEXT_BODY.formatted("남의 글"));

			List<Long> feed = allFeedIds(me);
			assertThat(feed).contains(others).doesNotContain(mine);
		}

		@Test
		void 이미_투표한_고민은_피드에_나오지_않는다() throws Exception {
			Session me = activeMember();
			long voted = createId(activeMember(), TEXT_BODY.formatted("투표함"));
			long notVoted = createId(activeMember(), TEXT_BODY.formatted("투표 안 함"));
			insertVote(me.memberId, voted);

			List<Long> feed = allFeedIds(me);
			assertThat(feed).contains(notVoted).doesNotContain(voted);
		}

		@Test
		void 내가_차단한_사람의_고민은_피드에_나오지_않는다() throws Exception {
			Session me = activeMember();
			Session blocked = activeMember();
			long hidden = createId(blocked, TEXT_BODY.formatted("차단한 사람 글"));
			block(me.memberId, blocked.memberId);

			assertThat(allFeedIds(me)).doesNotContain(hidden);
		}

		@Test
		void 나를_차단한_사람의_고민도_피드에_나오지_않는다() throws Exception {
			Session me = activeMember();
			Session blocker = activeMember();
			long hidden = createId(blocker, TEXT_BODY.formatted("나를 차단한 사람 글"));
			block(blocker.memberId, me.memberId);

			assertThat(allFeedIds(me)).doesNotContain(hidden);
		}

		@Test
		void 작성자가_지인_숨기기를_켜고_나를_등록했으면_피드에_나오지_않는다() throws Exception {
			Session me = activeMember();
			Session author = activeMember();
			long hidden = createId(author, TEXT_BODY.formatted("지인에게 숨김"));
			hideRelationRepository.save(new HideRelation(author.memberId, me.memberId));
			jdbc.update("UPDATE member SET hide_from_contacts = b'1' WHERE id = ?", author.memberId);

			assertThat(allFeedIds(me)).doesNotContain(hidden);

			// 스위치를 끄면 관계가 남아 있어도 다시 보인다
			jdbc.update("UPDATE member SET hide_from_contacts = b'0' WHERE id = ?", author.memberId);
			assertThat(allFeedIds(me)).contains(hidden);
		}

		@Test
		void 삭제됐거나_HIDDEN인_고민은_피드에_나오지_않는다() throws Exception {
			Session me = activeMember();
			Session author = activeMember();
			long deleted = createId(author, TEXT_BODY.formatted("삭제됨"));
			long hidden = createId(author, TEXT_BODY.formatted("신고 누적"));
			long visible = createId(author, TEXT_BODY.formatted("정상"));
			mockMvc.perform(delete("/api/v1/questions/" + deleted).header(HttpHeaders.AUTHORIZATION, bearer(author))).andExpect(status().isNoContent());
			jdbc.update("UPDATE question SET status = 'HIDDEN' WHERE id = ?", hidden);

			List<Long> feed = allFeedIds(me);
			assertThat(feed).contains(visible).doesNotContain(deleted, hidden);
		}

		@Test
		void 피드_항목에는_결과가_없고_작성자_닉네임과_선택지가_있다() throws Exception {
			Session me = activeMember();
			Session author = activeMember();
			long id = createId(author, TEXT_BODY.formatted("항목 형식"));

			JsonNode page = json(mockMvc.perform(get("/api/v1/questions/feed?size=50").header(HttpHeaders.AUTHORIZATION, bearer(me)))
					.andExpect(status().isOk()));
			JsonNode item = findItem(page, id);
			assertThat(item.get("author").get("nickname").asString()).isEqualTo(author.nickname);
			assertThat(item.get("options").size()).isEqualTo(2);
			assertThat(item.get("boosted").asBoolean()).isFalse();
			assertThat(item.has("result")).isFalse();
		}

		@Test
		void 잘못된_커서는_VALIDATION_ERROR() throws Exception {
			mockMvc.perform(get("/api/v1/questions/feed?cursor=not-a-cursor").header(HttpHeaders.AUTHORIZATION, bearer(activeMember())))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		}

	}

	@Test
	void 피드는_상단_노출을_먼저_그_다음_최신순으로_커서를_따라_중복_누락_없이_끝까지_넘어간다() throws Exception {
		Session me = activeMember();
		List<Long> created = new ArrayList<>();
		for (int i = 0; i < 7; i++) {
			created.add(createId(activeMember(), TEXT_BODY.formatted("커서 " + i)));
		}
		long boostedOld = created.get(1);
		long boostedNew = created.get(4);
		jdbc.update("UPDATE question SET boosted_until = DATE_ADD(NOW(6), INTERVAL 1 DAY) WHERE id IN (?, ?)", boostedOld, boostedNew);

		List<Long> feed = new ArrayList<>();
		String cursor = null;
		int pages = 0;
		do {
			String url = "/api/v1/questions/feed?size=3" + (cursor == null ? "" : "&cursor=" + cursor);
			JsonNode page = json(mockMvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, bearer(me))).andExpect(status().isOk()));
			assertThat(ids(page).size()).isLessThanOrEqualTo(3);
			feed.addAll(ids(page));
			cursor = page.get("hasNext").asBoolean() ? page.get("nextCursor").asString() : null;
			pages++;
		} while (cursor != null && pages < 200);

		// 중복 없음, 내가 만든 7개 모두 포함
		assertThat(feed).doesNotHaveDuplicates();
		assertThat(feed).containsAll(created);
		// 상단 노출 2개가 맨 앞 (그 안에서는 최신순), 나머지는 최신순
		assertThat(feed.subList(0, 2)).containsExactly(boostedNew, boostedOld);
		List<Long> normalExpected = new ArrayList<>(created);
		normalExpected.removeAll(List.of(boostedOld, boostedNew));
		normalExpected.sort((a, b) -> Long.compare(b, a));
		List<Long> normalActual = feed.stream().filter(created::contains).filter(id -> id != boostedOld && id != boostedNew).toList();
		assertThat(normalActual).containsExactlyElementsOf(normalExpected);
	}

	// =============================== 헬퍼 ===============================

	private record Session(long memberId, String nickname, String accessToken) {
	}

	/** 가입 후 DB 에서 바로 ACTIVE 로 올리고 ACTIVE 토큰을 만든다 (휴대폰 인증 흐름은 PhoneVerificationIntegrationTest 가 검증) */
	private Session activeMember() throws Exception {
		Session pending = pendingMember();
		jdbc.update("UPDATE member SET signup_status = 'ACTIVE' WHERE id = ?", pending.memberId);
		return new Session(pending.memberId, pending.nickname, jwtTokenProvider.createAccessToken(pending.memberId, SignupStatus.ACTIVE));
	}

	private Session pendingMember() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String nickname = "고민" + suffix;
		String body = "{\"email\":\"qi" + suffix + "@test.com\",\"password\":\"pass1234\",\"nickname\":\"" + nickname + "\"}";
		JsonNode json = json(mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated()));
		return new Session(json.get("member").get("id").asLong(), nickname, json.get("accessToken").asString());
	}

	private ResultActions create(Session s, String body) throws Exception {
		return mockMvc.perform(post("/api/v1/questions")
				.header(HttpHeaders.AUTHORIZATION, bearer(s))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private long createId(Session s, String body) throws Exception {
		return json(create(s, body).andExpect(status().isCreated())).get("id").asLong();
	}

	/** 피드를 끝까지 넘겨 ID 를 모두 모은다 */
	private List<Long> allFeedIds(Session viewer) throws Exception {
		List<Long> all = new ArrayList<>();
		String cursor = null;
		do {
			String url = "/api/v1/questions/feed?size=50" + (cursor == null ? "" : "&cursor=" + cursor);
			JsonNode page = json(mockMvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, bearer(viewer))).andExpect(status().isOk()));
			all.addAll(ids(page));
			cursor = page.get("hasNext").asBoolean() ? page.get("nextCursor").asString() : null;
		} while (cursor != null);
		return all;
	}

	private void block(long blockerId, long blockedId) {
		jdbc.update("INSERT INTO member_block (blocker_id, blocked_id, created_at) VALUES (?, ?, NOW(6))", blockerId, blockedId);
	}

	private void insertVote(long memberId, long questionId) {
		Long optionId = jdbc.queryForObject("SELECT id FROM question_option WHERE question_id = ? ORDER BY sort_order LIMIT 1", Long.class, questionId);
		jdbc.update("INSERT INTO vote (question_id, option_id, member_id, created_at) VALUES (?, ?, ?, NOW(6))", questionId, optionId, memberId);
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

	private static JsonNode findItem(JsonNode page, long id) {
		for (JsonNode item : page.get("items")) {
			if (item.get("id").asLong() == id) {
				return item;
			}
		}
		throw new AssertionError("피드에 없음: " + id);
	}

}
