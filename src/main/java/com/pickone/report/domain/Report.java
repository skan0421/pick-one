package com.pickone.report.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * 고민 신고. (reporter_id, question_id) 유니크 uk_report_reporter_id_question_id 가 중복 신고를 막는다 (409 REPORT_DUPLICATE).
 * 고민·회원은 ID 로만 참조한다 (FK 는 DB 가 보장).
 */
@Entity
@Table(name = "report")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Report {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "question_id", nullable = false)
	private Long questionId;

	@Column(name = "reporter_id", nullable = false)
	private Long reporterId;

	@Enumerated(EnumType.STRING)
	@Column(name = "reason", nullable = false, length = 30)
	private ReportReason reason;

	@Column(name = "detail", length = 200)
	private String detail;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private ReportStatus status;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private Report(Long questionId, Long reporterId, ReportReason reason, String detail) {
		this.questionId = questionId;
		this.reporterId = reporterId;
		this.reason = reason;
		this.detail = detail;
		this.status = ReportStatus.RECEIVED;
	}

	public static Report receive(Long questionId, Long reporterId, ReportReason reason, String detail) {
		return new Report(questionId, reporterId, reason, detail);
	}

}
