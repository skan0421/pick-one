package com.pickone.phone;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** pickone.otp.* 설정. 기본값은 docs/api.md 3.1 의 보안 규칙 */
@ConfigurationProperties(prefix = "pickone.otp")
public record OtpProperties(
		Integer codeLength,
		Duration ttl,
		Integer maxAttempts,
		Duration cooldown,
		Integer dailyLimitPerPhone,
		Integer dailyLimitPerMember
) {

	public OtpProperties {
		if (codeLength == null) codeLength = 6;
		if (ttl == null) ttl = Duration.ofMinutes(3);
		if (maxAttempts == null) maxAttempts = 5;
		if (cooldown == null) cooldown = Duration.ofMinutes(1);
		if (dailyLimitPerPhone == null) dailyLimitPerPhone = 5;
		if (dailyLimitPerMember == null) dailyLimitPerMember = 10;
	}

}
