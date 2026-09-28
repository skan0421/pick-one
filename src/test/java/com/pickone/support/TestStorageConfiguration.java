package com.pickone.support;

import com.pickone.global.storage.StorageProperties;
import java.net.URI;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutBucketPolicyRequest;

/**
 * 테스트 MinIO 에 버킷을 만들고 docker/minio/public-read-images.json 과 같은 정책(images/* 만 익명 GetObject)을 건다.
 * 로컬 compose 의 minio-init 컨테이너가 하는 일을 테스트 컨텍스트 기동 시 대신한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestStorageConfiguration {

	public static final String POLICY = """
			{"Version":"2012-10-17","Statement":[{"Sid":"PublicReadImagesPrefixOnly","Effect":"Allow","Principal":{"AWS":["*"]},
			"Action":["s3:GetObject"],"Resource":["arn:aws:s3:::%s/images/*"]}]}""";

	@Bean
	InitializingBean testBucketInitializer(StorageProperties properties) {
		return () -> {
			try (S3Client s3 = S3Client.builder()
					.endpointOverride(URI.create(properties.endpoint()))
					.region(Region.of(properties.region()))
					.credentialsProvider(StaticCredentialsProvider.create(
							AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
					.forcePathStyle(true)
					.build()) {
				try {
					s3.createBucket(CreateBucketRequest.builder().bucket(properties.bucket()).build());
				}
				catch (BucketAlreadyOwnedByYouException ignored) {
					// 컨텍스트가 다시 뜬 경우
				}
				s3.putBucketPolicy(PutBucketPolicyRequest.builder()
						.bucket(properties.bucket())
						.policy(POLICY.formatted(properties.bucket()))
						.build());
			}
		};
	}

}
