package com.pickone.global.security.jwt;

import com.pickone.global.error.BusinessException;
import com.pickone.global.error.ErrorCode;
import com.pickone.member.domain.SignupStatus;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * 토큰 발급·해석.
 * - access: iss, sub(memberId), iat, exp(30분), typ=access, signupStatus
 * - refresh: iss, sub(memberId), iat, exp(14일), typ=refresh, jti(UUID)
 */
@Component
public class JwtTokenProvider {

	public static final String ISSUER = "pickone";

	private final JwtEncoder jwtEncoder;
	private final JwtDecoder refreshTokenDecoder;
	private final JwtProperties properties;

	public JwtTokenProvider(JwtEncoder jwtEncoder,
			@Qualifier("refreshTokenDecoder") JwtDecoder refreshTokenDecoder,
			JwtProperties properties) {
		this.jwtEncoder = jwtEncoder;
		this.refreshTokenDecoder = refreshTokenDecoder;
		this.properties = properties;
	}

	public String createAccessToken(Long memberId, SignupStatus signupStatus) {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(ISSUER)
				.issuedAt(now)
				.expiresAt(now.plus(properties.accessTokenTtl()))
				.subject(String.valueOf(memberId))
				.claim(JwtConfig.TOKEN_TYPE_CLAIM, JwtConfig.TOKEN_TYPE_ACCESS)
				.claim(JwtConfig.SIGNUP_STATUS_CLAIM, signupStatus.name())
				.build();
		return encode(claims);
	}

	/** refresh 토큰의 클레임을 먼저 정한다. Redis 저장과 JWT 인코딩이 같은 jti/exp 를 쓰게 하기 위해서다 */
	public RefreshTokenClaims newRefreshTokenClaims(Long memberId) {
		Instant now = Instant.now();
		return new RefreshTokenClaims(memberId, UUID.randomUUID().toString(), now, now.plus(properties.refreshTokenTtl()));
	}

	public String createRefreshToken(RefreshTokenClaims refresh) {
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(ISSUER)
				.issuedAt(refresh.issuedAt())
				.expiresAt(refresh.expiresAt())
				.subject(String.valueOf(refresh.memberId()))
				.id(refresh.jti())
				.claim(JwtConfig.TOKEN_TYPE_CLAIM, JwtConfig.TOKEN_TYPE_REFRESH)
				.build();
		return encode(claims);
	}

	/** 서명·만료·typ 검증. 실패하면 모두 AUTH_INVALID_TOKEN (만료된 refresh 는 Redis 키도 이미 없으므로 구분하지 않는다) */
	public RefreshTokenClaims parseRefreshToken(String token) {
		try {
			Jwt jwt = refreshTokenDecoder.decode(token);
			return new RefreshTokenClaims(Long.valueOf(jwt.getSubject()), jwt.getId(), jwt.getIssuedAt(), jwt.getExpiresAt());
		}
		catch (JwtException | NumberFormatException | NullPointerException e) {
			throw new BusinessException(ErrorCode.AUTH_INVALID_TOKEN);
		}
	}

	private String encode(JwtClaimsSet claims) {
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}

	public record RefreshTokenClaims(Long memberId, String jti, Instant issuedAt, Instant expiresAt) {
	}

}
