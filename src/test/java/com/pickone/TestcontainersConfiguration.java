package com.pickone;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
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

	/** 이미지 이름이 redis 이면 Spring Boot 가 spring.data.redis.* 접속 정보를 자동으로 연결한다 */
	@Bean
	@ServiceConnection(name = "redis")
	GenericContainer<?> redisContainer() {
		return new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
	}

}
