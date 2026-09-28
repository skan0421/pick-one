package com.pickone.point.dto;

/** 잔액 응답 (docs/api.md 6.1) */
public record PointBalanceResponse(long balance, long todayEarned, long dailyEarnLimit) {
}
