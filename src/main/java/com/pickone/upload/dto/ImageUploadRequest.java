package com.pickone.upload.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 업로드 URL 발급 요청 (docs/api.md 4.6). size 는 바이트 */
public record ImageUploadRequest(@NotBlank String contentType, @NotNull @Positive Long size) {
}
