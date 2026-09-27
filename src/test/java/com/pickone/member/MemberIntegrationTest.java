package com.pickone.member;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pickone.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/** 내 정보 조회·닉네임 변경 통합 테스트. 인증 실패 케이스는 SecurityIntegrationTest 에서 다룬다 */
@IntegrationTest
class MemberIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ObjectMapper objectMapper;

	@Test
	void 토큰_없이_내_정보를_조회하면_401_AUTH_INVALID_TOKEN() throws Exception {
		mockMvc.perform(get("/api/v1/members/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTH_INVALID_TOKEN"));
	}

	@Test
	void PENDING_PHONE_회원도_내_정보를_조회할_수_있다() throws Exception {
		String email = uniqueEmail();
		String nickname = uniqueNickname();
		String token = signupAndGetToken(email, nickname);

		mockMvc.perform(get("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value(email))
				.andExpect(jsonPath("$.nickname").value(nickname))
				.andExpect(jsonPath("$.provider").value("EMAIL"))
				.andExpect(jsonPath("$.signupStatus").value("PENDING_PHONE"))
				.andExpect(jsonPath("$.phoneVerified").value(false))
				.andExpect(jsonPath("$.hideFromContacts").value(false))
				.andExpect(jsonPath("$.pointBalance").value(0))
				.andExpect(jsonPath("$.createdAt").isString());
	}

	@Test
	void 닉네임을_변경하면_200과_바뀐_정보를_받는다() throws Exception {
		String token = signupAndGetToken(uniqueEmail(), uniqueNickname());
		String newNickname = uniqueNickname();

		mockMvc.perform(patch("/api/v1/members/me")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"nickname\":\"" + newNickname + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.nickname").value(newNickname));

		mockMvc.perform(get("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(jsonPath("$.nickname").value(newNickname));
	}

	@Test
	void 다른_회원이_쓰는_닉네임으로_바꾸면_409_MEMBER_NICKNAME_DUPLICATE() throws Exception {
		String takenNickname = uniqueNickname();
		signupAndGetToken(uniqueEmail(), takenNickname);
		String token = signupAndGetToken(uniqueEmail(), uniqueNickname());

		mockMvc.perform(patch("/api/v1/members/me")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"nickname\":\"" + takenNickname + "\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("MEMBER_NICKNAME_DUPLICATE"));
	}

	@Test
	void 닉네임_형식이_잘못되면_400_VALIDATION_ERROR() throws Exception {
		String token = signupAndGetToken(uniqueEmail(), uniqueNickname());

		mockMvc.perform(patch("/api/v1/members/me")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"nickname\":\"a\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errors[0].field").value("nickname"));
	}

	private String signupAndGetToken(String email, String nickname) throws Exception {
		String body = "{\"email\":\"" + email + "\",\"password\":\"pass1234\",\"nickname\":\"" + nickname + "\"}";
		String response = mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(response).get("accessToken").asString();
	}

	private static String uniqueEmail() {
		return "m" + UUID.randomUUID().toString().substring(0, 8) + "@test.com";
	}

	private static String uniqueNickname() {
		return "멤" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");
	}

}
