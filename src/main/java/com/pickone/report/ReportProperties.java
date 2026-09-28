package com.pickone.report;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** pickone.report.* 설정 (docs/api.md 8.4 자동 숨김 정책) */
@ConfigurationProperties(prefix = "pickone.report")
public record ReportProperties(
		/** 같은 고민의 RECEIVED 신고가 이 수 이상이면 status 를 HIDDEN 으로 바꾼다 */
		Integer autoHideThreshold
) {

	public ReportProperties {
		if (autoHideThreshold == null) autoHideThreshold = 5;
	}

}
