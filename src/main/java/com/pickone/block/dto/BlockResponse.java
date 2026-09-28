package com.pickone.block.dto;

import java.time.LocalDateTime;

/** 차단 응답 (docs/api.md 8.1). 이미 차단한 상대면 최초 차단 시각을 그대로 돌려준다 */
public record BlockResponse(Long blockedMemberId, LocalDateTime createdAt) {
}
