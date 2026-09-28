package com.pickone.global.cors;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * pickone.cors.* (docs/api.md 1.9). 허용 origin 목록. 기본은 Expo 웹 개발 서버.
 * 쿠키를 쓰지 않으므로 allowCredentials 는 항상 false 이고, origin 은 정확한 문자열로만 비교한다 (패턴 없음).
 */
@ConfigurationProperties(prefix = "pickone.cors")
public record CorsProperties(List<String> allowedOrigins) {

	public CorsProperties {
		if (allowedOrigins == null || allowedOrigins.isEmpty()) {
			allowedOrigins = List.of("http://localhost:8081");
		}
		allowedOrigins = allowedOrigins.stream().map(String::trim).filter(o -> !o.isEmpty()).toList();
	}

}
