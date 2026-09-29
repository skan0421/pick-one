package com.pickone.global.storage;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * S3 호환 저장소 구현 (AWS SDK v2). 로컬 MinIO 와 운영 S3 는 endpoint·자격증명 설정만 다르다.
 * - path-style(`{endpoint}/{bucket}/{key}`): MinIO 는 virtual-host 스타일을 기본으로 지원하지 않는다
 * - 체크섬은 WHEN_REQUIRED: SDK 2.30+ 기본값(WHEN_SUPPORTED)은 PUT 에 CRC 체크섬 헤더를 서명에 넣어
 *   브라우저·앱이 presigned URL 로 직접 올릴 때 헤더를 맞춰야 하고, 일부 S3 호환 저장소와도 어긋난다
 * - presigner 는 s3Client 를 넘겨 같은 endpoint·자격증명·체크섬 설정을 쓴다
 */
@Slf4j
@Component
@EnableConfigurationProperties(StorageProperties.class)
public class S3ImageStorage implements ImageStorage {

	private final StorageProperties properties;
	private final S3Client s3Client;
	private final S3Presigner presigner;

	public S3ImageStorage(StorageProperties properties) {
		this.properties = properties;
		StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
				AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
		S3Configuration pathStyle = S3Configuration.builder().pathStyleAccessEnabled(true).build();
		this.s3Client = S3Client.builder()
				.endpointOverride(URI.create(properties.endpoint()))
				.region(Region.of(properties.region()))
				.credentialsProvider(credentials)
				.forcePathStyle(true) // S3Configuration 과 동시에 설정하면 SDK 가 거부한다
				.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
				.responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
				.build();
		this.presigner = S3Presigner.builder()
				.s3Client(s3Client)
				.endpointOverride(URI.create(properties.endpoint()))
				.region(Region.of(properties.region()))
				.credentialsProvider(credentials)
				.serviceConfiguration(pathStyle)
				.build();
	}

	@Override
	public PresignedUpload presignPut(String objectKey, String contentType, long contentLength, Duration ttl) {
		PutObjectRequest put = PutObjectRequest.builder()
				.bucket(properties.bucket())
				.key(objectKey)
				.contentType(contentType)
				.contentLength(contentLength)
				.build();
		PresignedPutObjectRequest presigned = presigner.presignPutObject(PutObjectPresignRequest.builder()
				.signatureDuration(ttl)
				.putObjectRequest(put)
				.build());
		LocalDateTime expiresAt = LocalDateTime.ofInstant(presigned.expiration(), ZoneId.systemDefault())
				.truncatedTo(ChronoUnit.MICROS);
		return new PresignedUpload(presigned.url().toString(), expiresAt);
	}

	@Override
	public String publicUrl(String objectKey) {
		return properties.publicBaseUrl() + "/" + objectKey;
	}

	@Override
	public boolean exists(String objectKey) {
		try {
			s3Client.headObject(HeadObjectRequest.builder().bucket(properties.bucket()).key(objectKey).build());
			return true;
		}
		catch (NoSuchKeyException e) {
			return false;
		}
	}

}
