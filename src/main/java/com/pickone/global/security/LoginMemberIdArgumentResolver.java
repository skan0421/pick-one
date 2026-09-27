package com.pickone.global.security;

import com.pickone.global.error.BusinessException;
import com.pickone.global.error.ErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** @LoginMemberId Long 파라미터에 JWT 의 sub(memberId) 를 넣어 준다 */
@Component
public class LoginMemberIdArgumentResolver implements HandlerMethodArgumentResolver {

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.hasParameterAnnotation(LoginMemberId.class)
				&& Long.class.equals(parameter.getParameterType());
	}

	@Override
	public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
			return Long.valueOf(jwtAuthentication.getToken().getSubject());
		}
		// 인증이 필요한 경로는 Security 가 먼저 막으므로 여기 오는 일은 설정 오류에 가깝다
		throw new BusinessException(ErrorCode.AUTH_INVALID_TOKEN);
	}

}
