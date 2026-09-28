package com.pickone.upload.dto;

import java.time.LocalDateTime;

/**
 * 업로드 URL 발급 응답 (docs/api.md 4.6).
 * uploadUrl 로 발급 시 선언한 Content-Type·크기 그대로 PUT 한 뒤, imageUrl 을 고민 등록의 imageUrl 에 넣는다.
 */
public record ImageUploadResponse(String uploadUrl, String imageUrl, LocalDateTime expiresAt) {
}
