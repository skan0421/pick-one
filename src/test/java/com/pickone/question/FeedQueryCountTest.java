package com.pickone.question;

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
import tools.jackson.databind.ObjectMapper;

/**
 * 피드 한 페이지에 실행되는 SQL 수를 센다 (N+1 방지).
 * 기대: ID 조회(단계별 1회, 전환 시 2회) + 작성자·선택지 fetch join 1회 → 최대 3회.
 * 선택지나 작성자를 항목마다 따로 읽으면 항목 수만큼 늘어나므로 상한으로 잡는다.
 */
@IntegrationTest
class FeedQueryCountTest {

	private static final int PAGE_SIZE = 5;
	private static final int QUESTIONS = 12;

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
	void 피드_한_페이지는_항목_수와_무관하게_SQL_3회_이하로_조회한다() throws Exception {
		String viewer = activeToken();
		String author = activeToken();
		for (int i = 0; i < QUESTIONS; i++) {
			createQuestion(author, "질문 " + i);
		}
		appender.list.clear();

		mockMvc.perform(get("/api/v1/questions/feed?size=" + PAGE_SIZE).header(HttpHeaders.AUTHORIZATION, "Bearer " + viewer))
				.andExpect(status().isOk());

		List<String> selects = appender.list.stream()
				.map(ILoggingEvent::getFormattedMessage)
				.filter(sql -> sql.trim().toLowerCase().startsWith("select"))
				.toList();
		// 첫 페이지: 상단 노출 단계(0건) → 일반 단계 → fetch join = 3회. 항목 5개를 따로 읽었다면 8회 이상이 된다
		assertThat(selects).as("실행된 SELECT: \n" + String.join("\n", selects)).hasSizeLessThanOrEqualTo(3);
		assertThat(selects).anyMatch(sql -> sql.contains("question_option"));
	}

	private String activeToken() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String body = "{\"email\":\"fq" + suffix + "@test.com\",\"password\":\"pass1234\",\"nickname\":\"쿼리" + suffix + "\"}";
		String response = mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		long memberId = objectMapper.readTree(response).get("member").get("id").asLong();
		jdbc.update("UPDATE member SET signup_status = 'ACTIVE' WHERE id = ?", memberId);
		return jwtTokenProvider.createAccessToken(memberId, SignupStatus.ACTIVE);
	}

	private void createQuestion(String token, String content) throws Exception {
		String body = "{\"questionType\":\"TEXT\",\"content\":\"" + content + "\",\"options\":[{\"content\":\"a\"},{\"content\":\"b\"},{\"content\":\"c\"}]}";
		mockMvc.perform(post("/api/v1/questions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
	}

}
