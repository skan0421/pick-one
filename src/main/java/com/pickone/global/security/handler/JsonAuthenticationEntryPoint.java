package com.pickone.global.security.handler;

import com.pickone.global.error.ErrorCode;
import com.pickone.global.error.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 인증 실패(401) 를 { code, message } JSON 으로 응답한다.
 * 토큰 만료는 AUTH_EXPIRED_TOKEN, 토큰 없음·위조·형식 오류는 AUTH_INVALID_TOKEN.
 */
@Component
@RequiredArgsConstructor
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private final ObjectMapper objectMapper;

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {
		ErrorCode code = resolve(authException);
		response.setStatus(code.getStatus().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		objectMapper.writeValue(response.getWriter(), ErrorResponse.of(code));
	}

	private ErrorCode resolve(AuthenticationException e) {
		// 리소스 서버는 디코딩 실패를 InvalidBearerTokenException(OAuth2AuthenticationException) 으로 감싸고,
		// 만료는 설명에 "Jwt expired at ..." 이 들어간다
		if (e instanceof OAuth2AuthenticationException oauth) {
			String description = oauth.getError().getDescription();
			if (description != null && description.toLowerCase().contains("expired")) {
				return ErrorCode.AUTH_EXPIRED_TOKEN;
			}
		}
		return ErrorCode.AUTH_INVALID_TOKEN;
	}

}
