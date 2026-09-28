package com.pickone.question.dto;

import com.pickone.question.domain.Question;
import com.pickone.question.domain.QuestionStatus;
import com.pickone.question.domain.QuestionType;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 내 고민 목록 항목 (docs/api.md 4.4). 투표 현황(totalVotes, count, percent)은 투표 기능에서 채운다. 지금은 0.
 */
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

	public static MyQuestionResponse from(Question question) {
		return new MyQuestionResponse(
				question.getId(),
				question.getQuestionType(),
				question.getContent(),
				question.getStatus(),
				question.getBoostedUntil(),
				0L,
				question.getOptions().stream()
						.map(o -> new MyOptionResponse(o.getId(), o.getSortOrder(), o.getContent(), o.getImageUrl(), 0L, 0.0))
						.toList(),
				question.getCreatedAt());
	}

	public record MyOptionResponse(Long id, int sortOrder, String content, String imageUrl, long count, double percent) {
	}

}
