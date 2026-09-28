package com.pickone.point;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** pickone.point.* 설정. 기본값은 docs/api.md 1.7 의 포인트 정책. 코드에 수치를 하드코딩하지 않는다 */
@ConfigurationProperties(prefix = "pickone.point")
public record PointProperties(
		/** 투표 1건 적립 포인트 */
		Long voteReward,
		/** 회원별 투표 적립 일일 상한 (KST 자정 초기화) */
		Long dailyEarnLimit,
		/** 상단 노출 1회 비용 */
		Long boostCost,
		/** 상단 노출 지속 시간 */
		Duration boostDuration,
		/** 지갑 낙관적 락 충돌 시 재시도 횟수 (총 시도 = 1 + 이 값) */
		Integer walletMaxRetries
) {

	public PointProperties {
		if (voteReward == null) voteReward = 1L;
		if (dailyEarnLimit == null) dailyEarnLimit = 50L;
		if (boostCost == null) boostCost = 100L;
		if (boostDuration == null) boostDuration = Duration.ofHours(24);
		if (walletMaxRetries == null) walletMaxRetries = 3;
	}

}
