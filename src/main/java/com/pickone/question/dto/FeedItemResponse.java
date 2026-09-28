package com.pickone.question.dto;

import com.pickone.question.domain.Question;
import com.pickone.question.domain.QuestionType;
import java.time.LocalDateTime;
import java.util.List;

/** 피드 항목 (docs/api.md 4.2). 투표 전 편향을 막기 위해 결과(득표수)는 넣지 않는다 */
public record FeedItemResponse(
		Long id,
		QuestionType questionType,
		String content,
		boolean boosted,
		List<OptionResponse> options,
		QuestionResponse.AuthorResponse author,
		LocalDateTime createdAt
) {

	public static FeedItemResponse of(Question question, LocalDateTime now) {
		return new FeedItemResponse(
				question.getId(),
				question.getQuestionType(),
				question.getContent(),
				question.isBoostedAt(now),
				question.getOptions().stream().map(OptionResponse::from).toList(),
				new QuestionResponse.AuthorResponse(question.getAuthor().getNickname()),
				question.getCreatedAt());
	}

}
