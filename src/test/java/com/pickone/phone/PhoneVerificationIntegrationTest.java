package com.pickone.phone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pickone.global.crypto.PhoneCipher;
import com.pickone.global.security.jwt.JwtConfig;
import com.pickone.hide.domain.HidePending;
import com.pickone.hide.domain.HideRelation;
import com.pickone.hide.repository.HidePendingRepository;
import com.pickone.hide.repository.HideRelationRepository;
import com.pickone.member.domain.Member;
import com.pickone.member.repository.MemberRepository;
import com.pickone.phone.repository.OtpStore;
import com.pickone.point.repository.PointWalletRepository;
import com.pickone.support.IntegrationTest;
import com.pickone.support.RecordingSmsSender;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** SMS OTP 휴대폰 인증 통합 테스트 (docs/api.md 3장) */
@IntegrationTest
class PhoneVerificationIntegrationTest {

	private static final String SEND_URL = "/api/v1/phone-verifications";
	private static final String CONFIRM_URL = "/api/v1/phone-verifications/confirm";
	private static final String ACTIVE_ONLY_PATH = "/api/v1/questions/feed";

	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper objectMapper;
	@Autowired RecordingSmsSender smsSender;
	@Autowired PhoneCipher phoneCipher;
	@Autowired StringRedisTemplate redisTemplate;
	@Autowired JwtDecoder jwtDecoder;
	@Autowired MemberRepository memberRepository;
	@Autowired PointWalletRepository pointWalletRepository;
	@Autowired HidePendingRepository hidePendingRepository;
	@Autowired HideRelationRepository hideRelationRepository;

	// ---------- 발송 ----------

	@Test
	void 발송하면_202이고_응답과_Redis에_코드_원문이_없다() throws Exception {
		Session s = signup();
		String phone = uniquePhone();

		String body = send(s, phone).andExpect(status().isAccepted())
				.andExpect(jsonPath("$.expiresInSeconds").value(180))
				.andExpect(jsonPath("$.cooldownSeconds").value(60))
				.andReturn().getResponse().getContentAsString();

		String code = smsSender.lastCodeFor(e164(phone));
		assertThat(code).hasSize(6);
		assertThat(body).doesNotContain(code);

		Map<Object, Object> stored = redisTemplate.opsForHash().entries(OtpStore.codeKey(s.memberId, hmac(phone)));
		assertThat(stored).containsKeys("hash", "salt", "attempts");
		assertThat(stored.values()).noneMatch(v -> String.valueOf(v).contains(code));
		assertThat(redisTemplate.getExpire(OtpStore.codeKey(s.memberId, hmac(phone)), TimeUnit.SECONDS)).isBetween(1L, 180L);
	}

	@Test
	void 잘못된_번호_형식은_400_PHONE_INVALID_FORMAT() throws Exception {
		send(signup(), "02-1234-5678")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("PHONE_INVALID_FORMAT"));
	}

	@Test
	void 같은_번호_1분_안_재발송은_429_OTP_COOLDOWN() throws Exception {
		Session s = signup();
		String phone = uniquePhone();
		send(s, phone).andExpect(status().isAccepted());

		send(s, phone)
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("OTP_COOLDOWN"));
	}

	@Test
	void 일일_발송_한도를_넘기면_429_OTP_DAILY_LIMIT() throws Exception {
		Session s = signup();
		String phone = uniquePhone();
		String today = LocalDate.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
		redisTemplate.opsForValue().set(OtpStore.dailyPhoneKey(hmac(phone), today), "5"); // 이미 5회 보낸 상태

		send(s, phone)
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("OTP_DAILY_LIMIT"));
	}

	@Test
	void 이미_다른_회원이_인증한_번호는_409_PHONE_ALREADY_REGISTERED() throws Exception {
		String phone = uniquePhone();
		verify(signup(), phone);

		send(signup(), phone)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("PHONE_ALREADY_REGISTERED"));
	}

	@Test
	void 이미_인증을_마친_회원이_다시_발송하면_409_PHONE_ALREADY_VERIFIED() throws Exception {
		Session s = signup();
		Session verified = verify(s, uniquePhone());

		send(verified, uniquePhone())
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("PHONE_ALREADY_VERIFIED"));
	}

	// ---------- 확인 ----------

	@Test
	void 확인에_성공하면_ACTIVE가_되고_새_토큰으로_ACTIVE_전용_API를_통과한다() throws Exception {
		Session s = signup();
		String phone = uniquePhone();
		send(s, phone).andExpect(status().isAccepted());

		// 인증 전에는 403
		mockMvc.perform(get(ACTIVE_ONLY_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + s.accessToken))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("SIGNUP_INCOMPLETE"));

		JsonNode tokens = json(confirm(s, phone, smsSender.lastCodeFor(e164(phone)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.member.signupStatus").value("ACTIVE"))
				.andExpect(jsonPath("$.refreshToken").isString()));

		String newAccess = tokens.get("accessToken").asString();
		assertThat(jwtDecoder.decode(newAccess).getClaimAsString(JwtConfig.SIGNUP_STATUS_CLAIM)).isEqualTo("ACTIVE");
		// 인증 후에는 인가를 통과한다 (경로가 아직 없어 404)
		mockMvc.perform(get(ACTIVE_ONLY_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + newAccess))
				.andExpect(status().isNotFound());

		Member member = memberRepository.findById(s.memberId).orElseThrow();
		assertThat(member.isSignupCompleted()).isTrue();
		assertThat(member.getPhoneHmac()).isEqualTo(hmac(phone));
		assertThat(phoneCipher.decrypt(member.getPhoneEncrypted())).isEqualTo(e164(phone));
		assertThat(pointWalletRepository.findById(s.memberId)).isPresent()
				.get().satisfies(w -> assertThat(w.getBalance()).isZero());
		assertThat(redisTemplate.hasKey(OtpStore.codeKey(s.memberId, hmac(phone)))).isFalse();
	}

	@Test
	void 코드가_틀리면_400_OTP_INVALID이고_남은_횟수를_알려준다() throws Exception {
		Session s = signup();
		String phone = uniquePhone();
		send(s, phone).andExpect(status().isAccepted());

		confirm(s, phone, wrongCode(smsSender.lastCodeFor(e164(phone))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("OTP_INVALID"))
				.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("남은 시도 4회")));
	}

	@Test
	void 다섯_번_틀리면_429_OTP_ATTEMPT_EXCEEDED이고_이후_맞는_코드도_만료다() throws Exception {
		Session s = signup();
		String phone = uniquePhone();
		send(s, phone).andExpect(status().isAccepted());
		String code = smsSender.lastCodeFor(e164(phone));
		String wrong = wrongCode(code);

		for (int i = 0; i < 4; i++) {
			confirm(s, phone, wrong).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OTP_INVALID"));
		}
		confirm(s, phone, wrong)
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("OTP_ATTEMPT_EXCEEDED"));

		confirm(s, phone, code)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("OTP_EXPIRED"));
	}

	@Test
	void 만료된_코드는_400_OTP_EXPIRED() throws Exception {
		Session s = signup();
		String phone = uniquePhone();
		send(s, phone).andExpect(status().isAccepted());
		String code = smsSender.lastCodeFor(e164(phone));
		redisTemplate.delete(OtpStore.codeKey(s.memberId, hmac(phone))); // TTL 만료를 흉내 낸다

		confirm(s, phone, code)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("OTP_EXPIRED"));
	}

	@Test
	void 다른_회원이_받은_코드로는_확인할_수_없다() throws Exception {
		Session a = signup();
		Session b = signup();
		String phone = uniquePhone();
		send(a, phone).andExpect(status().isAccepted());

		confirm(b, phone, smsSender.lastCodeFor(e164(phone)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("OTP_EXPIRED"));
	}

	@Test
	void 인증하면_hide_pending이_hide_relation으로_옮겨진다() throws Exception {
		Session owner = verify(signup(), uniquePhone());
		Session newcomer = signup();
		String phone = uniquePhone();
		hidePendingRepository.save(new HidePending(owner.memberId, hmac(phone))); // owner 가 미가입 번호를 연락처로 올려둔 상태

		verify(newcomer, phone);

		assertThat(hidePendingRepository.findAllByPhoneHmac(hmac(phone))).isEmpty();
		assertThat(hideRelationRepository.findAllByIdTargetMemberId(newcomer.memberId))
				.extracting(HideRelation::getOwnerId).containsExactly(owner.memberId);
	}

	// ---------- 동시성 ----------

	@Test
	void 같은_번호로_동시에_발송하면_하나만_202이고_나머지는_OTP_COOLDOWN() throws Exception {
		Session s = signup();
		String phone = uniquePhone();

		List<MockHttpServletResponse> responses = runConcurrently(
				() -> send(s, phone).andReturn().getResponse(),
				() -> send(s, phone).andReturn().getResponse(),
				() -> send(s, phone).andReturn().getResponse());

		List<Integer> statuses = responses.stream().map(MockHttpServletResponse::getStatus).sorted().toList();
		assertThat(statuses).containsExactly(202, 429, 429);
		assertThat(responses.stream().filter(r -> r.getStatus() == 429))
				.allSatisfy(r -> assertThat(objectMapper.readTree(r.getContentAsString()).get("code").asString()).isEqualTo("OTP_COOLDOWN"));
	}

	@Test
	void 두_회원이_같은_번호를_동시에_확인하면_하나만_200이고_나머지는_409_PHONE_ALREADY_REGISTERED() throws Exception {
		Session a = signup();
		Session b = signup();
		String phone = uniquePhone();
		send(a, phone).andExpect(status().isAccepted());
		String codeA = smsSender.lastCodeFor(e164(phone));
		redisTemplate.delete(OtpStore.cooldownKey(hmac(phone))); // 두 번째 회원도 코드를 받을 수 있게 쿨다운 해제
		send(b, phone).andExpect(status().isAccepted());
		String codeB = smsSender.lastCodeFor(e164(phone));

		List<MockHttpServletResponse> responses = runConcurrently(
				() -> confirm(a, phone, codeA).andReturn().getResponse(),
				() -> confirm(b, phone, codeB).andReturn().getResponse());

		List<Integer> statuses = responses.stream().map(MockHttpServletResponse::getStatus).sorted().toList();
		assertThat(statuses).containsExactly(200, 409);
		MockHttpServletResponse conflict = responses.stream().filter(r -> r.getStatus() == 409).findFirst().orElseThrow();
		assertThat(objectMapper.readTree(conflict.getContentAsString()).get("code").asString()).isEqualTo("PHONE_ALREADY_REGISTERED");
		assertThat(memberRepository.existsByPhoneHmac(hmac(phone))).isTrue();
		long owners = List.of(a.memberId, b.memberId).stream()
				.map(id -> memberRepository.findById(id).orElseThrow())
				.filter(m -> hmac(phone).equals(m.getPhoneHmac()))
				.count();
		assertThat(owners).isEqualTo(1);
	}

	@Test
	void 틀린_코드를_동시에_여러_번_넣어도_시도_횟수가_정확히_세어진다() throws Exception {
		Session s = signup();
		String phone = uniquePhone();
		send(s, phone).andExpect(status().isAccepted());
		String wrong = wrongCode(smsSender.lastCodeFor(e164(phone)));

		List<MockHttpServletResponse> responses = runConcurrently(
				() -> confirm(s, phone, wrong).andReturn().getResponse(),
				() -> confirm(s, phone, wrong).andReturn().getResponse(),
				() -> confirm(s, phone, wrong).andReturn().getResponse());

		assertThat(responses).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(400));
		Object attempts = redisTemplate.opsForHash().get(OtpStore.codeKey(s.memberId, hmac(phone)), "attempts");
		assertThat(attempts).isEqualTo("3");
	}

	// ---------- 헬퍼 ----------

	private record Session(long memberId, String accessToken) {
	}

	private Session signup() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String body = "{\"email\":\"p" + suffix + "@test.com\",\"password\":\"pass1234\",\"nickname\":\"폰" + suffix + "\"}";
		JsonNode json = json(mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated()));
		return new Session(json.get("member").get("id").asLong(), json.get("accessToken").asString());
	}

	/** 발송 → 확인까지 마치고 ACTIVE 토큰을 가진 세션을 돌려준다 */
	private Session verify(Session s, String phone) throws Exception {
		send(s, phone).andExpect(status().isAccepted());
		JsonNode tokens = json(confirm(s, phone, smsSender.lastCodeFor(e164(phone))).andExpect(status().isOk()));
		return new Session(s.memberId, tokens.get("accessToken").asString());
	}

	private ResultActions send(Session s, String phone) throws Exception {
		return mockMvc.perform(post(SEND_URL)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + s.accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"phone\":\"" + phone + "\"}"));
	}

	private ResultActions confirm(Session s, String phone, String code) throws Exception {
		return mockMvc.perform(post(CONFIRM_URL)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + s.accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\"}"));
	}

	private JsonNode json(ResultActions actions) throws Exception {
		return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
	}

	private String hmac(String phone) {
		return phoneCipher.hmac(e164(phone));
	}

	private static String e164(String phone) {
		return com.pickone.global.crypto.PhoneNumber.toE164(phone);
	}

	/** 테스트마다 다른 번호. 010-XXXX-XXXX 형태 */
	private static String uniquePhone() {
		int a = ThreadLocalRandom.current().nextInt(1000, 10000);
		int b = ThreadLocalRandom.current().nextInt(1000, 10000);
		return "010-" + a + "-" + b;
	}

	private static String wrongCode(String code) {
		int digit = code.charAt(0) - '0';
		return ((digit + 1) % 10) + code.substring(1);
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
