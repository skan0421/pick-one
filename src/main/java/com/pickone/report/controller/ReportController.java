package com.pickone.report.controller;

import com.pickone.global.security.LoginMemberId;
import com.pickone.report.dto.ReportRequest;
import com.pickone.report.dto.ReportResponse;
import com.pickone.report.service.ReportService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 고민 신고 (docs/api.md 8.4). ACTIVE 회원 전용 */
@Tag(name = "신고", description = "고민 신고와 누적 자동 숨김 (docs/api.md 8.4)")
@RestController
@RequiredArgsConstructor
public class ReportController {

	private final ReportService reportService;

	@Operation(summary = "고민 신고 (누적 5건이면 자동 숨김)")
	@PostMapping("/api/v1/questions/{id}/reports")
	@ResponseStatus(HttpStatus.CREATED)
	public ReportResponse report(@LoginMemberId Long memberId, @PathVariable Long id, @Valid @RequestBody ReportRequest request) {
		return reportService.report(memberId, id, request.reason(), request.detail());
	}

}
