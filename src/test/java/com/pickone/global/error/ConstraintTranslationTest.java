package com.pickone.global.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pickone.global.security.jwt.JwtTokenProvider;
import com.pickone.member.domain.SignupStatus;
import com.pickone.question.domain.Question;
import com.pickone.question.repository.QuestionRepository;
import com.pickone.support.IntegrationTest;
import com.pickone.vote.domain.Vote;
import com.pickone.vote.repository.VoteRepository;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 실제 MariaDB 에서 FK(1452)·CHECK(4025) 위반이 났을 때 Hibernate MariaDB 방언이 제약 이름을 추출하고,
 * 그 이름이 ErrorCode 로 번역되는지 확인한다. 유니크(1062)는 회원 가입 테스트가 이미 검증한다.
 */
@IntegrationTest
class ConstraintTranslationTest {

	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper objectMapper;
	@Autowired JdbcTemplate jdbc;
	@Autowired JwtTokenProvider jwtTokenProvider;
	@Autowired QuestionRepository questionRepository;
	@Autowired VoteRepository voteRepository;
	@Autowired EntityManager entityManager;
	@Autowired PlatformTransactionManager transactionManager;

	@Test
	void 다른_고민의_선택지로_투표_행을_넣으면_fk_vote_option_이_VOTE_OPTION_MISMATCH_로_번역된다() throws Exception {
		long author = activeMemberId();
		long q1 = createQuestion(author);
		long q2 = createQuestion(author);
		long voter = activeMemberId();

		Throwable thrown = catchThrowable(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
			Question question = questionRepository.findWithAuthorAndOptionsById(q1).orElseThrow();
			Question other = questionRepository.findWithAuthorAndOptionsById(q2).orElseThrow();
			voteRepository.save(Vote.cast(question, other.getOptions().get(0), voter));
		}));

		String constraint = ConstraintViolations.constraintName(thrown);
		assertThat(constraint).as("추출된 제약 이름").endsWithIgnoringCase("fk_vote_option");
		assertThat(ErrorCode.fromConstraintName(constraint)).contains(ErrorCode.VOTE_OPTION_MISMATCH);
	}

	@Test
	void 지갑_잔액을_음수로_만들면_chk_point_wallet_balance_가_POINT_INSUFFICIENT_로_번역된다() throws Exception {
		long memberId = activeMemberId();

		Throwable thrown = catchThrowable(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
				entityManager.createNativeQuery("UPDATE point_wallet SET balance = -1 WHERE member_id = :id")
						.setParameter("id", memberId)
						.executeUpdate()));

		String constraint = ConstraintViolations.constraintName(thrown);
		assertThat(constraint).as("추출된 제약 이름 (MariaDB Connector/J 의 '(conn=N) ' 접두사에 의존)").endsWithIgnoringCase("chk_point_wallet_balance");
		assertThat(ErrorCode.fromConstraintName(constraint)).contains(ErrorCode.POINT_INSUFFICIENT);
		assertThat(jdbc.queryForObject("SELECT balance FROM point_wallet WHERE member_id = ?", Long.class, memberId)).isZero();
	}

	private long activeMemberId() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String body = "{\"email\":\"ct" + suffix + "@test.com\",\"password\":\"pass1234\",\"nickname\":\"제약" + suffix + "\"}";
		String response = mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		long memberId = objectMapper.readTree(response).get("member").get("id").asLong();
		jdbc.update("UPDATE member SET signup_status = 'ACTIVE' WHERE id = ?", memberId);
		jdbc.update("INSERT INTO point_wallet (member_id, balance, version, updated_at) VALUES (?, 0, 0, NOW(6))", memberId);
		return memberId;
	}

	private long createQuestion(long memberId) throws Exception {
		String token = jwtTokenProvider.createAccessToken(memberId, SignupStatus.ACTIVE);
		String body = "{\"questionType\":\"TEXT\",\"content\":\"제약\",\"options\":[{\"content\":\"a\"},{\"content\":\"b\"}]}";
		String response = mockMvc.perform(post("/api/v1/questions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(response).get("id").asLong();
	}

}
