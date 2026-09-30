package com.pickone.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pickone.global.crypto.PhoneNumber;
import com.pickone.support.ConcurrencyTestSupport;
import com.pickone.support.ImageUploadTestSupport;
import com.pickone.support.IntegrationTest;
import com.pickone.support.RecordingSmsSender;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 고민 등록의 Idempotency-Key 통합 테스트 (docs/api.md 1.6, 4.1).
 * 회원은 가입 → 휴대폰 인증까지 API 로 만들고, 등록된 고민의 개수는 내 고민 목록 API 로 센다.
 */
@IntegrationTest
class QuestionIdempotencyIntegrationTest {

	private static final String TEXT_BODY = """
			{"questionType":"TEXT","content":"%s","options":[{"content":"카페"},{"content":"밥집"}]}""";
	private static final String IMAGE_BODY = """
			{"questionType":"IMAGE","content":"%s","options":[{"imageUrl":"%s"},{"imageUrl":"%s"}]}""";

	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper objectMapper;
	@Autowired JdbcTemplate jdbc;
	@Autowired RecordingSmsSender smsSender;

	// =============================== 키 없음 (하위 호환) ===============================

	@Nested
	class 키_없음 {

		@Test
		void 헤더_없이_등록하면_전과_같이_201이고_같은_내용을_두_번_보내면_고민이_2개다() throws Exception {
			Session me = activeMember();
			String body = TEXT_BODY.formatted("키 없이");

			long first = json(create(me, body, null).andExpect(status().isCreated())).get("id").asLong();
			long second = json(create(me, body, null).andExpect(status().isCreated())).get("id").asLong();

			assertThat(second).isNotEqualTo(first);
			assertThat(myQuestionIds(me)).containsExactlyInAnyOrder(first, second);
			assertThat(storedKey(first)).isNull();
			assertThat(storedKey(second)).isNull();
		}

		@Test
		void 헤더가_공백이면_키가_없는_것으로_보고_등록한다() throws Exception {
			Session me = activeMember();

			long id = json(create(me, TEXT_BODY.formatted("공백 헤더"), "   ").andExpect(status().isCreated())).get("id").asLong();

			assertThat(storedKey(id)).isNull();
		}

	}

	// =============================== 같은 키 재요청 ===============================

	@Nested
	class 같은_키_재요청 {

		@Test
		void 같은_키와_같은_내용을_두_번_보내면_고민은_1개이고_처음_응답을_그대로_받는다() throws Exception {
			Session me = activeMember();
			String key = UUID.randomUUID().toString();
			String body = TEXT_BODY.formatted("같은 키 두 번");

			JsonNode first = json(create(me, body, key).andExpect(status().isCreated()));
			JsonNode second = json(create(me, body, key).andExpect(status().isCreated()));

			assertThat(second).as("재요청의 응답 본문이 처음과 같다").isEqualTo(first);
			assertThat(second.get("id").asLong()).isEqualTo(first.get("id").asLong());
			assertThat(second.get("isMine").asBoolean()).isTrue();
			assertThat(myQuestionIds(me)).containsExactly(first.get("id").asLong());
			assertThat(countByKey(key)).isEqualTo(1);
			assertThat(storedKey(first.get("id").asLong())).isEqualTo(key);
		}

		@Test
		void 여러_번_다시_보내도_고민은_1개다() throws Exception {
			Session me = activeMember();
			String key = UUID.randomUUID().toString();
			String body = TEXT_BODY.formatted("다섯 번");

			List<Long> ids = new ArrayList<>();
			for (int i = 0; i < 5; i++) {
				ids.add(json(create(me, body, key).andExpect(status().isCreated())).get("id").asLong());
			}

			assertThat(ids).containsOnly(ids.get(0));
			assertThat(myQuestionIds(me)).hasSize(1);
		}

		@Test
		void 앞뒤_공백만_다르면_같은_내용으로_본다() throws Exception {
			Session me = activeMember();
			String key = UUID.randomUUID().toString();

			long first = json(create(me, TEXT_BODY.formatted("공백 차이"), key).andExpect(status().isCreated())).get("id").asLong();
			long second = json(create(me, """
					{"questionType":"TEXT","content":"  공백 차이 ","options":[{"content":" 카페"},{"content":"밥집  "}]}""", key)
					.andExpect(status().isCreated())).get("id").asLong();

			assertThat(second).isEqualTo(first);
			assertThat(myQuestionIds(me)).hasSize(1);
		}

		@Test
		void 헤더값의_앞뒤_공백은_다듬은_뒤_비교한다() throws Exception {
			Session me = activeMember();
			String key = UUID.randomUUID().toString();
			String body = TEXT_BODY.formatted("헤더 공백");

			long first = json(create(me, body, key).andExpect(status().isCreated())).get("id").asLong();
			long second = json(create(me, body, "  " + key + " ").andExpect(status().isCreated())).get("id").asLong();

			assertThat(second).isEqualTo(first);
		}

		@Test
		void 사진형도_같은_키로_다시_보내면_고민은_1개다() throws Exception {
			Session me = activeMember();
			String a = ImageUploadTestSupport.uploadImage(mockMvc, objectMapper, me.accessToken, "image/jpeg", ImageUploadTestSupport.fakeImage(300));
			String b = ImageUploadTestSupport.uploadImage(mockMvc, objectMapper, me.accessToken, "image/png", ImageUploadTestSupport.fakeImage(300));
			String key = UUID.randomUUID().toString();
			String body = IMAGE_BODY.formatted("사진형 재요청", a, b);

			JsonNode first = json(create(me, body, key).andExpect(status().isCreated()));
			JsonNode second = json(create(me, body, key).andExpect(status().isCreated()));

			assertThat(second).isEqualTo(first);
			assertThat(second.get("questionType").asString()).isEqualTo("IMAGE");
			assertThat(second.get("options").get(0).get("imageUrl").asString()).isEqualTo(a);
			assertThat(myQuestionIds(me)).containsExactly(first.get("id").asLong());
		}

		@Test
		void 등록한_고민을_삭제한_뒤_같은_키로_다시_보내도_새로_등록되지_않는다() throws Exception {
			Session me = activeMember();
			String key = UUID.randomUUID().toString();
			String body = TEXT_BODY.formatted("삭제 뒤 재요청");
			long id = json(create(me, body, key).andExpect(status().isCreated())).get("id").asLong();
			mockMvc.perform(delete("/api/v1/questions/" + id).header(HttpHeaders.AUTHORIZATION, bearer(me)))
					.andExpect(status().isNoContent());

			create(me, body, key).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(id));

			assertThat(myQuestionIds(me)).isEmpty();
			assertThat(countByKey(key)).isEqualTo(1);
		}

		@Test
		void 성공한_뒤_새_키로_같은_내용을_보내면_새_고민이_등록된다() throws Exception {
			Session me = activeMember();
			String body = TEXT_BODY.formatted("새 키");

			long first = json(create(me, body, UUID.randomUUID().toString()).andExpect(status().isCreated())).get("id").asLong();
			long second = json(create(me, body, UUID.randomUUID().toString()).andExpect(status().isCreated())).get("id").asLong();

			assertThat(second).isNotEqualTo(first);
			assertThat(myQuestionIds(me)).containsExactlyInAnyOrder(first, second);
		}

	}

	// =============================== 키 충돌 ===============================

	@Nested
	class 키_충돌 {

		@Test
		void 같은_키로_본문이_다른_요청을_보내면_409_IDEMPOTENCY_KEY_CONFLICT_이고_등록되지_않는다() throws Exception {
			Session me = activeMember();
			String key = UUID.randomUUID().toString();
			long id = json(create(me, TEXT_BODY.formatted("처음 내용"), key).andExpect(status().isCreated())).get("id").asLong();

			create(me, TEXT_BODY.formatted("다른 내용"), key)
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));

			assertThat(myQuestionIds(me)).containsExactly(id);
			// 처음 등록한 고민은 바뀌지 않는다
			mockMvc.perform(get("/api/v1/questions/" + id).header(HttpHeaders.AUTHORIZATION, bearer(me)))
					.andExpect(jsonPath("$.content").value("처음 내용"));
		}

		@Test
		void 선택지의_내용_개수_순서가_다르면_다른_내용이다() throws Exception {
			Session me = activeMember();
			String key = UUID.randomUUID().toString();
			create(me, TEXT_BODY.formatted("선택지"), key).andExpect(status().isCreated());

			String[] different = {
					// 선택지 내용
					"""
					{"questionType":"TEXT","content":"선택지","options":[{"content":"카페"},{"content":"술집"}]}""",
					// 개수
					"""
					{"questionType":"TEXT","content":"선택지","options":[{"content":"카페"},{"content":"밥집"},{"content":"술집"}]}""",
					// 순서
					"""
					{"questionType":"TEXT","content":"선택지","options":[{"content":"밥집"},{"content":"카페"}]}""",
			};
			for (String body : different) {
				create(me, body, key)
						.andExpect(status().isConflict())
						.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
			}

			assertThat(myQuestionIds(me)).hasSize(1);
		}

		@Test
		void 값을_이어_붙였을_때_같아지는_내용도_구분한다() throws Exception {
			Session me = activeMember();
			String key = UUID.randomUUID().toString();
			create(me, """
					{"questionType":"TEXT","content":"경계","options":[{"content":"ab"},{"content":"c"}]}""", key)
					.andExpect(status().isCreated());

			create(me, """
					{"questionType":"TEXT","content":"경계","options":[{"content":"a"},{"content":"bc"}]}""", key)
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
		}

		@Test
		void 다른_회원이_쓴_키로는_등록할_수_없다() throws Exception {
			Session owner = activeMember();
			Session other = activeMember();
			String key = UUID.randomUUID().toString();
			String body = TEXT_BODY.formatted("남의 키");
			create(owner, body, key).andExpect(status().isCreated());

			create(other, body, key)
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));

			assertThat(myQuestionIds(other)).isEmpty();
			assertThat(myQuestionIds(owner)).hasSize(1);
		}

	}

	// =============================== 키 검증과 실패한 요청 ===============================

	@Nested
	class 키_검증 {

		@Test
		void 키가_UUID_형식이_아니면_400_VALIDATION_ERROR_이고_등록되지_않는다() throws Exception {
			Session me = activeMember();

			for (String invalid : new String[] {"abc", "vote:1", "12345678-1234-1234-1234-1234567890"}) {
				create(me, TEXT_BODY.formatted("형식 오류"), invalid)
						.andExpect(status().isBadRequest())
						.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
			}

			assertThat(myQuestionIds(me)).isEmpty();
		}

		@Test
		void 검증에서_거절된_요청은_키를_쓰지_않는다_고친_내용을_같은_키로_보내면_등록된다() throws Exception {
			Session me = activeMember();
			String key = UUID.randomUUID().toString();

			// 선택지가 1개라 거절된다
			create(me, """
					{"questionType":"TEXT","content":"고쳐서 다시","options":[{"content":"카페"}]}""", key)
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("QUESTION_OPTION_COUNT_INVALID"));
			assertThat(countByKey(key)).isZero();

			long id = json(create(me, TEXT_BODY.formatted("고쳐서 다시"), key).andExpect(status().isCreated())).get("id").asLong();

			assertThat(myQuestionIds(me)).containsExactly(id);
			assertThat(storedKey(id)).isEqualTo(key);
		}

		@Test
		void 휴대폰_인증_전_회원은_키가_있어도_403_SIGNUP_INCOMPLETE() throws Exception {
			Session pending = pendingMember();
			String key = UUID.randomUUID().toString();

			create(pending, TEXT_BODY.formatted("인증 전"), key)
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.code").value("SIGNUP_INCOMPLETE"));

			assertThat(countByKey(key)).isZero();
		}

	}

	// =============================== 동시 요청 ===============================

	@Nested
	class 동시_요청 {

		@Test
		void 같은_키로_동시에_요청하면_고민은_1개이고_모두_같은_응답을_받는다() throws Exception {
			int threads = 4;
			Session me = activeMember();
			String key = UUID.randomUUID().toString();
			String body = TEXT_BODY.formatted("같은 키 동시");

			List<MockHttpServletResponse> responses = ConcurrencyTestSupport.runConcurrently(
					repeat(threads, () -> create(me, body, key).andReturn().getResponse()));

			assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsOnly(201).hasSize(threads);
			List<JsonNode> bodies = new ArrayList<>();
			for (MockHttpServletResponse response : responses) {
				bodies.add(objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8)));
			}
			assertThat(bodies.stream().distinct().toList()).as("모든 응답의 본문이 같다").hasSize(1);
			assertThat(myQuestionIds(me)).containsExactly(bodies.get(0).get("id").asLong());
			assertThat(countByKey(key)).isEqualTo(1);
			assertThat(optionCount(bodies.get(0).get("id").asLong())).as("선택지도 한 벌만").isEqualTo(2);
		}

		@Test
		void 같은_키로_서로_다른_내용을_동시에_보내면_하나만_등록되고_나머지는_409다() throws Exception {
			int threads = 3;
			Session me = activeMember();
			String key = UUID.randomUUID().toString();

			List<Callable<MockHttpServletResponse>> tasks = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				String body = TEXT_BODY.formatted("동시 다른 내용 " + i);
				tasks.add(() -> create(me, body, key).andReturn().getResponse());
			}
			List<MockHttpServletResponse> responses = ConcurrencyTestSupport.runConcurrently(tasks);

			assertThat(responses).extracting(MockHttpServletResponse::getStatus)
					.containsOnly(201, 409).containsOnlyOnce(201);
			for (MockHttpServletResponse response : responses) {
				if (response.getStatus() == 409) {
					assertThat(objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8)).get("code").asString())
							.isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
				}
			}
			assertThat(myQuestionIds(me)).hasSize(1);
			assertThat(countByKey(key)).isEqualTo(1);
		}

		@Test
		void 서로_다른_키로_동시에_요청하면_각각_등록된다() throws Exception {
			int threads = 4;
			Session me = activeMember();
			String body = TEXT_BODY.formatted("다른 키 동시");

			List<MockHttpServletResponse> responses = ConcurrencyTestSupport.runConcurrently(
					repeat(threads, () -> create(me, body, UUID.randomUUID().toString()).andReturn().getResponse()));

			assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsOnly(201).hasSize(threads);
			assertThat(myQuestionIds(me)).hasSize(threads).doesNotHaveDuplicates();
		}

	}

	// =============================== 상단 노출과의 관계 ===============================

	@Test
	void 고민_등록에_쓴_키는_상단_노출의_키와_따로_관리된다() throws Exception {
		Session me = activeMember();
		String key = UUID.randomUUID().toString();
		long id = json(create(me, TEXT_BODY.formatted("키 공간"), key).andExpect(status().isCreated())).get("id").asLong();

		// 같은 값을 상단 노출에 보내면 처음 보는 키로 처리된다. 포인트가 없으므로 잔액 부족으로 거절되고, 키 충돌이 아니다
		mockMvc.perform(post("/api/v1/questions/" + id + "/boosts")
						.header(HttpHeaders.AUTHORIZATION, bearer(me))
						.header("Idempotency-Key", key))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("POINT_INSUFFICIENT"));
	}

	// =============================== 헬퍼 ===============================

	private record Session(long memberId, String accessToken) {
	}

	/** 가입만 한 회원 (PENDING_PHONE) */
	private Session pendingMember() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		JsonNode signup = json(mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"qi" + suffix + "@test.com\",\"password\":\"pass1234\",\"nickname\":\"멱등" + suffix + "\"}"))
				.andExpect(status().isCreated()));
		return new Session(signup.get("member").get("id").asLong(), signup.get("accessToken").asString());
	}

	/** 가입 → 인증번호 발송 → 확인까지 API 로 마친 ACTIVE 회원 */
	private Session activeMember() throws Exception {
		Session pending = pendingMember();
		String phone = uniquePhone();
		mockMvc.perform(post("/api/v1/phone-verifications")
						.header(HttpHeaders.AUTHORIZATION, bearer(pending))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"phone\":\"" + phone + "\"}"))
				.andExpect(status().isAccepted());
		String code = smsSender.lastCodeFor(PhoneNumber.toE164(phone));
		JsonNode tokens = json(mockMvc.perform(post("/api/v1/phone-verifications/confirm")
						.header(HttpHeaders.AUTHORIZATION, bearer(pending))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\"}"))
				.andExpect(status().isOk()));
		return new Session(pending.memberId, tokens.get("accessToken").asString());
	}

	/** idempotencyKey 가 null 이면 헤더를 보내지 않는다 */
	private ResultActions create(Session s, String body, String idempotencyKey) throws Exception {
		MockHttpServletRequestBuilder request = post("/api/v1/questions")
				.header(HttpHeaders.AUTHORIZATION, bearer(s))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body);
		if (idempotencyKey != null) {
			request.header("Idempotency-Key", idempotencyKey);
		}
		return mockMvc.perform(request);
	}

	/** 내 고민 목록 API 로 본 고민 id (삭제한 고민은 나오지 않는다) */
	private List<Long> myQuestionIds(Session s) throws Exception {
		JsonNode page = json(mockMvc.perform(get("/api/v1/members/me/questions?size=50").header(HttpHeaders.AUTHORIZATION, bearer(s)))
				.andExpect(status().isOk()));
		List<Long> ids = new ArrayList<>();
		page.get("items").forEach(item -> ids.add(item.get("id").asLong()));
		return ids;
	}

	/** 삭제한 고민까지 포함해 이 키로 저장된 행의 수 */
	private long countByKey(String key) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM question WHERE idempotency_key = ?", Long.class, key);
	}

	private String storedKey(long questionId) {
		return jdbc.queryForObject("SELECT idempotency_key FROM question WHERE id = ?", String.class, questionId);
	}

	private long optionCount(long questionId) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM question_option WHERE question_id = ?", Long.class, questionId);
	}

	private JsonNode json(ResultActions actions) throws Exception {
		return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
	}

	private static String bearer(Session s) {
		return "Bearer " + s.accessToken;
	}

	private static <T> List<Callable<T>> repeat(int times, Callable<T> task) {
		List<Callable<T>> tasks = new ArrayList<>();
		for (int i = 0; i < times; i++) {
			tasks.add(task);
		}
		return tasks;
	}

	/** 테스트마다 다른 번호. 010-XXXX-XXXX 형태 */
	private static String uniquePhone() {
		int a = ThreadLocalRandom.current().nextInt(1000, 10000);
		int b = ThreadLocalRandom.current().nextInt(1000, 10000);
		return "010-" + a + "-" + b;
	}

}
