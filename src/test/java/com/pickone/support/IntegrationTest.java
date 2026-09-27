package com.pickone.support;

import com.pickone.TestcontainersConfiguration;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/**
 * 통합 테스트 공통 설정: 전체 컨텍스트 + Testcontainers MariaDB + 테스트용 비밀값(JWT·암호화 키) + MockMvc.
 * 모든 통합 테스트가 같은 설정을 쓰므로 스프링 컨텍스트가 한 번만 뜨고 캐시된다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestSecretsConfiguration.class})
public @interface IntegrationTest {
}
