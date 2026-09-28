package com.pickone.report.service;

import com.pickone.global.error.BusinessException;
import com.pickone.global.error.ErrorCode;
import com.pickone.question.domain.Question;
import com.pickone.question.repository.QuestionRepository;
import com.pickone.report.ReportProperties;
import com.pickone.report.domain.Report;
import com.pickone.report.domain.ReportReason;
import com.pickone.report.domain.ReportStatus;
import com.pickone.report.dto.ReportResponse;
import com.pickone.report.repository.ReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고민 신고와 자동 숨김 (docs/api.md 8.4).
 *
 * 신고 누적 N건(설정값) 도달 시 HIDDEN 전환이 동시 신고에서도 정확히 일어나야 한다.
 * 각 신고가 "INSERT 후 COUNT" 를 자기 스냅샷으로만 하면, 3건 있는 상태에서 2건이 동시에 오면 둘 다 4를 세어 5건째 전환을 놓친다.
 * 그래서 고민 행을 SELECT ... FOR UPDATE 로 먼저 잠가 같은 고민의 신고를 직렬화한다. 이 잠금 읽기가 트랜잭션의 첫 문장이라
 * InnoDB read view 는 그 뒤의 COUNT 에서 만들어지고, 앞서 커밋된 신고가 모두 보인다.
 * 이미 HIDDEN 인 고민도 신고는 접수한다 (운영 판단 근거 보존). 삭제된 고민은 404, 내 고민은 400.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(ReportProperties.class)
public class ReportService {

	private final QuestionRepository questionRepository;
	private final ReportRepository reportRepository;
	private final ReportProperties properties;

	@Transactional
	public ReportResponse report(Long memberId, Long questionId, ReportReason reason, String detail) {
		Question question = questionRepository.findByIdForUpdate(questionId)
				.filter(q -> !q.isDeleted())
				.orElseThrow(() -> new BusinessException(ErrorCode.QUESTION_NOT_FOUND));
		if (question.isOwnedBy(memberId)) {
			throw new BusinessException(ErrorCode.REPORT_OWN_QUESTION);
		}

		String trimmed = detail == null || detail.isBlank() ? null : detail.trim();
		Report report = reportRepository.save(Report.receive(questionId, memberId, reason, trimmed));

		long received = reportRepository.countByQuestionIdAndStatus(questionId, ReportStatus.RECEIVED);
		if (received >= properties.autoHideThreshold() && question.hideByReports()) {
			log.info("신고 누적으로 고민 숨김: questionId={}, received={}", questionId, received);
		}
		return new ReportResponse(report.getId(), report.getStatus());
	}

}
