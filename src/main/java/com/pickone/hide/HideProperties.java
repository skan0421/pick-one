package com.pickone.hide;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** pickone.hide.* 설정 (docs/api.md 7.1) */
@ConfigurationProperties(prefix = "pickone.hide")
public record HideProperties(
		/** 연락처 업로드 상한. 초과 시 CONTACTS_TOO_MANY */
		Integer maxContacts,
		/** JDBC 배치·IN 절 분할 크기 */
		Integer batchSize
) {

	public HideProperties {
		if (maxContacts == null) maxContacts = 5000;
		if (batchSize == null) batchSize = 1000;
	}

}
