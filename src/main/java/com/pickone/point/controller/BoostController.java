package com.pickone.point.controller;

import com.pickone.global.idempotency.IdempotencyKeys;
import com.pickone.global.security.LoginMemberId;
import com.pickone.point.dto.BoostResponse;
import com.pickone.point.service.BoostService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** 상단 노출 사용 (docs/api.md 6.3). Idempotency-Key 헤더 필수 — 누락은 서비스에서 400 IDEMPOTENCY_KEY_REQUIRED 로 응답 */
@Tag(name = "포인트", description = "잔액·내역·상단 노출 (docs/api.md 6장)")
@RestController
@RequiredArgsConstructor
public class BoostController {

	private final BoostService boostService;

	@Operation(summary = "상단 노출 사용 (100P, 24시간, Idempotency-Key 헤더 필수)")
	@PostMapping("/api/v1/questions/{id}/boosts")
	public BoostResponse boost(@LoginMemberId Long memberId, @PathVariable Long id,
			@RequestHeader(value = IdempotencyKeys.HEADER, required = false) String idempotencyKey) {
		return boostService.boost(memberId, id, idempotencyKey);
	}

}
