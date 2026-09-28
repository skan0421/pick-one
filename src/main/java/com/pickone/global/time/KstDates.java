package com.pickone.global.time;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * "오늘" 의 기준은 KST 다 (일일 한도·일일 적립 상한은 KST 자정에 초기화).
 * Redis 날짜 키(yyyyMMdd)와 DB created_at 비교 기준을 한 곳에서 계산해 둘이 어긋나지 않게 한다.
 */
public final class KstDates {

	public static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final DateTimeFormatter DATE_KEY = DateTimeFormatter.ofPattern("yyyyMMdd");

	private KstDates() {
	}

	/** Redis 일일 키에 쓰는 오늘 날짜 (KST 기준 yyyyMMdd) */
	public static String today() {
		return LocalDate.now(KST).format(DATE_KEY);
	}

	/**
	 * KST 오늘 자정을 JVM 기본 시간대의 LocalDateTime 으로 돌려준다.
	 * created_at(@CreationTimestamp) 은 JVM 시간대의 LocalDateTime 으로 저장되므로 그 값과 직접 비교할 수 있다.
	 */
	public static LocalDateTime startOfTodayInSystemZone() {
		ZonedDateTime kstMidnight = LocalDate.now(KST).atStartOfDay(KST);
		return kstMidnight.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
	}

	/** 다음 KST 자정까지 남은 시간. 일일 카운터 TTL 용. 0 이하가 되는 경계에서는 1초 */
	public static Duration untilMidnight() {
		ZonedDateTime now = ZonedDateTime.now(KST);
		ZonedDateTime midnight = LocalDateTime.of(now.toLocalDate().plusDays(1), LocalTime.MIDNIGHT).atZone(KST);
		Duration d = Duration.between(now, midnight);
		return d.isZero() || d.isNegative() ? Duration.ofSeconds(1) : d;
	}

}
