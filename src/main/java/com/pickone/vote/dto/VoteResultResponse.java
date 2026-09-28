package com.pickone.vote.dto;

import com.pickone.question.domain.Question;
import com.pickone.vote.service.VoteResultCalculator.Tally;
import java.util.List;

/** 결과 조회 응답 (docs/api.md 5.2). 작성자는 myOptionId = null */
public record VoteResultResponse(Long questionId, long totalVotes, Long myOptionId, List<Option> options) {

	public static VoteResultResponse of(Question question, Long myOptionId, Tally tally) {
		List<Option> options = tally.options().stream()
				.map(o -> new Option(o.option().getId(), o.option().getSortOrder(), o.option().getContent(),
						o.option().getImageUrl(), o.count(), o.percent()))
				.toList();
		return new VoteResultResponse(question.getId(), tally.totalVotes(), myOptionId, options);
	}

	public record Option(Long optionId, int sortOrder, String content, String imageUrl, long count, double percent) {
	}

}
