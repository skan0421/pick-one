package com.pickone.vote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pickone.global.security.jwt.JwtTokenProvider;
import com.pickone.member.domain.SignupStatus;
import com.pickone.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 내가 투표한 고민 목록 한 페이지의 SQL 수 (N+1 방지). 기대: vote 행 조회 + 고민 fetch join + 득표 집계 = 3회.
 * 항목마다 고민·선택지·집계를 따로 읽으면 항목 수만큼 늘어난다.
 */
@IntegrationTest
class MyVotesQueryCountTest {

	private static final int PAGE_SIZE = 5;
	private static final int VOTES = 8;

	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper objectMapper;
	@Autowired JdbcTemplate jdbc;
	@Autowired JwtTokenProvider jwtTokenProvider;

	private final Logger sqlLogger = (Logger) LoggerFactory.getLogger("org.hibernate.SQL");
	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
	private Level originalLevel;

	@BeforeEach
	void attach() {
		originalLevel = sqlLogger.getLevel();
		sqlLogger.setLevel(Level.DEBUG);
		appender.start();
		sqlLogger.addAppender(appender);
	}

	@AfterEach
	void detach() {
		sqlLogger.detachAppender(appender);
		sqlLogger.setLevel(originalLevel);
	}

	@Test
	void 내_투표_목록_한_페이지는_항목_수와_무관하게_SQL_3회_이하로_조회한다() throws Exception {
		long me = activeMember();
		long author = activeMember();
		String meToken = token(me);
		String authorToken = token(author);
		for (int i = 0; i < VOTES; i++) {
			long optionId = createQuestion(authorToken, "질문 " + i);
			mockMvc.perform(post("/api/v1/questions/" + questionOf(optionId) + "/votes").header(HttpHeaders.AUTHORIZATION, "Bearer " + meToken)
					.contentType(MediaType.APPLICATION_JSON).content("{\"optionId\":" + optionId + "}")).andExpect(status().isCreated());
		}
		appender.list.clear();

		mockMvc.perform(get("/api/v1/members/me/votes?size=" + PAGE_SIZE).header(HttpHeaders.AUTHORIZATION, "Bearer " + meToken))
				.andExpect(status().isOk());

		List<String> selects = appender.list.stream()
				.map(ILoggingEvent::getFormattedMessage)
				.filter(sql -> sql.trim().toLowerCase().startsWith("select"))
				.toList();
		assertThat(selects).as("실행된 SELECT: \n" + String.join("\n", selects)).hasSizeLessThanOrEqualTo(3);
		assertThat(selects).anyMatch(sql -> sql.contains("question_option"));
		assertThat(selects).anyMatch(sql -> sql.toLowerCase().contains("count("));
	}

	private long activeMember() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String body = "{\"email\":\"mq" + suffix + "@test.com\",\"password\":\"pass1234\",\"nickname\":\"쿼리수" + suffix + "\"}";
		String response = mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		long memberId = objectMapper.readTree(response).get("member").get("id").asLong();
		jdbc.update("UPDATE member SET signup_status = 'ACTIVE' WHERE id = ?", memberId);
		jdbc.update("INSERT INTO point_wallet (member_id, balance, version, updated_at) VALUES (?, 0, 0, NOW(6))", memberId);
		return memberId;
	}

	private String token(long memberId) {
		return jwtTokenProvider.createAccessToken(memberId, SignupStatus.ACTIVE);
	}

	/** 3지선다 고민을 만들고 첫 선택지 ID 를 돌려준다 */
	private long createQuestion(String token, String content) throws Exception {
		String body = "{\"questionType\":\"TEXT\",\"content\":\"" + content + "\",\"options\":[{\"content\":\"a\"},{\"content\":\"b\"},{\"content\":\"c\"}]}";
		JsonNode json = objectMapper.readTree(mockMvc.perform(post("/api/v1/questions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
		return json.get("options").get(0).get("id").asLong();
	}

	private long questionOf(long optionId) {
		return jdbc.queryForObject("SELECT question_id FROM question_option WHERE id = ?", Long.class, optionId);
	}

}
