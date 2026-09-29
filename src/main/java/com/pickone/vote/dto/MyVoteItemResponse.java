package com.pickone.vote.dto;

import com.pickone.question.domain.Question;
import com.pickone.question.domain.QuestionStatus;
import com.pickone.question.domain.QuestionType;
import com.pickone.question.dto.OptionResponse;
import com.pickone.question.dto.QuestionResponse;
import com.pickone.question.dto.QuestionResponse.ResultResponse;
import com.pickone.vote.repository.VoteQueryRepository.MyVoteRow;
import com.pickone.vote.service.VoteResultCalculator.Tally;
import java.time.LocalDateTime;
import java.util.List;

/** 내가 투표한 고민 목록 항목 (docs/api.md 5.3): 고민 요약 + 내가 고른 선택지 + 현재 결과 */
public record MyVoteItemResponse(
		Long voteId,
		LocalDateTime votedAt,
		QuestionSummary question,
		Long myOptionId,
		ResultResponse result
) {

	public static MyVoteItemResponse of(MyVoteRow row, Question question, Tally tally, LocalDateTime now) {
		return new MyVoteItemResponse(row.voteId(), row.votedAt(), QuestionSummary.of(question, now), row.optionId(),
				ResultResponse.from(tally));
	}

	public record QuestionSummary(Long id, QuestionType questionType, String content, QuestionStatus status, boolean boosted,
			List<OptionResponse> options, QuestionResponse.AuthorResponse author, LocalDateTime createdAt) {

		static QuestionSummary of(Question q, LocalDateTime now) {
			return new QuestionSummary(q.getId(), q.getQuestionType(), q.getContent(), q.getStatus(), q.isBoostedAt(now),
					q.getOptions().stream().map(OptionResponse::from).toList(),
					new QuestionResponse.AuthorResponse(q.getAuthor().getNickname()), q.getCreatedAt());
		}

	}

}
