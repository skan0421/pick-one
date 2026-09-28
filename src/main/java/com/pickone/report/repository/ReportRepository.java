package com.pickone.report.repository;

import com.pickone.report.domain.Report;
import com.pickone.report.domain.ReportStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportRepository extends JpaRepository<Report, Long> {

	long countByQuestionIdAndStatus(Long questionId, ReportStatus status);

}
