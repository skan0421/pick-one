package com.pickone.point.repository;

import com.pickone.point.domain.PointLedger;
import com.pickone.point.domain.TxType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 원장은 INSERT 전용이다. JpaRepository 대신 Repository 를 상속해 저장·조회 메서드만 노출하고
 * delete / deleteAll / saveAll 같은 변경 메서드는 두지 않는다.
 * (재선언한 save / findById 는 Spring Data 가 SimpleJpaRepository 구현으로 연결한다)
 */
public interface PointLedgerRepository extends Repository<PointLedger, Long> {

	<S extends PointLedger> S save(S entity);

	Optional<PointLedger> findById(Long id);

	Optional<PointLedger> findByIdempotencyKey(String idempotencyKey);

	/** 회원 원장 합계. 불변식 SUM(amount) = wallet.balance 검증과 잔액 복구에 쓴다 */
	@Query("SELECT COALESCE(SUM(l.amount), 0) FROM PointLedger l WHERE l.memberId = :memberId")
	long sumAmountByMemberId(@Param("memberId") Long memberId);

	/** 특정 유형의 기간 합계. 일일 적립 상한 판정 (idx_point_ledger_member_id_created_at) */
	@Query("SELECT COALESCE(SUM(l.amount), 0) FROM PointLedger l "
			+ "WHERE l.memberId = :memberId AND l.txType = :txType AND l.createdAt >= :from")
	long sumAmountByMemberIdAndTxTypeSince(@Param("memberId") Long memberId, @Param("txType") TxType txType,
			@Param("from") LocalDateTime from);

	// ---- 내역 (커서: created_at DESC, id DESC) ----

	@Query("SELECT l FROM PointLedger l WHERE l.memberId = :memberId ORDER BY l.createdAt DESC, l.id DESC")
	List<PointLedger> findFirstPage(@Param("memberId") Long memberId, Limit limit);

	@Query("SELECT l FROM PointLedger l WHERE l.memberId = :memberId "
			+ "AND (l.createdAt < :cursorCreatedAt OR (l.createdAt = :cursorCreatedAt AND l.id < :cursorId)) "
			+ "ORDER BY l.createdAt DESC, l.id DESC")
	List<PointLedger> findAfter(@Param("memberId") Long memberId, @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
			@Param("cursorId") Long cursorId, Limit limit);

}
