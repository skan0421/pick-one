package com.pickone.point.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;

/**
 * 포인트 원장. 모든 적립·차감의 근거이며 INSERT 만 한다 (수정·삭제 없음).
 * - 엔티티는 @Immutable 이라 Hibernate 가 변경을 UPDATE 로 내보내지 않는다
 * - 리포지토리(PointLedgerRepository)는 저장·조회 메서드만 노출한다
 * - idempotency_key 유니크가 같은 요청의 이중 처리를 막는다: 투표 보상은 "vote:{voteId}", boost 는 클라이언트 헤더값
 * 불변식: SUM(amount) = point_wallet.balance
 */
@Entity
@Table(name = "point_ledger")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PointLedger {

	private static final String VOTE_REWARD_KEY = "vote:%d";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "member_id", nullable = false)
	private Long memberId;

	/** 적립 +, 차감 - */
	@Column(name = "amount", nullable = false)
	private long amount;

	/** 처리 후 잔액 (검증용) */
	@Column(name = "balance_after", nullable = false)
	private long balanceAfter;

	@Enumerated(EnumType.STRING)
	@Column(name = "tx_type", nullable = false, length = 30)
	private TxType txType;

	@Enumerated(EnumType.STRING)
	@Column(name = "ref_type", length = 30)
	private RefType refType;

	@Column(name = "ref_id")
	private Long refId;

	@Column(name = "idempotency_key", nullable = false, length = 100)
	private String idempotencyKey;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private PointLedger(Long memberId, long amount, long balanceAfter, TxType txType, RefType refType, Long refId,
			String idempotencyKey) {
		this.memberId = memberId;
		this.amount = amount;
		this.balanceAfter = balanceAfter;
		this.txType = txType;
		this.refType = refType;
		this.refId = refId;
		this.idempotencyKey = idempotencyKey;
	}

	/** 투표 보상 적립. 키는 서버가 만든다 */
	public static PointLedger voteReward(Long memberId, long amount, long balanceAfter, Long voteId) {
		return new PointLedger(memberId, amount, balanceAfter, TxType.VOTE_REWARD, RefType.VOTE, voteId, voteRewardKey(voteId));
	}

	/** 상단 노출 사용 차감. 키는 클라이언트의 Idempotency-Key 헤더값 */
	public static PointLedger boostUse(Long memberId, long cost, long balanceAfter, Long questionId, String idempotencyKey) {
		return new PointLedger(memberId, -cost, balanceAfter, TxType.BOOST_USE, RefType.QUESTION, questionId, idempotencyKey);
	}

	public static String voteRewardKey(Long voteId) {
		return VOTE_REWARD_KEY.formatted(voteId);
	}

	/** 같은 회원이 같은 고민에 boost 한 항목인지 (멱등 재응답 판정) */
	public boolean isBoostOf(Long memberId, Long questionId) {
		return this.memberId.equals(memberId) && txType == TxType.BOOST_USE && refType == RefType.QUESTION
				&& questionId.equals(refId);
	}

}
