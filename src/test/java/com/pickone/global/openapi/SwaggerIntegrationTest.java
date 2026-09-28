package com.pickone.global.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pickone.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Swagger 를 명시적으로 켠 경우: 문서·UI 는 인증 없이 열리고, 그 밖의 API 는 그대로 보호된다 (기본값은 꺼짐) */
@IntegrationTest
@TestPropertySource(properties = "pickone.swagger.enabled=true")
class SwaggerIntegrationTest {

	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper objectMapper;

	@Test
	void api_docs는_인증_없이_200이고_모든_컨트롤러의_경로와_Bearer_스킴이_들어_있다() throws Exception {
		String body = mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.openapi").isString())
				.andExpect(jsonPath("$.info.title").value("pick-one API"))
				.andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
				.andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"))
				.andReturn().getResponse().getContentAsString();
		JsonNode paths = objectMapper.readTree(body).get("paths");

		assertThat(paths.properties().stream().map(java.util.Map.Entry::getKey)).contains(
				"/api/v1/auth/signup", "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout",
				"/api/v1/members/me", "/api/v1/phone-verifications", "/api/v1/phone-verifications/confirm",
				"/api/v1/questions", "/api/v1/questions/feed", "/api/v1/questions/{id}", "/api/v1/members/me/questions",
				"/api/v1/questions/{id}/votes", "/api/v1/questions/{id}/results",
				"/api/v1/points/balance", "/api/v1/points/ledger", "/api/v1/questions/{id}/boosts",
				"/api/v1/members/me/contacts", "/api/v1/members/me/hide-from-contacts",
				"/api/v1/members/{id}/blocks", "/api/v1/members/me/blocks", "/api/v1/questions/{id}/reports");
		// @LoginMemberId 는 토큰에서 나오므로 파라미터로 노출되지 않는다
		assertThat(paths.get("/api/v1/members/me").get("get").has("parameters")).isFalse();
		// 공개 API 는 보안 요구가 비어 있고, 보호 API 는 bearerAuth 를 요구한다
		assertThat(paths.get("/api/v1/auth/login").get("post").get("security").isEmpty()).isTrue();
		assertThat(paths.get("/api/v1/questions/feed").get("get").has("security")).isFalse(); // 전역 요구사항을 상속
		assertThat(objectMapper.readTree(body).get("security").get(0).has("bearerAuth")).isTrue();
	}

	@Test
	void swagger_ui는_인증_없이_200이다() throws Exception {
		mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
	}

	@Test
	void 문서가_열려_있어도_인증이_필요한_API는_여전히_401이다() throws Exception {
		mockMvc.perform(get("/api/v1/members/me")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));
		mockMvc.perform(get("/api/v1/questions/feed")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/points/balance")).andExpect(status().isUnauthorized());
		// swagger 와 비슷한 다른 경로는 열리지 않는다
		mockMvc.perform(get("/v3/other")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/swagger-resources")).andExpect(status().isUnauthorized());
	}

}
