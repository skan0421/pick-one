package com.pickone.global.openapi;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pickone.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 아무 설정도 하지 않고 뜬 컨텍스트(운영 기본값): 문서·UI 경로가 열리지 않는다.
 * 테스트는 기본 프로필이 local 이라 개발자 PC 의 application-local.yml(swagger 켜짐)을 읽을 수 있으므로,
 * 존재하지 않는 test 프로필을 활성화해 로컬 파일 없이 application.yml 기본값(${SWAGGER_ENABLED:false})만으로 띄운다.
 */
@IntegrationTest
@TestPropertySource(properties = "spring.profiles.active=test")
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
