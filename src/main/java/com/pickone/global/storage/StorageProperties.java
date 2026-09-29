package com.pickone.global.storage;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * pickone.storage.* — S3 호환 객체 저장소 설정 (docs/api.md 4.6).
 * endpoint/bucket/access-key/secret-key 는 환경변수 또는 application-local.yml 로만 주입한다 (기본값 없음).
 * public-base-url 은 앱이 이미지를 읽는 주소의 접두사(CDN 이 있으면 CDN). 비어 있으면 "{endpoint}/{bucket}".
 */
@ConfigurationProperties(prefix = "pickone.storage")
public record StorageProperties(
		String endpoint,
		String publicBaseUrl,
		String region,
		String bucket,
		String accessKey,
		String secretKey,
		/** 이미지 1장 최대 크기 */
		DataSize maxImageSize,
		/** presigned PUT URL 유효 시간 */
		Duration presignTtl,
		/** 허용 Content-Type */
		List<String> allowedContentTypes
) {

	public StorageProperties {
		require(endpoint, "pickone.storage.endpoint", "STORAGE_ENDPOINT");
		require(bucket, "pickone.storage.bucket", "STORAGE_BUCKET");
		require(accessKey, "pickone.storage.access-key", "STORAGE_ACCESS_KEY");
		require(secretKey, "pickone.storage.secret-key", "STORAGE_SECRET_KEY");
		endpoint = stripTrailingSlash(endpoint);
		if (region == null || region.isBlank()) region = "ap-northeast-2";
		if (maxImageSize == null) maxImageSize = DataSize.ofMegabytes(5);
		if (presignTtl == null) presignTtl = Duration.ofMinutes(5);
		if (allowedContentTypes == null || allowedContentTypes.isEmpty()) {
			allowedContentTypes = List.of("image/jpeg", "image/png", "image/webp");
		}
		publicBaseUrl = publicBaseUrl == null || publicBaseUrl.isBlank()
				? endpoint + "/" + bucket
				: stripTrailingSlash(publicBaseUrl);
	}

	private static void require(String value, String property, String envVar) {
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(property + " 가 비어 있습니다. " + envVar + " 환경변수 또는 application-local.yml 을 확인하세요.");
		}
	}

	private static String stripTrailingSlash(String url) {
		String trimmed = url.trim();
		return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
	}

}
