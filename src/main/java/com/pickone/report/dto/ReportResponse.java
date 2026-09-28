package com.pickone.report.dto;

import com.pickone.report.domain.ReportStatus;

public record ReportResponse(Long reportId, ReportStatus status) {
}
