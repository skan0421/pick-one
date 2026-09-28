package com.pickone.block.dto;

import java.util.List;

/** 차단 목록 (docs/api.md 8.3). 차단 수는 많지 않으므로 페이징 없음 */
public record BlockListResponse(List<BlockedMemberResponse> items) {
}
