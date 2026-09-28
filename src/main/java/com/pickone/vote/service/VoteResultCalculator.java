package com.pickone.vote.service;

import com.pickone.question.domain.QuestionOption;
import com.pickone.vote.repository.OptionCount;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 득표수 → 결과(총 투표수, 선택지별 count·percent) 계산 (docs/api.md 5.2).
 * percent 는 소수점 1자리 반올림. 반올림 합이 100.0 이 아니면 득표가 가장 많은 선택지(동률이면 앞 순서)에서 보정한다.
 * 부동소수 오차를 피하려고 0.1% 단위 정수(tenths)로 계산한다.
 */
public final class VoteResultCalculator {

	private VoteResultCalculator() {
	}

	public record Tally(long totalVotes, List<OptionTally> options) {

		public OptionTally of(Long optionId) {
			return options.stream().filter(o -> o.option().getId().equals(optionId)).findFirst().orElseThrow();
		}

	}

	public record OptionTally(QuestionOption option, long count, double percent) {
	}

	/** 선택지 순서(sort_order)를 유지한다. 집계에 없는 선택지는 0표 */
	public static Tally tally(List<QuestionOption> options, List<OptionCount> counts) {
		Map<Long, Long> countByOptionId = new HashMap<>();
		for (OptionCount c : counts) {
			countByOptionId.merge(c.optionId(), c.count(), Long::sum);
		}
		long total = countByOptionId.values().stream().mapToLong(Long::longValue).sum();

		long[] tenths = new long[options.size()];
		long[] votes = new long[options.size()];
		long tenthsSum = 0;
		int maxIndex = 0;
		for (int i = 0; i < options.size(); i++) {
			votes[i] = countByOptionId.getOrDefault(options.get(i).getId(), 0L);
			tenths[i] = total == 0 ? 0 : Math.round(votes[i] * 1000.0 / total);
			tenthsSum += tenths[i];
			if (votes[i] > votes[maxIndex]) {
				maxIndex = i;
			}
		}
		if (total > 0 && tenthsSum != 1000) {
			tenths[maxIndex] += 1000 - tenthsSum;
		}

		List<OptionTally> result = new ArrayList<>(options.size());
		for (int i = 0; i < options.size(); i++) {
			result.add(new OptionTally(options.get(i), votes[i], tenths[i] / 10.0));
		}
		return new Tally(total, result);
	}

	/** 여러 고민의 집계 행을 고민별로 나눠 계산한다 (내 고민 목록) */
	public static Map<Long, List<OptionCount>> groupByQuestion(List<OptionCount> counts) {
		Map<Long, List<OptionCount>> byQuestion = new HashMap<>();
		for (OptionCount c : counts) {
			byQuestion.computeIfAbsent(c.questionId(), k -> new ArrayList<>()).add(c);
		}
		return byQuestion;
	}

}
