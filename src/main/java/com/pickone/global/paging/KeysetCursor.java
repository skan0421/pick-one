package com.pickone.global.paging;

import java.time.LocalDateTime;

/** created_at DESC, id DESC 정렬 목록의 키셋 커서 (내 고민 목록, 포인트 내역) */
public record KeysetCursor(LocalDateTime createdAt, Long id) {
}
