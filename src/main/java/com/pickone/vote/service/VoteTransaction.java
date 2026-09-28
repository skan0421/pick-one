package com.pickone.vote.service;

import com.pickone.global.error.BusinessException;
import com.pickone.global.error.ErrorCode;
import com.pickone.global.time.KstDates;
import com.pickone.point.PointProperties;
import com.pickone.point.domain.PointLedger;
import com.pickone.point.domain.PointWallet;
import com.pickone.point.domain.TxType;
import com.pickone.point.repository.PointLedgerRepository;
import com.pickone.point.repository.PointWalletRepository;
import com.pickone.question.domain.Question;
import com.pickone.question.domain.QuestionOption;
import com.pickone.question.domain.QuestionStatus;
import com.pickone.question.repository.QuestionRepository;
import com.pickone.vote.domain.Vote;
import com.pickone.vote.repository.VoteRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 투표 1건의 DB 트랜잭션 (docs/api.md 5.1). 하나라도 실패하면 전체 롤백.
 *
 * 1. 고민 검증: 없음·삭제·HIDDEN·차단/지인 숨김 → QUESTION_NOT_FOUND, CLOSED → QUESTION_CLOSED, 내 고민 → VOTE_OWN_QUESTION
 * 2. 선택지 소속 검증 → VOTE_OPTION_MISMATCH, 지갑 없음 → POINT_WALLET_NOT_FOUND
 * 3. vote INSERT (IDENTITY 라 즉시 실행). 동시 중복은 uk_vote_member_id_question_id 가 막고 409 로 번역된다
 * 4. 오늘 적립 합계(원장 SUM)가 상한 미만이면 지갑 +1 → 원장 INSERT(idempotency_key = vote:{voteId})
 *    상한 판정을 Redis 가 아니라 원장으로 하는 이유: 적립하는 투표는 모두 같은 지갑 행을 UPDATE 하므로
 *    낙관적 락이 회원 단위로 적립을 직렬화해 준다. 49P 에서 동시에 두 건이 와도 한 건만 적립되고 나머지는 재시도 후 상한에 걸린다
 * 결과 집계는 여기서 하지 않는다. REPEATABLE READ 스냅샷이 트랜잭션 시작 시점에 고정되어 동시에 커밋된 다른 표가 빠지므로,
 * 커밋이 끝난 뒤 VoteService 가 새 스냅샷으로 집계한다 (명세 5.1 의 7단계).
 *
 * 지갑 UPDATE 는 일부러 flush 하지 않고 커밋 시점에 내보낸다 (X 락 보유 시간 최소화). version 불일치는 커밋에서
 * ObjectOptimisticLockingFailureException 으로 나오고, 호출자(VoteService)가 OptimisticRetryExecutor 로 이 메서드 전체를 다시 실행한다.
 */
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(PointProperties.class)
public class VoteTransaction {

	public static final String REASON_DAILY_LIMIT = "DAILY_LIMIT_REACHED";

	private final QuestionRepository questionRepository;
	private final VoteRepository voteRepository;
	private final PointWalletRepository pointWalletRepository;
	private final PointLedgerRepository pointLedgerRepository;
	private final PointProperties properties;

	/** 트랜잭션 결과. 응답 조립·집계는 트랜잭션 밖에서 한다 (options 는 fetch join 으로 이미 로드됨) */
	public record VoteOutcome(Long voteId, Long optionId, List<QuestionOption> options,
			boolean earned, long amount, String reason) {
	}

	@Transactional
	public VoteOutcome execute(Long memberId, Long questionId, Long optionId) {
		Question question = questionRepository.findWithAuthorAndOptionsById(questionId)
				.filter(q -> !q.isDeleted() && q.getStatus() != QuestionStatus.HIDDEN)
				.orElseThrow(() -> new BusinessException(ErrorCode.QUESTION_NOT_FOUND));
		if (questionRepository.isHiddenFromViewer(memberId, question.getAuthor().getId())) {
			throw new BusinessException(ErrorCode.QUESTION_NOT_FOUND);
		}
		if (question.isClosed()) {
			throw new BusinessException(ErrorCode.QUESTION_CLOSED);
		}
		if (question.isOwnedBy(memberId)) {
			throw new BusinessException(ErrorCode.VOTE_OWN_QUESTION);
		}
		QuestionOption option = question.getOptions().stream()
				.filter(o -> o.getId().equals(optionId))
				.findFirst()
				.orElseThrow(() -> new BusinessException(ErrorCode.VOTE_OPTION_MISMATCH));
		PointWallet wallet = pointWalletRepository.findById(memberId)
				.orElseThrow(() -> new BusinessException(ErrorCode.POINT_WALLET_NOT_FOUND));

		Vote vote = voteRepository.save(Vote.cast(question, option, memberId));

		long reward = properties.voteReward();
		long todayEarned = pointLedgerRepository.sumAmountByMemberIdAndTxTypeSince(
				memberId, TxType.VOTE_REWARD, KstDates.startOfTodayInSystemZone());
		boolean earned = todayEarned + reward <= properties.dailyEarnLimit();
		if (earned) {
			wallet.earn(reward);
			pointLedgerRepository.save(PointLedger.voteReward(memberId, reward, wallet.getBalance(), vote.getId()));
		}

		return new VoteOutcome(vote.getId(), option.getId(), question.getOptions(),
				earned, earned ? reward : 0L, earned ? null : REASON_DAILY_LIMIT);
	}

}
