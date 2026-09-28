package com.pickone.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 발급 API → 실제 PUT 까지 한 번에 하는 테스트 헬퍼. 사진형 고민을 만드는 테스트가 공용으로 쓴다 */
public final class ImageUploadTestSupport {

	private static final HttpClient HTTP = HttpClient.newHttpClient();

	private ImageUploadTestSupport() {
	}

	public record Issued(String uploadUrl, String imageUrl, String expiresAt) {
	}

	/** POST /api/v1/uploads/images 만 호출 (업로드는 하지 않음) */
	public static Issued issue(MockMvc mockMvc, ObjectMapper objectMapper, String accessToken, String contentType, long size) throws Exception {
		String body = mockMvc.perform(post("/api/v1/uploads/images")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"contentType\":\"" + contentType + "\",\"size\":" + size + "}"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		JsonNode json = objectMapper.readTree(body);
		return new Issued(json.get("uploadUrl").asString(), json.get("imageUrl").asString(), json.get("expiresAt").asString());
	}

	/** 발급받은 uploadUrl 로 PUT. 발급 시 선언한 Content-Type·크기와 같아야 한다 */
	public static int put(String uploadUrl, String contentType, byte[] bytes) throws Exception {
		HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(uploadUrl))
				.header("Content-Type", contentType)
				.PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
				.build(), HttpResponse.BodyHandlers.ofString());
		return response.statusCode();
	}

	/** 발급 + 업로드 성공까지. 고민 등록에 쓸 imageUrl 을 돌려준다 */
	public static String uploadImage(MockMvc mockMvc, ObjectMapper objectMapper, String accessToken, String contentType, byte[] bytes) throws Exception {
		Issued issued = issue(mockMvc, objectMapper, accessToken, contentType, bytes.length);
		assertThat(put(issued.uploadUrl(), contentType, bytes)).as("presigned PUT").isEqualTo(200);
		return issued.imageUrl();
	}

	public static byte[] fakeImage(int size) {
		byte[] bytes = new byte[size];
		for (int i = 0; i < size; i++) {
			bytes[i] = (byte) (i * 31 + 7);
		}
		return bytes;
	}

}
