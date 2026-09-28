package com.pickone.global.openapi;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pickone.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** 운영처럼 pickone.swagger.enabled=false 로 끈 경우: 문서·UI 경로가 열리지 않는다 (별도 컨텍스트) */
@IntegrationTest
@TestPropertySource(properties = "pickone.swagger.enabled=false")
class SwaggerDisabledIntegrationTest {

	@Autowired MockMvc mockMvc;

	@Test
	void 꺼져_있으면_api_docs와_swagger_ui는_인증_없이_접근할_수_없다() throws Exception {
		mockMvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
	}

	@Test
	void 꺼져_있어도_일반_API_인증_규칙은_그대로다() throws Exception {
		mockMvc.perform(get("/api/v1/members/me")).andExpect(status().isUnauthorized());
	}

}
