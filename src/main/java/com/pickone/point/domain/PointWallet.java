package com.pickone.point.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 회원별 포인트 잔액. 회원이 ACTIVE 로 전환되는 시점에 0 으로 생성된다.
 * 모든 변동의 근거는 point_ledger 이며 SUM(ledger.amount) = balance 가 항상 성립해야 한다.
 * version 으로 낙관적 락, balance >= 0 은 DB CHECK(chk_point_wallet_balance) 가 마지막 방어선.
 */
@Entity
@Table(name = "point_wallet")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PointWallet {

	/** member.id 와 같은 값. 1:1 이므로 별도 PK 를 두지 않는다 */
	@Id
	@Column(name = "member_id")
	private Long memberId;

	@Column(name = "balance", nullable = false)
	private long balance;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	private PointWallet(Long memberId) {
		this.memberId = memberId;
		this.balance = 0L;
	}

	public static PointWallet open(Long memberId) {
		return new PointWallet(memberId);
	}

}
