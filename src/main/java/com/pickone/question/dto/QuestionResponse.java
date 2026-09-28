package com.pickone.question.dto;

import com.pickone.question.domain.Question;
import com.pickone.question.domain.QuestionStatus;
import com.pickone.question.domain.QuestionType;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 등록·상세 응답 (docs/api.md 4.1, 4.3).
 * myVote / result 는 투표 기능에서 채운다. 지금은 항상 null (JSON 생략).
 */
public record QuestionResponse(
		Long id,
		QuestionType questionType,
		String content,
		QuestionStatus status,
		boolean boosted,
		LocalDateTime boostedUntil,
		List<OptionResponse> options,
		AuthorResponse author,
		boolean isMine,
		MyVoteResponse myVote,
		ResultResponse result,
		LocalDateTime createdAt
) {

	public static QuestionResponse of(Question question, Long viewerId, LocalDateTime now) {
		return new QuestionResponse(
				question.getId(),
				question.getQuestionType(),
				question.getContent(),
				question.getStatus(),
				question.isBoostedAt(now),
				question.getBoostedUntil(),
				question.getOptions().stream().map(OptionResponse::from).toList(),
				new AuthorResponse(question.getAuthor().getNickname()),
				question.isOwnedBy(viewerId),
				null,
				null,
				question.getCreatedAt());
	}

	public record AuthorResponse(String nickname) {
	}

	public record MyVoteResponse(Long optionId) {
	}

	public record ResultResponse(long totalVotes, List<OptionResult> options) {
	}

	public record OptionResult(Long optionId, long count, double percent) {
	}

}
