package com.pickone.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pickone.member.repository.MemberRepository;
import com.pickone.support.IntegrationTest;
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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 가입·로그인 통합 테스트. 실제 커밋 경합을 보기 위해 @Transactional 을 붙이지 않고,
 * 테스트마다 고유한 이메일/닉네임을 써서 서로 간섭하지 않게 한다.
 */
@IntegrationTest
class AuthIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ObjectMapper objectMapper;

	@Autowired
	MemberRepository memberRepository;

	// ---------- 가입 ----------

	@Test
	void 가입하면_201과_PENDING_PHONE_상태의_토큰을_받는다() throws Exception {
		String email = uniqueEmail();

		signup(email, "pass1234", uniqueNickname())
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.member.id").isNumber())
				.andExpect(jsonPath("$.member.signupStatus").value("PENDING_PHONE"))
				.andExpect(jsonPath("$.accessToken").isString());

		assertThat(memberRepository.existsByEmail(email)).isTrue();
	}

	@Test
	void 이미_가입된_이메일이면_409_MEMBER_EMAIL_DUPLICATE() throws Exception {
		String email = uniqueEmail();
		signup(email, "pass1234", uniqueNickname()).andExpect(status().isCreated());

		signup(email, "pass1234", uniqueNickname())
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("MEMBER_EMAIL_DUPLICATE"));
	}

	@Test
	void 이미_사용_중인_닉네임이면_409_MEMBER_NICKNAME_DUPLICATE() throws Exception {
		String nickname = uniqueNickname();
		signup(uniqueEmail(), "pass1234", nickname).andExpect(status().isCreated());

		signup(uniqueEmail(), "pass1234", nickname)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("MEMBER_NICKNAME_DUPLICATE"));
	}

	@Test
	void 입력값이_잘못되면_400_VALIDATION_ERROR와_필드_목록을_받는다() throws Exception {
		signup("not-an-email", "short", "!")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors").isArray())
				.andExpect(jsonPath("$.errors[*].field").isNotEmpty());
	}

	// ---------- 동시 가입 경합 ----------

	@Test
	void 두_스레드가_같은_이메일로_동시에_가입하면_하나만_201이고_나머지는_409다() throws Exception {
		String email = uniqueEmail();

		List<MockHttpServletResponse> responses = runConcurrently(
				() -> signup(email, "pass1234", uniqueNickname()).andReturn().getResponse(),
				() -> signup(email, "pass1234", uniqueNickname()).andReturn().getResponse());

		assertStatuses(responses, "MEMBER_EMAIL_DUPLICATE");
		assertThat(memberRepository.findByEmailAndDeletedAtIsNull(email)).isPresent();
	}

	@Test
	void 두_스레드가_같은_닉네임으로_동시에_가입하면_하나만_201이고_나머지는_409다() throws Exception {
		String nickname = uniqueNickname();

		List<MockHttpServletResponse> responses = runConcurrently(
				() -> signup(uniqueEmail(), "pass1234", nickname).andReturn().getResponse(),
				() -> signup(uniqueEmail(), "pass1234", nickname).andReturn().getResponse());

		assertStatuses(responses, "MEMBER_NICKNAME_DUPLICATE");
	}

	// ---------- 로그인 ----------

	@Test
	void 가입한_이메일과_비밀번호로_로그인하면_200과_토큰을_받는다() throws Exception {
		String email = uniqueEmail();
		signup(email, "pass1234", uniqueNickname()).andExpect(status().isCreated());

		login(email, "pass1234")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.member.signupStatus").value("PENDING_PHONE"))
				.andExpect(jsonPath("$.accessToken").isString());
	}

	@Test
	void 비밀번호가_틀리면_401_AUTH_INVALID_CREDENTIALS() throws Exception {
		String email = uniqueEmail();
		signup(email, "pass1234", uniqueNickname()).andExpect(status().isCreated());

		login(email, "wrong9999")
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
	}

	@Test
	void 없는_이메일도_같은_401_AUTH_INVALID_CREDENTIALS() throws Exception {
		login(uniqueEmail(), "pass1234")
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
	}

	// ---------- 헬퍼 ----------

	private ResultActions signup(String email, String password, String nickname) throws Exception {
		String body = objectMapper.writeValueAsString(new SignupBody(email, password, nickname));
		return mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions login(String email, String password) throws Exception {
		String body = objectMapper.writeValueAsString(new LoginBody(email, password));
		return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	/** 두 작업을 래치로 동시에 출발시켜 결과를 모두 모은다 */
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

	private void assertStatuses(List<MockHttpServletResponse> responses, String expectedConflictCode) throws Exception {
		List<Integer> statuses = responses.stream().map(MockHttpServletResponse::getStatus).sorted().toList();
		assertThat(statuses).containsExactly(201, 409);

		MockHttpServletResponse conflict = responses.stream().filter(r -> r.getStatus() == 409).findFirst().orElseThrow();
		JsonNode json = objectMapper.readTree(conflict.getContentAsString());
		assertThat(json.get("code").asString()).isEqualTo(expectedConflictCode);
	}

	private static String uniqueEmail() {
		return "u" + UUID.randomUUID().toString().substring(0, 8) + "@test.com";
	}

	private static String uniqueNickname() {
		return "닉" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");
	}

	private record SignupBody(String email, String password, String nickname) {
	}

	private record LoginBody(String email, String password) {
	}

}
