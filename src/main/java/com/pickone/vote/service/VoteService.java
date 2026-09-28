package com.pickone.vote.service;

import com.pickone.global.error.BusinessException;
import com.pickone.global.error.ErrorCode;
import com.pickone.global.time.KstDates;
import com.pickone.point.repository.PointDailyCounterStore;
import com.pickone.point.service.OptimisticRetryExecutor;
import com.pickone.question.domain.Question;
import com.pickone.question.domain.QuestionStatus;
import com.pickone.question.repository.QuestionRepository;
import com.pickone.vote.domain.Vote;
import com.pickone.vote.dto.VoteResponse;
import com.pickone.vote.dto.VoteResultResponse;
import com.pickone.vote.repository.VoteRepository;
import com.pickone.vote.service.VoteResultCalculator.Tally;
import com.pickone.vote.service.VoteTransaction.VoteOutcome;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 투표 API 서비스.
 * vote() 에는 일부러 @Transactional 을 붙이지 않는다. DB 작업은 VoteTransaction 이 한 트랜잭션으로 처리하고,
 * 낙관적 락 충돌 시 OptimisticRetryExecutor 가 그 트랜잭션을 통째로 다시 실행한다.
 * Redis 일일 카운터는 커밋이 끝난 뒤(execute 가 정상 반환한 뒤)에만 올린다. 롤백된 적립이 세어지지 않게 하기 위해서다.
 * 결과 집계도 커밋 뒤에 한다. 트랜잭션 안에서 세면 REPEATABLE READ 스냅샷 때문에 동시에 들어온 다른 표가 빠진다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoteService {

	private final VoteTransaction voteTransaction;
	private final OptimisticRetryExecutor retryExecutor;
	private final PointDailyCounterStore dailyCounterStore;
	private final QuestionRepository questionRepository;
	private final VoteRepository voteRepository;

	public VoteResponse vote(Long memberId, Long questionId, Long optionId) {
		VoteOutcome outcome = retryExecutor.execute("vote question=" + questionId,
				() -> voteTransaction.execute(memberId, questionId, optionId));

		if (outcome.earned()) {
			// 커밋 이후. 카운터는 표시용 캐시라 실패해도 투표·적립 결과에는 영향이 없다
			try {
				dailyCounterStore.increment(memberId, KstDates.today(), outcome.amount(), KstDates.untilMidnight());
			}
			catch (RuntimeException e) {
				log.warn("일일 적립 카운터 갱신 실패 (원장에서 복구됨): memberId={}", memberId, e);
			}
		}

		// 커밋 이후 새 스냅샷으로 집계 (idx_vote_question_id_option_id)
		Tally tally = VoteResultCalculator.tally(outcome.options(), voteRepository.countByQuestion(questionId));
		return VoteResponse.of(outcome, tally);
	}

	/** 결과 조회 (docs/api.md 5.2): 투표한 사람 또는 작성자만 */
	@Transactional(readOnly = true)
	public VoteResultResponse results(Long viewerId, Long questionId) {
		Question question = questionRepository.findWithAuthorAndOptionsById(questionId)
				.filter(q -> q.isOwnedBy(viewerId) || (!q.isDeleted() && q.getStatus() != QuestionStatus.HIDDEN))
				.orElseThrow(() -> new BusinessException(ErrorCode.QUESTION_NOT_FOUND));
		if (!question.isOwnedBy(viewerId) && questionRepository.isHiddenFromViewer(viewerId, question.getAuthor().getId())) {
			throw new BusinessException(ErrorCode.QUESTION_NOT_FOUND);
		}
		Optional<Vote> myVote = voteRepository.findByMemberIdAndQuestionId(viewerId, questionId);
		if (!question.isOwnedBy(viewerId) && myVote.isEmpty()) {
			throw new BusinessException(ErrorCode.RESULT_NOT_ALLOWED);
		}
		Tally tally = VoteResultCalculator.tally(question.getOptions(), voteRepository.countByQuestion(questionId));
		// 작성자는 자기 고민에 투표할 수 없으므로 myOptionId 는 null
		Long myOptionId = myVote.map(v -> v.getOption().getId()).orElse(null);
		return VoteResultResponse.of(question, myOptionId, tally);
	}

}
