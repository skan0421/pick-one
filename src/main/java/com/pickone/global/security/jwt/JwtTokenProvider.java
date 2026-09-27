package com.pickone.global.security.jwt;

import com.pickone.member.domain.SignupStatus;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/** Access 토큰 발급. 클레임: iss, sub(memberId), iat, exp, signupStatus */
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

	public static final String ISSUER = "pickone";

	private final JwtEncoder jwtEncoder;
	private final JwtProperties properties;

	public String createAccessToken(Long memberId, SignupStatus signupStatus) {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(ISSUER)
				.issuedAt(now)
				.expiresAt(now.plus(properties.accessTokenTtl()))
				.subject(String.valueOf(memberId))
				.claim(JwtConfig.SIGNUP_STATUS_CLAIM, signupStatus.name())
				.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}

}
