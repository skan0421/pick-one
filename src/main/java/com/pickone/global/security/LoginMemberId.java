package com.pickone.global.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 컨트롤러 파라미터에 붙이면 현재 로그인한 회원의 ID(Long) 가 주입된다.
 * 토큰의 sub 클레임에서 읽는다 (LoginMemberIdArgumentResolver).
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface LoginMemberId {
}
