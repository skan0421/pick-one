package com.pickone.support;

import com.pickone.TestcontainersConfiguration;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * 통합 테스트 공통 설정: 전체 컨텍스트 + Testcontainers MariaDB·Redis + 테스트용 비밀값(JWT·암호화 키) + MockMvc.
 * 모든 통합 테스트가 같은 설정을 쓰므로 스프링 컨텍스트가 한 번만 뜨고 캐시된다.
 * 프로필은 test 로 고정한다. application.yml 의 spring.profiles.default 가 local 이라 그대로 두면 개발자 PC 의
 * application-local.yml(git 미추적)이 테스트에 읽혀 CI 와 설정이 달라진다 (docs/troubleshooting.md 13).
 * test 프로필용 파일은 없으며, DB·Redis 는 @ServiceConnection, 비밀값은 TestSecretsConfiguration 이 준다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecretsConfiguration.class, TestSmsConfiguration.class})
public @interface IntegrationTest {
}
