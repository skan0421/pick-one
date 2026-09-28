package com.pickone.global.openapi;

import com.pickone.global.security.LoginMemberId;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 문서 설정 (springdoc). pickone.swagger.enabled=true 일 때만 등록된다.
 * - JWT Bearer 스킴을 등록해 Swagger UI 의 Authorize 버튼으로 access 토큰을 넣을 수 있다. 공개 API 는 @SecurityRequirements 로 예외 처리
 * - @LoginMemberId 파라미터는 토큰에서 나오므로 문서의 파라미터 목록에서 뺀다
 * 접속: /swagger-ui/index.html, 원본 JSON: /v3/api-docs
 */
@Configuration
@ConditionalOnProperty(name = "pickone.swagger.enabled", havingValue = "true", matchIfMissing = true)
public class OpenApiConfig {

	public static final String BEARER_SCHEME = "bearerAuth";

	static {
		SpringDocUtils.getConfig().addAnnotationsToIgnore(LoginMemberId.class);
	}

	@Bean
	OpenAPI pickoneOpenApi() {
		return new OpenAPI()
				.info(new Info()
						.title("pick-one API")
						.version("v1")
						.description("둘 중 하나 골라주는 익명 투표 서비스 MVP. 상세 규칙(에러 코드, 커서, Idempotency-Key, 포인트 정책)은 docs/api.md 를 따른다."))
				.components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
						.type(SecurityScheme.Type.HTTP)
						.scheme("bearer")
						.bearerFormat("JWT")
						.description("Authorization: Bearer <accessToken>. 가입/로그인 응답의 accessToken 을 넣는다")))
				.addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
	}

}
