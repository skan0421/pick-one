package com.pickone.question.dto;

import com.pickone.question.domain.Question;
import com.pickone.question.domain.QuestionStatus;
import com.pickone.question.domain.QuestionType;
import com.pickone.vote.service.VoteResultCalculator.Tally;
import java.time.LocalDateTime;
import java.util.List;

/** 내 고민 목록 항목 (docs/api.md 4.4). 투표 현황은 페이지 단위로 한 번 집계한 값을 넣는다 */
public record MyQuestionResponse(
		Long id,
		QuestionType questionType,
		String content,
		QuestionStatus status,
		LocalDateTime boostedUntil,
		long totalVotes,
		List<MyOptionResponse> options,
		LocalDateTime createdAt
) {

	public static MyQuestionResponse of(Question question, Tally tally) {
		return new MyQuestionResponse(
				question.getId(),
				question.getQuestionType(),
				question.getContent(),
				question.getStatus(),
				question.getBoostedUntil(),
				tally.totalVotes(),
				tally.options().stream()
						.map(o -> new MyOptionResponse(o.option().getId(), o.option().getSortOrder(), o.option().getContent(),
								o.option().getImageUrl(), o.count(), o.percent()))
						.toList(),
				question.getCreatedAt());
	}

	public record MyOptionResponse(Long id, int sortOrder, String content, String imageUrl, long count, double percent) {
	}

}
