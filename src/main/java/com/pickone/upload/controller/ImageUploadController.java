package com.pickone.upload.controller;

import com.pickone.global.security.LoginMemberId;
import com.pickone.upload.dto.ImageUploadRequest;
import com.pickone.upload.dto.ImageUploadResponse;
import com.pickone.upload.service.ImageUploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 사진 업로드 URL 발급 (docs/api.md 4.6). ACTIVE 회원 전용 */
@Tag(name = "업로드", description = "사진형 고민의 이미지 업로드 URL 발급 (presigned PUT, docs/api.md 4.6)")
@RestController
@RequiredArgsConstructor
public class ImageUploadController {

	private final ImageUploadService imageUploadService;

	@Operation(summary = "이미지 업로드 URL 발급 (jpeg/png/webp, 최대 5MB, 5분 유효)")
	@PostMapping("/api/v1/uploads/images")
	public ImageUploadResponse issue(@LoginMemberId Long memberId, @Valid @RequestBody ImageUploadRequest request) {
		return imageUploadService.issue(memberId, request.contentType(), request.size());
	}

}
