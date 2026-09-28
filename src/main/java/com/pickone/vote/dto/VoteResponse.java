package com.pickone.vote.dto;

import com.pickone.vote.service.VoteResultCalculator.Tally;
import com.pickone.vote.service.VoteTransaction.VoteOutcome;
import java.util.List;

/** 투표 응답 (docs/api.md 5.1). 일일 상한에 걸리면 투표는 성공하고 pointReward 만 earned=false 가 된다 */
public record VoteResponse(Long voteId, Result result, PointReward pointReward) {

	public static VoteResponse of(VoteOutcome outcome, Tally tally) {
		List<OptionResult> options = tally.options().stream()
				.map(o -> new OptionResult(o.option().getId(), o.count(), o.percent()))
				.toList();
		return new VoteResponse(outcome.voteId(),
				new Result(tally.totalVotes(), outcome.optionId(), options),
				new PointReward(outcome.earned(), outcome.amount(), outcome.reason()));
	}

	public record Result(long totalVotes, Long myOptionId, List<OptionResult> options) {
	}

	public record OptionResult(Long optionId, long count, double percent) {
	}

	/** reason 은 적립되지 않았을 때만 (DAILY_LIMIT_REACHED), 적립되면 null → JSON 생략 */
	public record PointReward(boolean earned, long amount, String reason) {
	}

}
