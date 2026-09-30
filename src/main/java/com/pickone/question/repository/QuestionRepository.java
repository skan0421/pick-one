package com.pickone.question.repository;

import com.pickone.question.domain.Question;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuestionRepository extends JpaRepository<Question, Long>, QuestionQueryRepository {

	/** 작성자 + 선택지를 한 번에 가져온다. 목록 조회 2단계에서 ID 순서는 호출자가 다시 맞춘다 */
	@Query("SELECT DISTINCT q FROM Question q JOIN FETCH q.author LEFT JOIN FETCH q.options WHERE q.id IN :ids")
	List<Question> findAllWithAuthorAndOptionsByIdIn(@Param("ids") List<Long> ids);

	@Query("SELECT q FROM Question q JOIN FETCH q.author LEFT JOIN FETCH q.options WHERE q.id = :id")
	Optional<Question> findWithAuthorAndOptionsById(@Param("id") Long id);

	/** 등록 요청의 Idempotency-Key 로 조회 (uk_question_idempotency_key). 재요청 판정에 쓴다 */
	Optional<Question> findByIdempotencyKey(String idempotencyKey);

	/** SELECT ... FOR UPDATE. 같은 고민에 대한 신고 누적 판정을 직렬화한다 (ReportService) */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT q FROM Question q WHERE q.id = :id")
	Optional<Question> findByIdForUpdate(@Param("id") Long id);

	// ---- 내 고민 목록 (커서: created_at DESC, id DESC). 선택지는 findAllWithAuthorAndOptionsByIdIn 으로 2차 조회 ----

	@Query("SELECT q FROM Question q WHERE q.author.id = :me AND q.deletedAt IS NULL "
			+ "ORDER BY q.createdAt DESC, q.id DESC")
	List<Question> findMineFirstPage(@Param("me") Long me, Limit limit);

	@Query("SELECT q FROM Question q WHERE q.author.id = :me AND q.deletedAt IS NULL "
			+ "AND (q.createdAt < :cursorCreatedAt OR (q.createdAt = :cursorCreatedAt AND q.id < :cursorId)) "
			+ "ORDER BY q.createdAt DESC, q.id DESC")
	List<Question> findMineAfter(@Param("me") Long me, @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
			@Param("cursorId") Long cursorId, Limit limit);

}
