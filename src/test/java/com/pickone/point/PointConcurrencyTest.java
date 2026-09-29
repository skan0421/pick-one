package com.pickone.point;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pickone.global.security.jwt.JwtTokenProvider;
import com.pickone.member.domain.SignupStatus;
import com.pickone.point.service.OptimisticRetryExecutor;
import com.pickone.support.ConcurrencyTestSupport;
import com.pickone.support.IntegrationTest;
import com.pickone.support.ThreadSafeListAppender;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 투표·포인트 동시성 테스트. 모든 케이스 끝에 "지갑 잔액 = 원장 amount 합계" 불변식을 검증한다.
 *
 * 스레드 수 근거
 * - 지갑 경합 케이스는 총 시도 횟수(1 + wallet-max-retries = 4) 이하로 둔다. 같은 지갑을 놓고 경쟁하는 N개 스레드는
 *   각 실패가 다른 스레드의 커밋 1건 때문이고 그 커밋은 최대 N-1개이므로, 총 시도 A ≥ N 이면 전부 성공한다.
 * - 요청 스레드마다 DB 커넥션 1개를 트랜잭션 동안 점유하므로 HikariCP 기본 풀(10) 안에서 5개 이하로 둔다.
 * 재시도 횟수는 스케줄링에 따라 달라지므로 상한(N(N-1)/2)만 단정하고 실제 값은 출력한다.
 */
@IntegrationTest
class PointConcurrencyTest {

	private static final String TWO_OPTIONS = """
			{"questionType":"TEXT","content":"%s","options":[{"content":"카페"},{"content":"밥집"}]}""";
	private static final String RETRY_LOG = "낙관적 락 재시도:";
	private static final String IDEMPOTENCY_RERUN_LOG = "Idempotency-Key 경합";

	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper objectMapper;
	@Autowired JdbcTemplate jdbc;
	@Autowired JwtTokenProvider jwtTokenProvider;

	private final ThreadSafeListAppender retryLogs = new ThreadSafeListAppender(OptimisticRetryExecutor.class);

	@BeforeEach
	void attachRetryLogAppender() {
		retryLogs.attach();
	}

	@AfterEach
	void detachRetryLogAppender() {
		retryLogs.detach();
	}

	@Test
	void 같은_회원이_같은_고민에_동시에_투표하면_1건만_성공하고_포인트도_1번만_적립된다() throws Exception {
		int threads = 5;
		Session voter = activeMember();
		Created q = create(activeMember(), TWO_OPTIONS.formatted("같은 고민 동시"));

		List<MockHttpServletResponse> responses = ConcurrencyTestSupport.runConcurrently(
				repeat(threads, () -> vote(voter, q.id, q.option(0))));

		List<Integer> statuses = statuses(responses);
		assertThat(statuses).containsOnly(201, 409).containsOnlyOnce(201);
		assertThat(codes(responses, 409)).containsOnly("VOTE_ALREADY_VOTED");
		assertThat(voteCount(q.id)).isEqualTo(1);
		assertThat(ledgerCount(voter.memberId)).isEqualTo(1);
		assertThat(balance(voter.memberId)).isEqualTo(1);
		assertWalletEqualsLedger(voter.memberId);
		report("같은 회원·같은 고민 동시 투표 " + threads + "건", statuses);
	}

	@Test
	void 여러_회원이_같은_고민에_동시에_투표하면_전부_성공하고_각자_정확히_1P_적립된다() throws Exception {
		int threads = 5;
		Created q = create(activeMember(), TWO_OPTIONS.formatted("여러 회원 동시"));
		List<Session> voters = new ArrayList<>();
		List<Callable<MockHttpServletResponse>> tasks = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			Session voter = activeMember();
			voters.add(voter);
			int option = i % 2;
			tasks.add(() -> vote(voter, q.id, q.option(option)));
		}

		List<MockHttpServletResponse> responses = ConcurrencyTestSupport.runConcurrently(tasks);

		List<Integer> statuses = statuses(responses);
		assertThat(statuses).containsOnly(201).hasSize(threads);
		assertThat(voteCount(q.id)).isEqualTo(threads);
		for (Session voter : voters) {
			assertThat(balance(voter.memberId)).isEqualTo(1);
			assertThat(ledgerCount(voter.memberId)).isEqualTo(1);
			assertWalletEqualsLedger(voter.memberId);
		}
		// 마지막에 커밋된 응답은 전체 집계를 담는다
		long maxTotal = responses.stream().mapToLong(r -> json(r).get("result").get("totalVotes").asLong()).max().orElseThrow();
		assertThat(maxTotal).isEqualTo(threads);
		report("서로 다른 회원 " + threads + "명이 같은 고민에 동시 투표", statuses);
	}

	@Test
	void 같은_회원이_여러_고민에_동시에_투표하면_지갑_낙관적_락_재시도로_전부_반영되고_잔액이_원장_합계와_같다() throws Exception {
		int threads = 4; // 총 시도 4회(1 + 재시도 3) ≥ 스레드 4 → 전부 성공이 보장되는 최대값
		Session voter = activeMember();
		Session author = activeMember();
		List<Callable<MockHttpServletResponse>> tasks = new ArrayList<>();
		List<Long> questionIds = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			Created q = create(author, TWO_OPTIONS.formatted("지갑 경합 " + i));
			questionIds.add(q.id);
			tasks.add(() -> vote(voter, q.id, q.option(0)));
		}

		List<MockHttpServletResponse> responses = ConcurrencyTestSupport.runConcurrently(tasks);

		List<Integer> statuses = statuses(responses);
		assertThat(statuses).containsOnly(201).hasSize(threads);
		for (Long questionId : questionIds) {
			assertThat(voteCount(questionId)).isEqualTo(1);
		}
		assertThat(balance(voter.memberId)).isEqualTo(threads);
		assertThat(ledgerCount(voter.memberId)).isEqualTo(threads);
		// 원장 balance_after 가 1,2,3,4 로 끊김 없이 이어진다 (재시도 없이 겹쳐 썼다면 중복값이 생긴다)
		List<Long> balanceAfters = jdbc.queryForList("SELECT balance_after FROM point_ledger WHERE member_id = ? ORDER BY balance_after", Long.class, voter.memberId);
		assertThat(balanceAfters).containsExactly(1L, 2L, 3L, 4L);
		assertWalletEqualsLedger(voter.memberId);
		long retries = retryLogs.count(RETRY_LOG);
		assertThat(retries).isLessThanOrEqualTo((long) threads * (threads - 1) / 2);
		report("같은 회원이 서로 다른 고민 " + threads + "개에 동시 투표 (지갑 경합)", statuses);
	}

	@Test
	void 같은_Idempotency_Key로_boost를_동시에_요청하면_1번만_차감되고_모두_같은_응답을_받는다() throws Exception {
		int threads = 3;
		Session me = activeMember();
		grant(me, 300);
		Created q = create(me, TWO_OPTIONS.formatted("같은 키 동시 boost"));
		String key = UUID.randomUUID().toString();

		List<MockHttpServletResponse> responses = ConcurrencyTestSupport.runConcurrently(
				repeat(threads, () -> boost(me, q.id, key)));

		List<Integer> statuses = statuses(responses);
		assertThat(statuses).containsOnly(200).hasSize(threads);
		List<String> bodies = responses.stream().map(PointConcurrencyTest::content).distinct().toList();
		assertThat(bodies).as("세 응답이 모두 같다 (ledgerId, balanceAfter, boostedUntil)").hasSize(1);
		assertThat(json(responses.get(0)).get("balanceAfter").asLong()).isEqualTo(200);
		assertThat(balance(me.memberId)).isEqualTo(200);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_ledger WHERE idempotency_key = ?", Long.class, key)).isEqualTo(1);
		assertWalletEqualsLedger(me.memberId);
		report("같은 Idempotency-Key 로 boost 동시 " + threads + "건 (멱등 재실행 " + retryLogs.count(IDEMPOTENCY_RERUN_LOG) + "회)", statuses);
	}

	@Test
	void 잔액이_딱_1번_boost_가능할_때_서로_다른_키로_동시_요청하면_1번만_성공하고_잔액은_음수가_되지_않는다() throws Exception {
		int threads = 3;
		Session me = activeMember();
		grant(me, 100);
		Created q = create(me, TWO_OPTIONS.formatted("잔액 1회분 동시 boost"));

		List<MockHttpServletResponse> responses = ConcurrencyTestSupport.runConcurrently(
				repeat(threads, () -> boost(me, q.id, UUID.randomUUID().toString())));

		List<Integer> statuses = statuses(responses);
		assertThat(statuses).containsOnly(200, 409).containsOnlyOnce(200);
		assertThat(codes(responses, 409)).containsOnly("POINT_INSUFFICIENT");
		assertThat(balance(me.memberId)).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_ledger WHERE member_id = ? AND tx_type = 'BOOST_USE'", Long.class, me.memberId)).isEqualTo(1);
		assertWalletEqualsLedger(me.memberId);
		report("잔액 100 에서 서로 다른 키로 boost 동시 " + threads + "건", statuses);
	}

	@Test
	void 일일_상한_직전에_동시에_투표하면_적립은_정확히_상한까지만_된다() throws Exception {
		int threads = 3;
		Session voter = activeMember();
		grantToday(voter, 49); // 오늘 이미 49P 적립
		Session author = activeMember();
		List<Callable<MockHttpServletResponse>> tasks = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			Created q = create(author, TWO_OPTIONS.formatted("상한 경합 " + i));
			tasks.add(() -> vote(voter, q.id, q.option(0)));
		}

		List<MockHttpServletResponse> responses = ConcurrencyTestSupport.runConcurrently(tasks);

		List<Integer> statuses = statuses(responses);
		assertThat(statuses).containsOnly(201).hasSize(threads);
		long earned = responses.stream().filter(r -> json(r).get("pointReward").get("earned").asBoolean()).count();
		assertThat(earned).as("적립된 투표 수").isEqualTo(1);
		assertThat(balance(voter.memberId)).isEqualTo(50);
		assertWalletEqualsLedger(voter.memberId);
		report("오늘 49P 상태에서 동시 투표 " + threads + "건 (적립 " + earned + "건)", statuses);
	}

	// =============================== 헬퍼 ===============================

	private record Session(long memberId, String accessToken) {
	}

	private record Created(long id, List<Long> optionIds) {

		long option(int index) {
			return optionIds.get(index);
		}

	}

	private void report(String label, List<Integer> statuses) {
		long success = statuses.stream().filter(s -> s < 300).count();
		String failures = statuses.stream().filter(s -> s >= 300).map(String::valueOf).collect(Collectors.joining(","));
		System.out.printf("[동시성] %s → 성공 %d건, 실패 %d건%s, 낙관적 락 재시도 %d회%n",
				label, success, statuses.size() - success, failures.isEmpty() ? "" : " (" + failures + ")", retryLogs.count(RETRY_LOG));
	}

	private static List<Callable<MockHttpServletResponse>> repeat(int times, Callable<MockHttpServletResponse> task) {
		List<Callable<MockHttpServletResponse>> tasks = new ArrayList<>();
		for (int i = 0; i < times; i++) {
			tasks.add(task);
		}
		return tasks;
	}

	private Session activeMember() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String body = "{\"email\":\"cc" + suffix + "@test.com\",\"password\":\"pass1234\",\"nickname\":\"동시" + suffix + "\"}";
		JsonNode json = objectMapper.readTree(mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
		long memberId = json.get("member").get("id").asLong();
		jdbc.update("UPDATE member SET signup_status = 'ACTIVE' WHERE id = ?", memberId);
		jdbc.update("INSERT INTO point_wallet (member_id, balance, version, updated_at) VALUES (?, 0, 0, NOW(6))", memberId);
		return new Session(memberId, jwtTokenProvider.createAccessToken(memberId, SignupStatus.ACTIVE));
	}

	/** 테스트용 지급 (이틀 전 적립으로 기록해 오늘 상한에 영향 없음). 지갑과 원장을 함께 올려 불변식을 유지한다 */
	private void grant(Session s, long amount) {
		insertGrant(s, amount, "DATE_SUB(NOW(6), INTERVAL 2 DAY)");
	}

	/** 오늘 적립한 것으로 기록 (일일 상한 판정에 포함). JVM 시각을 쓴다 — 앱의 @CreationTimestamp 와 같은 시계 (troubleshooting.md 17) */
	private void grantToday(Session s, long amount) {
		insertGrant(s, amount, java.time.LocalDateTime.now());
	}

	private void insertGrant(Session s, long amount, String createdAtExpression) {
		jdbc.update("UPDATE point_wallet SET balance = balance + ? WHERE member_id = ?", amount, s.memberId);
		jdbc.update("INSERT INTO point_ledger (member_id, amount, balance_after, tx_type, ref_type, ref_id, idempotency_key, created_at) "
				+ "VALUES (?, ?, ?, 'VOTE_REWARD', NULL, NULL, ?, " + createdAtExpression + ")",
				s.memberId, amount, balance(s.memberId), "test:" + UUID.randomUUID());
	}

	private void insertGrant(Session s, long amount, java.time.LocalDateTime createdAt) {
		jdbc.update("UPDATE point_wallet SET balance = balance + ? WHERE member_id = ?", amount, s.memberId);
		jdbc.update("INSERT INTO point_ledger (member_id, amount, balance_after, tx_type, ref_type, ref_id, idempotency_key, created_at) "
				+ "VALUES (?, ?, ?, 'VOTE_REWARD', NULL, NULL, ?, ?)",
				s.memberId, amount, balance(s.memberId), "test:" + UUID.randomUUID(), createdAt);
	}

	private Created create(Session s, String body) throws Exception {
		JsonNode json = objectMapper.readTree(mockMvc.perform(post("/api/v1/questions")
				.header(HttpHeaders.AUTHORIZATION, bearer(s))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
		List<Long> optionIds = new ArrayList<>();
		json.get("options").forEach(o -> optionIds.add(o.get("id").asLong()));
		return new Created(json.get("id").asLong(), optionIds);
	}

	private MockHttpServletResponse vote(Session s, long questionId, long optionId) throws Exception {
		return mockMvc.perform(post("/api/v1/questions/" + questionId + "/votes")
				.header(HttpHeaders.AUTHORIZATION, bearer(s))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"optionId\":" + optionId + "}")).andReturn().getResponse();
	}

	private MockHttpServletResponse boost(Session s, long questionId, String idempotencyKey) throws Exception {
		return mockMvc.perform(post("/api/v1/questions/" + questionId + "/boosts")
				.header(HttpHeaders.AUTHORIZATION, bearer(s))
				.header("Idempotency-Key", idempotencyKey)).andReturn().getResponse();
	}

	private long balance(long memberId) {
		return jdbc.queryForObject("SELECT balance FROM point_wallet WHERE member_id = ?", Long.class, memberId);
	}

	private long voteCount(long questionId) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM vote WHERE question_id = ?", Long.class, questionId);
	}

	private long ledgerCount(long memberId) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM point_ledger WHERE member_id = ? AND ref_type = 'VOTE'", Long.class, memberId);
	}

	/** 불변식: 지갑 잔액 = 원장 amount 합계 */
	private void assertWalletEqualsLedger(long memberId) {
		Long sum = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM point_ledger WHERE member_id = ?", Long.class, memberId);
		assertThat(balance(memberId)).as("지갑 잔액 = 원장 합계").isEqualTo(sum);
	}

	private static List<Integer> statuses(List<MockHttpServletResponse> responses) {
		return responses.stream().map(MockHttpServletResponse::getStatus).toList();
	}

	private List<String> codes(List<MockHttpServletResponse> responses, int status) {
		return responses.stream().filter(r -> r.getStatus() == status).map(r -> json(r).get("code").asString()).toList();
	}

	private JsonNode json(MockHttpServletResponse response) {
		return objectMapper.readTree(content(response));
	}

	private static String content(MockHttpServletResponse response) {
		try {
			return response.getContentAsString();
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static String bearer(Session s) {
		return "Bearer " + s.accessToken;
	}

}
