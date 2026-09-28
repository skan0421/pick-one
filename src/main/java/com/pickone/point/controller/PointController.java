package com.pickone.point.controller;

import com.pickone.global.paging.CursorPage;
import com.pickone.global.security.LoginMemberId;
import com.pickone.point.dto.PointBalanceResponse;
import com.pickone.point.dto.PointLedgerItemResponse;
import com.pickone.point.service.PointService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 포인트 조회 (docs/api.md 6.1, 6.2). ACTIVE 회원 전용 */
@Tag(name = "포인트", description = "잔액·내역·상단 노출 (docs/api.md 6장)")
@RestController
@RequestMapping("/api/v1/points")
@RequiredArgsConstructor
public class PointController {

	private final PointService pointService;

	@Operation(summary = "잔액 조회")
	@GetMapping("/balance")
	public PointBalanceResponse balance(@LoginMemberId Long memberId) {
		return pointService.balance(memberId);
	}

	@Operation(summary = "포인트 내역 (커서)")
	@GetMapping("/ledger")
	public CursorPage<PointLedgerItemResponse> ledger(@LoginMemberId Long memberId,
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer size) {
		return pointService.ledger(memberId, cursor, size);
	}

}
