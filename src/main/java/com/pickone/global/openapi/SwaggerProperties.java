package com.pickone.global.openapi;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * pickone.swagger.* 설정. enabled 가 false 면 springdoc(api-docs, swagger-ui)이 꺼지고 SecurityConfig 도 해당 경로를 열지 않는다.
 * local 기본 true, 운영은 SWAGGER_ENABLED=false 로 끈다.
 */
@ConfigurationProperties(prefix = "pickone.swagger")
public record SwaggerProperties(Boolean enabled) {

	public SwaggerProperties {
		if (enabled == null) enabled = true;
	}

	public boolean isEnabled() {
		return enabled;
	}

}
