package com.pickone.vote.repository;

import com.pickone.global.paging.KeysetCursor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 내가 투표한 고민 목록 (docs/api.md 5.3). 피드와 같은 2단계 조회: 여기서 vote 행(ID·선택지·시각)만 고르고, 고민·선택지는 fetch join 으로 따로 읽는다.
 *
 * 제외 규칙 — 상세 API 가 404 를 주는 항목은 목록에서도 뺀다 ("목록의 항목은 모두 열 수 있다"):
 * 삭제된 고민, HIDDEN 고민, 양방향 차단 관계, 작성자가 지인 숨기기를 켜고 나를 등록한 경우. CLOSED 는 결과를 볼 수 있으므로 포함.
 * 투표·적립 기록 자체는 그대로 남는다.
 *
 * 인덱스: idx_vote_member_id_created_at (V5). member_id = :me 로 ref 접근 후 인덱스 순서(created_at, 뒤에 붙는 PK id)가
 * ORDER BY v.created_at DESC, v.id DESC 와 일치해 filesort 없이 LIMIT 에서 멈춘다 (MyVotesExplainTest).
 */
public class VoteQueryRepositoryImpl implements VoteQueryRepository {

	private static final String BASE = """
			SELECT v.id, v.question_id, v.option_id, v.created_at
			FROM vote v
			JOIN question q ON q.id = v.question_id
			WHERE v.member_id = :me
			  AND q.deleted_at IS NULL AND q.status <> 'HIDDEN'
			  AND NOT EXISTS (SELECT 1 FROM member_block b WHERE b.blocker_id = :me AND b.blocked_id = q.member_id)
			  AND NOT EXISTS (SELECT 1 FROM member_block b WHERE b.blocker_id = q.member_id AND b.blocked_id = :me)
			  AND NOT EXISTS (SELECT 1 FROM hide_relation h
			                  JOIN member a ON a.id = h.owner_id
			                  WHERE h.owner_id = q.member_id AND h.target_member_id = :me AND a.hide_from_contacts = b'1')
			""";
	private static final String KEYSET = """
			  AND (v.created_at < :cursorCreatedAt OR (v.created_at = :cursorCreatedAt AND v.id < :cursorId))
			""";
	private static final String ORDER_AND_LIMIT = """
			ORDER BY v.created_at DESC, v.id DESC
			LIMIT :limit
			""";

	@PersistenceContext
	private EntityManager em;

	@Override
	@SuppressWarnings("unchecked")
	public List<MyVoteRow> findMyVotes(Long memberId, KeysetCursor after, int limit) {
		Query query = em.createNativeQuery(myVotesSql(after != null))
				.setParameter("me", memberId)
				.setParameter("limit", limit);
		if (after != null) {
			query.setParameter("cursorCreatedAt", after.createdAt()).setParameter("cursorId", after.id());
		}
		List<Object[]> rows = query.getResultList();
		return rows.stream()
				.map(r -> new MyVoteRow(((Number) r[0]).longValue(), ((Number) r[1]).longValue(),
						((Number) r[2]).longValue(), toLocalDateTime(r[3])))
				.toList();
	}

	private static LocalDateTime toLocalDateTime(Object value) {
		if (value instanceof LocalDateTime ldt) {
			return ldt;
		}
		return ((Timestamp) value).toLocalDateTime();
	}

	/** EXPLAIN·문서용: 실제 실행되는 SQL */
	public static String myVotesSql(boolean withKeyset) {
		return BASE + (withKeyset ? KEYSET : "") + ORDER_AND_LIMIT;
	}

}
