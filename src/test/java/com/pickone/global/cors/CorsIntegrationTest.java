package com.pickone.global.cors;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pickone.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/** CORS (docs/api.md 1.9): /api/** 에만, 설정된 origin 만, credentials 없음 */
@IntegrationTest
class CorsIntegrationTest {

	private static final String ALLOWED = "http://localhost:8081";
	private static final String DENIED = "https://evil.example.com";

	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper objectMapper;

	@Test
	void 허용_origin의_preflight는_토큰_없이_200이고_허용_헤더와_메서드를_돌려준다() throws Exception {
		mockMvc.perform(options("/api/v1/questions/feed")
				.header(HttpHeaders.ORIGIN, ALLOWED)
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization, Content-Type"))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST,PUT,PATCH,DELETE,OPTIONS"))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "Authorization, Content-Type"))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600"))
				.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
	}

	@Test
	void 허용되지_않은_origin의_preflight는_403이다() throws Exception {
		mockMvc.perform(options("/api/v1/questions/feed")
				.header(HttpHeaders.ORIGIN, DENIED)
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
	}

	@Test
	void 허용_origin의_실제_요청_응답에는_Allow_Origin이_붙고_인증_규칙은_그대로다() throws Exception {
		// 토큰 없이 보호 API → 401 이지만 CORS 헤더는 붙는다 (브라우저가 에러 본문을 읽을 수 있게)
		mockMvc.perform(get("/api/v1/members/me").header(HttpHeaders.ORIGIN, ALLOWED))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED));

		// 공개 API 도 같은 origin 이면 허용 헤더가 붙는다
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		mockMvc.perform(post("/api/v1/auth/signup").header(HttpHeaders.ORIGIN, ALLOWED)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"cors" + suffix + "@test.com\",\"password\":\"pass1234\",\"nickname\":\"코스" + suffix + "\"}"))
				.andExpect(status().isCreated())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED))
				.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
	}

	@Test
	void 허용되지_않은_origin의_실제_요청은_403이고_api_밖_경로에는_CORS가_적용되지_않는다() throws Exception {
		mockMvc.perform(get("/api/v1/members/me").header(HttpHeaders.ORIGIN, DENIED))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));

		// /api/** 밖에는 CORS 설정 자체가 없다: preflight 에 허용 헤더가 붙지 않고(브라우저가 거부), 실제 요청도 인증 규칙대로 401
		mockMvc.perform(options("/v3/api-docs")
				.header(HttpHeaders.ORIGIN, ALLOWED)
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
				.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
		mockMvc.perform(get("/v3/api-docs").header(HttpHeaders.ORIGIN, ALLOWED))
				.andExpect(status().isUnauthorized())
				.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
	}

}
