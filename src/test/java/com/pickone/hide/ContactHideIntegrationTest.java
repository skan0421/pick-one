package com.pickone.hide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pickone.global.crypto.PhoneCipher;
import com.pickone.global.crypto.PhoneNumber;
import com.pickone.global.security.jwt.JwtTokenProvider;
import com.pickone.hide.domain.HidePending;
import com.pickone.hide.repository.HidePendingRepository;
import com.pickone.member.domain.SignupStatus;
import com.pickone.support.IntegrationTest;
import com.pickone.support.RecordingSmsSender;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 지인에게 숨기기 통합 테스트 (docs/api.md 7장): 연락처 교체, 켜기/끄기, 휴대폰 인증 시 매칭, 5,000건 성능 */
@IntegrationTest
class ContactHideIntegrationTest {

	private static final String QUESTION_BODY = """
			{"questionType":"TEXT","content":"%s","options":[{"content":"카페"},{"content":"밥집"}]}""";

	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper objectMapper;
	@Autowired JdbcTemplate jdbc;
	@Autowired JwtTokenProvider jwtTokenProvider;
	@Autowired PhoneCipher phoneCipher;
	@Autowired RecordingSmsSender smsSender;
	@Autowired HidePendingRepository hidePendingRepository;
	@Autowired PlatformTransactionManager transactionManager;

	@Nested
	class 연락처_교체 {

		@Test
		void 가입_회원은_hide_relation_미가입_번호는_hide_pending_이_되고_내_번호와_형식_오류는_제외된다() throws Exception {
			String myPhone = uniquePhone();
			Session me = activeMember(myPhone);
			String friendPhone = uniquePhone();
			Session friend = activeMember(friendPhone);
			String unknown = uniquePhone();

			// 지인 번호(형식 변형·중복 포함), 미가입 번호, 내 번호, 유선 번호
			ResultActions result = upload(me, List.of(friendPhone, "+82 " + friendPhone.substring(1), unknown, myPhone, "02-1234-5678"));

			result.andExpect(status().isOk())
					.andExpect(jsonPath("$.received").value(2))
					.andExpect(jsonPath("$.matchedMembers").value(1))
					.andExpect(jsonPath("$.pending").value(1));
			assertThat(relationTargets(me.memberId)).containsExactly(friend.memberId);
			assertThat(pendingHmacs(me.memberId)).containsExactly(hmac(unknown));
		}

		@Test
		void 다시_올리면_빠진_번호는_삭제되고_새_번호는_추가된다() throws Exception {
			Session me = activeMember(uniquePhone());
			Session a = activeMember(uniquePhone());
			Session b = activeMember(uniquePhone());
			Session c = activeMember(uniquePhone());
			String pendingOld = uniquePhone();
			String pendingNew = uniquePhone();
			upload(me, List.of(a.phone, b.phone, pendingOld)).andExpect(status().isOk());
			assertThat(relationTargets(me.memberId)).containsExactlyInAnyOrder(a.memberId, b.memberId);

			upload(me, List.of(b.phone, c.phone, pendingNew))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.matchedMembers").value(2))
					.andExpect(jsonPath("$.pending").value(1));

			assertThat(relationTargets(me.memberId)).containsExactlyInAnyOrder(b.memberId, c.memberId);
			assertThat(pendingHmacs(me.memberId)).containsExactly(hmac(pendingNew));

			// 빈 목록이면 전부 지운다
			upload(me, List.of()).andExpect(status().isOk()).andExpect(jsonPath("$.received").value(0));
			assertThat(relationTargets(me.memberId)).isEmpty();
			assertThat(pendingHmacs(me.memberId)).isEmpty();
		}

		@Test
		void 오천건을_넘으면_400_CONTACTS_TOO_MANY_이고_phones가_없으면_VALIDATION_ERROR() throws Exception {
			Session me = activeMember(uniquePhone());
			List<String> tooMany = new ArrayList<>();
			for (int i = 0; i < 5001; i++) {
				tooMany.add("010" + String.format("%08d", i));
			}

			upload(me, tooMany)
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("CONTACTS_TOO_MANY"));
			mockMvc.perform(put("/api/v1/members/me/contacts").header(HttpHeaders.AUTHORIZATION, bearer(me))
					.contentType(MediaType.APPLICATION_JSON).content("{}"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		}

		@Test
		void 업로드_중_로그에_번호_원문이_남지_않는다() throws Exception {
			Session me = activeMember(uniquePhone());
			String friendPhone = uniquePhone();
			activeMember(friendPhone);
			Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
			ListAppender<ILoggingEvent> appender = new ListAppender<>();
			appender.start();
			root.addAppender(appender);
			try {
				upload(me, List.of(friendPhone, uniquePhone())).andExpect(status().isOk());
			}
			finally {
				root.detachAppender(appender);
			}

			String digits = friendPhone.replace("-", "");
			assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
					.noneMatch(m -> m.contains(friendPhone) || m.contains(digits) || m.contains(PhoneNumber.toE164(friendPhone)));
			assertThat(jdbc.queryForList("SELECT phone_hmac FROM hide_pending WHERE owner_id = ?", String.class, me.memberId))
					.noneMatch(h -> h.contains(digits));
		}

	}

	@Nested
	class 켜기_끄기 {

		@Test
		void 켜면_등록한_지인의_피드에서_내_고민이_빠지고_끄면_다시_보인다() throws Exception {
			Session me = activeMember(uniquePhone());
			Session friend = activeMember(uniquePhone());
			long mine = createQuestion(me);
			upload(me, List.of(friend.phone, uniquePhone())).andExpect(status().isOk());
			assertThat(feedIds(friend)).contains(mine); // 관계는 있지만 아직 꺼져 있음

			toggle(me, true)
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.enabled").value(true))
					.andExpect(jsonPath("$.hiddenMembers").value(1))
					.andExpect(jsonPath("$.pending").value(1));
			assertThat(feedIds(friend)).doesNotContain(mine);
			mockMvc.perform(get("/api/v1/questions/" + mine).header(HttpHeaders.AUTHORIZATION, bearer(friend))).andExpect(status().isNotFound());
			mockMvc.perform(get("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, bearer(me)))
					.andExpect(jsonPath("$.hideFromContacts").value(true));

			toggle(me, false).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
			assertThat(feedIds(friend)).contains(mine);
		}

		@Test
		void 연락처_없이_켜면_200이고_hiddenMembers는_0이다() throws Exception {
			toggle(activeMember(uniquePhone()), true)
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.hiddenMembers").value(0))
					.andExpect(jsonPath("$.pending").value(0));
		}

	}

	@Test
	void 미가입_번호가_나중에_휴대폰_인증을_마치면_hide_pending이_hide_relation으로_옮겨져_피드에서_숨겨진다() throws Exception {
		Session owner = activeMember(uniquePhone());
		long mine = createQuestion(owner);
		String phone = uniquePhone();
		upload(owner, List.of(phone)).andExpect(status().isOk())
				.andExpect(jsonPath("$.matchedMembers").value(0))
				.andExpect(jsonPath("$.pending").value(1));
		toggle(owner, true).andExpect(status().isOk());

		// 그 번호의 주인이 가입해 실제 SMS OTP 흐름으로 인증을 마친다
		Session newcomer = signupAndVerify(phone);

		assertThat(pendingHmacs(owner.memberId)).isEmpty();
		assertThat(relationTargets(owner.memberId)).containsExactly(newcomer.memberId);
		assertThat(feedIds(newcomer)).doesNotContain(mine);
		// 다시 올리면 이제는 가입 회원으로 매칭된다
		upload(owner, List.of(phone)).andExpect(status().isOk())
				.andExpect(jsonPath("$.matchedMembers").value(1))
				.andExpect(jsonPath("$.pending").value(0));
		assertThat(feedIds(newcomer)).doesNotContain(mine);
	}

	@Test
	void 연락처_오천건_교체_처리_시간을_측정한다() throws Exception {
		Session me = activeMember(uniquePhone());
		int matchedMembers = 10;
		List<String> phones = new ArrayList<>();
		int base = ThreadLocalRandom.current().nextInt(10_000_000, 90_000_000);
		for (int i = 0; i < 5000; i++) {
			phones.add("010" + String.format("%08d", base + i));
		}
		for (int i = 0; i < matchedMembers; i++) {
			activeMember(phones.get(i)); // 앞 10건은 가입 회원
		}

		long start = System.nanoTime();
		upload(me, phones).andExpect(status().isOk())
				.andExpect(jsonPath("$.received").value(5000))
				.andExpect(jsonPath("$.matchedMembers").value(matchedMembers))
				.andExpect(jsonPath("$.pending").value(5000 - matchedMembers));
		long apiMs = (System.nanoTime() - start) / 1_000_000;

		// 재교체(삭제 5,000 + 삽입 5,000)
		start = System.nanoTime();
		upload(me, phones).andExpect(status().isOk());
		long replaceMs = (System.nanoTime() - start) / 1_000_000;

		// 비교 기준: 같은 5,000건을 JPA saveAll(IDENTITY, 건별 INSERT) 로 넣을 때
		Session other = activeMember(uniquePhone());
		List<HidePending> entities = phones.stream().map(p -> new HidePending(other.memberId, hmac(p))).toList();
		start = System.nanoTime();
		new TransactionTemplate(transactionManager).executeWithoutResult(tx -> hidePendingRepository.saveAll(entities));
		long jpaMs = (System.nanoTime() - start) / 1_000_000;

		assertThat(relationTargets(me.memberId)).hasSize(matchedMembers);
		assertThat(pendingHmacs(me.memberId)).hasSize(5000 - matchedMembers);
		assertThat(apiMs).isLessThan(15_000);
		System.out.printf("[성능] 연락처 5,000건 교체 API(JdbcTemplate batchUpdate, 정규화+HMAC 포함): 최초 %d ms, 재교체 %d ms / JPA saveAll 5,000건(IDENTITY): %d ms%n",
				apiMs, replaceMs, jpaMs);
	}

	// =============================== 헬퍼 ===============================

	private record Session(long memberId, String phone, String accessToken) {
	}

	/** 가입 → ACTIVE → phone_hmac 세팅 (인증 흐름을 거치지 않고 DB 로 직접). 실제 흐름은 signupAndVerify 가 검증 */
	private Session activeMember(String phone) throws Exception {
		Session s = signup();
		jdbc.update("UPDATE member SET signup_status = 'ACTIVE', phone_hmac = ? WHERE id = ?", hmac(phone), s.memberId);
		return new Session(s.memberId, phone, jwtTokenProvider.createAccessToken(s.memberId, SignupStatus.ACTIVE));
	}

	private Session signup() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String body = "{\"email\":\"hd" + suffix + "@test.com\",\"password\":\"pass1234\",\"nickname\":\"숨김" + suffix + "\"}";
		JsonNode json = json(mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated()));
		return new Session(json.get("member").get("id").asLong(), null, json.get("accessToken").asString());
	}

	/** 실제 SMS OTP 발송·확인 흐름으로 ACTIVE 가 된 세션 */
	private Session signupAndVerify(String phone) throws Exception {
		Session s = signup();
		mockMvc.perform(post("/api/v1/phone-verifications").header(HttpHeaders.AUTHORIZATION, bearer(s))
				.contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"" + phone + "\"}")).andExpect(status().isAccepted());
		String code = smsSender.lastCodeFor(PhoneNumber.toE164(phone));
		JsonNode tokens = json(mockMvc.perform(post("/api/v1/phone-verifications/confirm").header(HttpHeaders.AUTHORIZATION, bearer(s))
				.contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\"}"))
				.andExpect(status().isOk()));
		return new Session(s.memberId, phone, tokens.get("accessToken").asString());
	}

	private ResultActions upload(Session s, List<String> phones) throws Exception {
		return mockMvc.perform(put("/api/v1/members/me/contacts")
				.header(HttpHeaders.AUTHORIZATION, bearer(s))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("phones", phones))));
	}

	private ResultActions toggle(Session s, boolean enabled) throws Exception {
		return mockMvc.perform(put("/api/v1/members/me/hide-from-contacts")
				.header(HttpHeaders.AUTHORIZATION, bearer(s))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"enabled\":" + enabled + "}"));
	}

	private long createQuestion(Session s) throws Exception {
		return json(mockMvc.perform(post("/api/v1/questions")
				.header(HttpHeaders.AUTHORIZATION, bearer(s))
				.contentType(MediaType.APPLICATION_JSON)
				.content(QUESTION_BODY.formatted("숨김 " + UUID.randomUUID()))).andExpect(status().isCreated())).get("id").asLong();
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

	private List<Long> relationTargets(long ownerId) {
		return jdbc.queryForList("SELECT target_member_id FROM hide_relation WHERE owner_id = ? ORDER BY target_member_id", Long.class, ownerId);
	}

	private List<String> pendingHmacs(long ownerId) {
		return jdbc.queryForList("SELECT phone_hmac FROM hide_pending WHERE owner_id = ?", String.class, ownerId);
	}

	private String hmac(String phone) {
		return phoneCipher.hmac(PhoneNumber.toE164(phone));
	}

	private static String uniquePhone() {
		int a = ThreadLocalRandom.current().nextInt(1000, 10000);
		int b = ThreadLocalRandom.current().nextInt(1000, 10000);
		return "010-" + a + "-" + b;
	}

	private static String bearer(Session s) {
		return "Bearer " + s.accessToken;
	}

	private JsonNode json(ResultActions actions) throws Exception {
		return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
	}

}
