package com.pickone.point.dto;

import java.time.LocalDateTime;

/** 상단 노출 응답 (docs/api.md 6.3). 같은 Idempotency-Key 재요청에도 같은 ledgerId·balanceAfter 가 돌아간다 */
public record BoostResponse(Long questionId, LocalDateTime boostedUntil, long cost, long balanceAfter, Long ledgerId) {
}
