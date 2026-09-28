package com.pickone.report.dto;

import com.pickone.report.domain.ReportReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 신고 요청 (docs/api.md 8.4). detail 은 선택, 200자 */
public record ReportRequest(@NotNull ReportReason reason, @Size(max = 200) String detail) {
}
