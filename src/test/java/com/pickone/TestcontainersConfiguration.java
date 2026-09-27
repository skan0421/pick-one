package com.pickone;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 통합 테스트용 MariaDB 컨테이너 설정.
 * docker-compose.yml 과 같은 버전을 사용해 로컬/테스트 환경 차이를 줄인다.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	MariaDBContainer mariaDbContainer() {
		return new MariaDBContainer(DockerImageName.parse("mariadb:11.4"));
	}

}
