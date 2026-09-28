package com.pickone.vote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pickone.global.security.jwt.JwtTokenProvider;
import com.pickone.member.domain.SignupStatus;
import com.pickone.support.IntegrationTest;
import com.pickone.vote.repository.VoteQueryRepositoryImpl;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * 내가 투표한 고민 목록 SQL 의 실행 계획. V5 의 idx_vote_member_id_created_at 을 타고 filesort 없이 정렬되는지 확인한다.
 * 실제 서비스가 실행하는 SQL(VoteQueryRepositoryImpl.myVotesSql)을 그대로 EXPLAIN 한다.
 */
@IntegrationTest
class MyVotesExplainTest {

	private static final int VOTES = 40;
	private static final int OTHER_MEMBERS = 60; // 다른 회원의 표를 넣어 내 표가 전체의 일부(약 1.6%)가 되게 한다
	private static final String INDEX = "idx_vote_member_id_created_at";

	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper objectMapper;
	@Autowired JdbcTemplate jdbc;
	@Autowired JwtTokenProvider jwtTokenProvider;

	@Test
	void 내_투표_목록은_member_created_at_인덱스로_읽고_filesort가_없다() throws Exception {
		long me = activeMember();
		long author = activeMember();
		String meToken = jwtTokenProvider.createAccessToken(me, SignupStatus.ACTIVE);
		String authorToken = jwtTokenProvider.createAccessToken(author, SignupStatus.ACTIVE);
		for (int i = 0; i < VOTES; i++) {
			long optionId = createQuestion(authorToken, "계획 " + i);
			long questionId = jdbc.queryForObject("SELECT question_id FROM question_option WHERE id = ?", Long.class, optionId);
			mockMvc.perform(post("/api/v1/questions/" + questionId + "/votes").header(HttpHeaders.AUTHORIZATION, "Bearer " + meToken)
					.contentType(MediaType.APPLICATION_JSON).content("{\"optionId\":" + optionId + "}")).andExpect(status().isCreated());
		}
		insertOtherMembersVotes(OTHER_MEMBERS);
		jdbc.execute("ANALYZE TABLE vote");
		long totalVotes = jdbc.queryForObject("SELECT COUNT(*) FROM vote", Long.class);
		System.out.printf("[EXPLAIN] vote 전체 %d행, 내 표 %d행%n", totalVotes, VOTES);
		Map<String, Object> mid = jdbc.queryForMap(
				"SELECT id, created_at FROM vote WHERE member_id = ? ORDER BY created_at DESC, id DESC LIMIT 1 OFFSET 20", me);

		// 첫 페이지 / 커서 이후 페이지 두 가지 SQL
		String firstPage = bind(VoteQueryRepositoryImpl.myVotesSql(false), me, null, null);
		String afterCursor = bind(VoteQueryRepositoryImpl.myVotesSql(true), me, String.valueOf(mid.get("created_at")), mid.get("id").toString());

		for (String label : List.of("첫 페이지", "커서 이후")) {
			String sql = label.equals("첫 페이지") ? firstPage : afterCursor;
			List<Map<String, Object>> plan = jdbc.queryForList("EXPLAIN " + sql);
			Map<String, Object> voteRow = plan.stream().filter(r -> "v".equals(r.get("table"))).findFirst().orElseThrow();
			String extra = String.valueOf(voteRow.get("Extra"));
			System.out.printf("[EXPLAIN] 내 투표 목록 %s: table=v key=%s type=%s rows=%s Extra=%s%n",
					label, voteRow.get("key"), voteRow.get("type"), voteRow.get("rows"), extra);
			plan.forEach(r -> System.out.printf("[EXPLAIN]   %s | %s | %s | key=%s | rows=%s | %s%n",
					r.get("id"), r.get("select_type"), r.get("table"), r.get("key"), r.get("rows"), r.get("Extra")));

			assertThat(voteRow.get("key")).as(label + " vote 인덱스").isEqualTo(INDEX);
			assertThat(extra.toLowerCase()).as(label + " Extra").doesNotContain("filesort").doesNotContain("using temporary");
		}
	}

	/**
	 * 회원 N명을 DB 에 직접 만들고 모든 고민에 투표한 행을 배치로 넣는다 (API 를 거치지 않아 빠르다).
	 * 표가 한 회원에 몰려 있으면 옵티마이저가 풀스캔을 고르므로, 실제 서비스처럼 여러 회원에 분산된 분포를 만든다.
	 */
	private void insertOtherMembersVotes(int members) {
		List<Map<String, Object>> options = jdbc.queryForList(
				"SELECT o.id AS option_id, o.question_id FROM question_option o WHERE o.sort_order = 1 AND o.question_id IN "
						+ "(SELECT question_id FROM vote WHERE member_id = (SELECT member_id FROM vote ORDER BY id DESC LIMIT 1))");
		List<Object[]> votes = new java.util.ArrayList<>();
		for (int i = 0; i < members; i++) {
			String suffix = UUID.randomUUID().toString().substring(0, 8);
			jdbc.update("INSERT INTO member (email, password_hash, nickname, hide_from_contacts, status, signup_status, created_at, updated_at) "
					+ "VALUES (?, NULL, ?, b'0', 'ACTIVE', 'ACTIVE', NOW(6), NOW(6))", "bulk" + suffix + "@test.com", "벌크" + suffix);
			long memberId = jdbc.queryForObject("SELECT id FROM member WHERE nickname = ?", Long.class, "벌크" + suffix);
			for (Map<String, Object> o : options) {
				votes.add(new Object[] {o.get("question_id"), o.get("option_id"), memberId});
			}
		}
		jdbc.batchUpdate("INSERT INTO vote (question_id, option_id, member_id, created_at) VALUES (?, ?, ?, NOW(6))", votes);
	}

	/** 네이티브 SQL 의 이름 파라미터를 리터럴로 치환한다 (EXPLAIN 용) */
	private static String bind(String sql, long me, String cursorCreatedAt, String cursorId) {
		String bound = sql.replace(":me", String.valueOf(me)).replace(":limit", "21");
		if (cursorCreatedAt != null) {
			bound = bound.replace(":cursorCreatedAt", "'" + cursorCreatedAt + "'").replace(":cursorId", cursorId);
		}
		return bound;
	}

	private long activeMember() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String body = "{\"email\":\"ex" + suffix + "@test.com\",\"password\":\"pass1234\",\"nickname\":\"계획" + suffix + "\"}";
		String response = mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		long memberId = objectMapper.readTree(response).get("member").get("id").asLong();
		jdbc.update("UPDATE member SET signup_status = 'ACTIVE' WHERE id = ?", memberId);
		jdbc.update("INSERT INTO point_wallet (member_id, balance, version, updated_at) VALUES (?, 0, 0, NOW(6))", memberId);
		return memberId;
	}

	private long createQuestion(String token, String content) throws Exception {
		String body = "{\"questionType\":\"TEXT\",\"content\":\"" + content + "\",\"options\":[{\"content\":\"a\"},{\"content\":\"b\"}]}";
		String response = mockMvc.perform(post("/api/v1/questions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(response).get("options").get(0).get("id").asLong();
	}

}
