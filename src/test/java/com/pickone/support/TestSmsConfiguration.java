package com.pickone.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** 통합 테스트에서는 LoggingSmsSender 대신 기록용 발송기를 주입한다 */
@TestConfiguration(proxyBeanMethods = false)
public class TestSmsConfiguration {

	@Bean
	@Primary
	RecordingSmsSender recordingSmsSender() {
		return new RecordingSmsSender();
	}

}
