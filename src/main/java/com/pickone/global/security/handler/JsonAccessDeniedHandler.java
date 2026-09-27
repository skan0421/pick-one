package com.pickone.global.security.handler;

import com.pickone.global.error.ErrorCode;
import com.pickone.global.error.ErrorResponse;
import com.pickone.global.security.jwt.JwtConfig;
import com.pickone.member.domain.SignupStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 인가 실패(403) 를 { code, message } JSON 으로 응답한다.
 * 휴대폰 인증 전(PENDING_PHONE) 회원이 ACTIVE 전용 API 를 호출하면 SIGNUP_INCOMPLETE, 그 외는 FORBIDDEN.
 */
@Component
@RequiredArgsConstructor
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

	private static final String PENDING_AUTHORITY = JwtConfig.SIGNUP_AUTHORITY_PREFIX + SignupStatus.PENDING_PHONE.name();

	private final ObjectMapper objectMapper;

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException accessDeniedException) throws IOException {
		ErrorCode code = isPendingMember() ? ErrorCode.SIGNUP_INCOMPLETE : ErrorCode.FORBIDDEN;
		response.setStatus(code.getStatus().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		objectMapper.writeValue(response.getWriter(), ErrorResponse.of(code));
	}

	private boolean isPendingMember() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null) {
			return false;
		}
		return authentication.getAuthorities().stream()
				.map(GrantedAuthority::getAuthority)
				.anyMatch(PENDING_AUTHORITY::equals);
	}

}
