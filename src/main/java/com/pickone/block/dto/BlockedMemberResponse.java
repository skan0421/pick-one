package com.pickone.block.dto;

import java.time.LocalDateTime;

/** 차단 목록 항목 (docs/api.md 8.3) */
public record BlockedMemberResponse(Long memberId, String nickname, LocalDateTime createdAt) {
}
