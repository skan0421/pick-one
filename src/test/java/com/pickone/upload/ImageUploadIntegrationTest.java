package com.pickone.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pickone.global.security.jwt.JwtTokenProvider;
import com.pickone.global.storage.StorageProperties;
import com.pickone.member.domain.SignupStatus;
import com.pickone.support.ImageUploadTestSupport;
import com.pickone.support.ImageUploadTestSupport.Issued;
import com.pickone.support.IntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 사진 업로드 URL 발급 → 실제 PUT(Testcontainers MinIO) → 사진형 고민 등록, 그리고 읽기 정책 (docs/api.md 4.6) */
@IntegrationTest
class ImageUploadIntegrationTest {

	private static final String JPEG = "image/jpeg";
	private static final HttpClient HTTP = HttpClient.newHttpClient();

	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper objectMapper;
	@Autowired JdbcTemplate jdbc;
	@Autowired JwtTokenProvider jwtTokenProvider;
	@Autowired StorageProperties storageProperties;

	@Nested
	class 발급 {

		@Test
		void 발급하면_회원_폴더의_UUID_키로_업로드_URL과_공개_URL_만료_시각을_준다() throws Exception {
			Session me = activeMember();
			LocalDateTime before = LocalDateTime.now();

			Issued issued = ImageUploadTestSupport.issue(mockMvc, objectMapper, me.token, JPEG, 1024);

			String expectedPrefix = storageProperties.publicBaseUrl() + "/images/" + me.memberId + "/";
			assertThat(issued.imageUrl()).startsWith(expectedPrefix).endsWith(".jpg");
			String file = issued.imageUrl().substring(expectedPrefix.length());
			assertThat(UUID.fromString(file.substring(0, 36))).isNotNull();
			assertThat(issued.uploadUrl()).contains("/images/" + me.memberId + "/" + file).contains("X-Amz-Signature=");
			LocalDateTime expiresAt = LocalDateTime.parse(issued.expiresAt());
			assertThat(expiresAt).isBetween(before.plusMinutes(4), LocalDateTime.now().plusMinutes(6));
		}

		@Test
		void 허용되지_않은_형식은_400_IMAGE_TYPE_NOT_ALLOWED() throws Exception {
			issueRaw(activeMember(), "{\"contentType\":\"image/gif\",\"size\":100}")
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("IMAGE_TYPE_NOT_ALLOWED"));
			issueRaw(activeMember(), "{\"contentType\":\"text/html\",\"size\":100}")
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("IMAGE_TYPE_NOT_ALLOWED"));
		}

		@Test
		void 크기_상한을_넘으면_400_IMAGE_TOO_LARGE_이고_0이하면_VALIDATION_ERROR() throws Exception {
			long sixMb = 6L * 1024 * 1024;
			issueRaw(activeMember(), "{\"contentType\":\"image/png\",\"size\":" + sixMb + "}")
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("IMAGE_TOO_LARGE"));
			issueRaw(activeMember(), "{\"contentType\":\"image/png\",\"size\":0}")
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
			issueRaw(activeMember(), "{\"contentType\":\"image/png\"}")
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		}

		@Test
		void 토큰이_없으면_401이다() throws Exception {
			mockMvc.perform(post("/api/v1/uploads/images").contentType(MediaType.APPLICATION_JSON)
					.content("{\"contentType\":\"image/png\",\"size\":10}"))
					.andExpect(status().isUnauthorized());
		}

	}

	@Nested
	class 업로드와_고민_등록 {

		@Test
		void 발급받은_URL로_PUT_하고_그_imageUrl_두_개로_사진형_고민을_등록한다() throws Exception {
			Session me = activeMember();
			byte[] a = ImageUploadTestSupport.fakeImage(2048);
			byte[] b = ImageUploadTestSupport.fakeImage(4096);
			String urlA = ImageUploadTestSupport.uploadImage(mockMvc, objectMapper, me.token, JPEG, a);
			String urlB = ImageUploadTestSupport.uploadImage(mockMvc, objectMapper, me.token, "image/webp", b);

			JsonNode created = json(createImageQuestion(me, urlA, urlB)
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.questionType").value("IMAGE"))
					.andExpect(jsonPath("$.options[0].imageUrl").value(urlA))
					.andExpect(jsonPath("$.options[1].imageUrl").value(urlB)));

			// 앱은 저장된 imageUrl 로 익명 GET 해 그대로 표시한다 (images/* 공개 읽기)
			HttpResponse<byte[]> read = HTTP.send(HttpRequest.newBuilder(URI.create(urlA)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
			assertThat(read.statusCode()).isEqualTo(200);
			assertThat(read.body()).isEqualTo(a);
			assertThat(read.headers().firstValue("Content-Type")).contains(JPEG);
			mockMvc.perform(get("/api/v1/questions/" + created.get("id").asLong()).header(HttpHeaders.AUTHORIZATION, "Bearer " + me.token))
					.andExpect(jsonPath("$.options[0].imageUrl").value(urlA));
		}

		@Test
		void 선언한_형식이나_크기와_다르게_PUT_하면_저장소가_거절한다() throws Exception {
			Session me = activeMember();
			byte[] bytes = ImageUploadTestSupport.fakeImage(1000);
			Issued issued = ImageUploadTestSupport.issue(mockMvc, objectMapper, me.token, JPEG, 1000);

			assertThat(ImageUploadTestSupport.put(issued.uploadUrl(), "image/png", bytes)).as("다른 Content-Type").isEqualTo(403);
			assertThat(ImageUploadTestSupport.put(issued.uploadUrl(), JPEG, ImageUploadTestSupport.fakeImage(1500))).as("다른 크기").isEqualTo(403);
			assertThat(ImageUploadTestSupport.put(issued.uploadUrl(), JPEG, bytes)).as("선언대로").isEqualTo(200);
		}

		@Test
		void 남의_이미지_외부_URL_업로드_안_한_키는_400_IMAGE_URL_INVALID() throws Exception {
			Session me = activeMember();
			Session other = activeMember();
			String mine = ImageUploadTestSupport.uploadImage(mockMvc, objectMapper, me.token, JPEG, ImageUploadTestSupport.fakeImage(100));
			String others = ImageUploadTestSupport.uploadImage(mockMvc, objectMapper, other.token, JPEG, ImageUploadTestSupport.fakeImage(100));
			String notUploaded = ImageUploadTestSupport.issue(mockMvc, objectMapper, me.token, JPEG, 100).imageUrl();

			for (String bad : new String[] {others, "https://cdn.example.com/a.jpg", notUploaded, mine + "?x=1",
					mine.replace("/images/", "/other/")}) {
				createImageQuestion(me, mine, bad)
						.andExpect(status().isBadRequest())
						.andExpect(jsonPath("$.code").value("IMAGE_URL_INVALID"));
			}
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question WHERE member_id = ?", Long.class, me.memberId)).isZero();
		}

	}

	@Nested
	class 읽기_정책 {

		@Test
		void 버킷_목록_조회와_images_밖의_객체는_익명으로_읽을_수_없다() throws Exception {
			String bucketUrl = storageProperties.endpoint() + "/" + storageProperties.bucket();
			try (S3Client s3 = s3Client()) {
				s3.putObject(PutObjectRequest.builder().bucket(storageProperties.bucket()).key("private/secret.txt").build(),
						RequestBody.fromString("secret"));
			}

			assertThat(anonymousGet(bucketUrl + "?list-type=2")).as("ListBucket").isEqualTo(403);
			assertThat(anonymousGet(bucketUrl + "/private/secret.txt")).as("images/ 밖 객체").isEqualTo(403);
			assertThat(anonymousGet(bucketUrl + "/images/1/" + UUID.randomUUID() + ".jpg")).as("없는 이미지").isEqualTo(404);
		}

	}

	// =============================== 헬퍼 ===============================

	private record Session(long memberId, String token) {
	}

	private Session activeMember() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String body = "{\"email\":\"up" + suffix + "@test.com\",\"password\":\"pass1234\",\"nickname\":\"업로드" + suffix + "\"}";
		JsonNode json = json(mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated()));
		long memberId = json.get("member").get("id").asLong();
		jdbc.update("UPDATE member SET signup_status = 'ACTIVE' WHERE id = ?", memberId);
		return new Session(memberId, jwtTokenProvider.createAccessToken(memberId, SignupStatus.ACTIVE));
	}

	private ResultActions issueRaw(Session s, String body) throws Exception {
		return mockMvc.perform(post("/api/v1/uploads/images")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + s.token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private ResultActions createImageQuestion(Session s, String urlA, String urlB) throws Exception {
		return mockMvc.perform(post("/api/v1/questions")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + s.token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"questionType\":\"IMAGE\",\"content\":\"뭐 입지\",\"options\":[{\"imageUrl\":\"" + urlA + "\"},{\"imageUrl\":\"" + urlB + "\"}]}"));
	}

	private static int anonymousGet(String url) throws Exception {
		return HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
	}

	private S3Client s3Client() {
		return S3Client.builder()
				.endpointOverride(URI.create(storageProperties.endpoint()))
				.region(Region.of(storageProperties.region()))
				.credentialsProvider(StaticCredentialsProvider.create(
						AwsBasicCredentials.create(storageProperties.accessKey(), storageProperties.secretKey())))
				.forcePathStyle(true)
				.build();
	}

	private JsonNode json(ResultActions actions) throws Exception {
		return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
	}

}
