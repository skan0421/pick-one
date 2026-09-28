package com.pickone;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 통합 테스트용 컨테이너 설정.
 * docker-compose.yml 과 같은 이미지 버전을 사용해 로컬/테스트 환경 차이를 줄인다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	MariaDBContainer mariaDbContainer() {
		return new MariaDBContainer(DockerImageName.parse("mariadb:11.4"));
	}

	private static final String MINIO_USER = "pickone-test";
	private static final String MINIO_PASSWORD = "pickone-test-secret";

	/**
	 * S3 호환 저장소. docker-compose 와 같은 Bitnami legacy MinIO 이미지 (공식 minio/minio 는 더 이상 받아지지 않음, troubleshooting.md 14).
	 * Testcontainers 의 MinIOContainer 는 공식 이미지의 실행 명령을 전제하므로 GenericContainer 로 띄운다.
	 * @ServiceConnection 이 없어 접속 정보는 pickone.storage.* 로 직접 등록하고, 버킷·정책은 TestStorageConfiguration 이 만든다.
	 */
	@Bean
	GenericContainer<?> minioContainer() {
		return new GenericContainer<>(DockerImageName.parse("bitnamilegacy/minio:2025.7.23-debian-12-r5"))
				.withEnv("MINIO_ROOT_USER", MINIO_USER)
				.withEnv("MINIO_ROOT_PASSWORD", MINIO_PASSWORD)
				.withExposedPorts(9000)
				.waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));
	}

	@Bean
	DynamicPropertyRegistrar storageProperties(@Qualifier("minioContainer") GenericContainer<?> minio) {
		return registry -> {
			registry.add("pickone.storage.endpoint", () -> "http://" + minio.getHost() + ":" + minio.getMappedPort(9000));
			registry.add("pickone.storage.bucket", () -> "pickone-images-test");
			registry.add("pickone.storage.access-key", () -> MINIO_USER);
			registry.add("pickone.storage.secret-key", () -> MINIO_PASSWORD);
		};
	}

	/** 이미지 이름이 redis 이면 Spring Boot 가 spring.data.redis.* 접속 정보를 자동으로 연결한다 */
	@Bean
	@ServiceConnection(name = "redis")
	GenericContainer<?> redisContainer() {
		return new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
	}

}
