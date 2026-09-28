package com.pickone.question.dto;

import com.pickone.question.domain.Question;
import com.pickone.question.domain.QuestionStatus;
import com.pickone.question.domain.QuestionType;
import com.pickone.vote.service.VoteResultCalculator.Tally;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 등록·상세 응답 (docs/api.md 4.1, 4.3).
 * myVote / result 는 내가 투표했거나 내 고민일 때만 채워지고, 아니면 null (JSON 생략).
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

	/** 등록 직후 등 투표 정보가 없는 경우 */
	public static QuestionResponse of(Question question, Long viewerId, LocalDateTime now) {
		return of(question, viewerId, now, null, null);
	}

	public static QuestionResponse of(Question question, Long viewerId, LocalDateTime now, Long myOptionId, Tally tally) {
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
				myOptionId == null ? null : new MyVoteResponse(myOptionId),
				tally == null ? null : ResultResponse.from(tally),
				question.getCreatedAt());
	}

	public record AuthorResponse(String nickname) {
	}

	public record MyVoteResponse(Long optionId) {
	}

	public record ResultResponse(long totalVotes, List<OptionResult> options) {

		public static ResultResponse from(Tally tally) {
			return new ResultResponse(tally.totalVotes(), tally.options().stream()
					.map(o -> new OptionResult(o.option().getId(), o.count(), o.percent()))
					.toList());
		}

	}

	public record OptionResult(Long optionId, long count, double percent) {
	}

}
