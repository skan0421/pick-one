package com.pickone.question.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 피드 SQL. 뷰어(:me) 기준 필터 다섯 가지를 모두 적용한다.
 *  1) 내가 쓴 글 제외
 *  2) 이미 투표한 글 제외         — vote(member_id, question_id) 유니크 인덱스
 *  3) 양방향 차단 제외            — member_block PK (blocker_id, blocked_id) 를 양쪽 방향으로 각각 탐색
 *  4) 지인 숨김                   — 작성자가 hide_from_contacts 를 켰고 hide_relation(owner=작성자, target=나) 이 있으면 제외.
 *                                   member 를 본문에서 JOIN 하면 옵티마이저가 조인 버퍼를 쓰며 정렬 인덱스를 버리므로 EXISTS 안으로 넣는다
 *  5) status = ACTIVE, deleted_at IS NULL
 * 인덱스: 상단 노출 단계는 idx_question_status_boosted_until(V3, range 후 소량 filesort),
 *         일반 단계는 idx_question_status_created_at 을 역순으로 읽어 filesort 없이 LIMIT 에서 멈춘다 (EXPLAIN 은 docs/api.md 4.2).
 */
public class QuestionQueryRepositoryImpl implements QuestionQueryRepository {

	private static final String VIEWER_FILTER = """
			  AND q.member_id <> :me
			  AND NOT EXISTS (SELECT 1 FROM vote v WHERE v.member_id = :me AND v.question_id = q.id)
			  AND NOT EXISTS (SELECT 1 FROM member_block b WHERE b.blocker_id = :me AND b.blocked_id = q.member_id)
			  AND NOT EXISTS (SELECT 1 FROM member_block b WHERE b.blocker_id = q.member_id AND b.blocked_id = :me)
			  AND NOT EXISTS (SELECT 1 FROM hide_relation h
			                  JOIN member a ON a.id = h.owner_id
			                  WHERE h.owner_id = q.member_id AND h.target_member_id = :me AND a.hide_from_contacts = b'1')
			""";

	private static final String KEYSET = """
			  AND (q.created_at < :cursorCreatedAt OR (q.created_at = :cursorCreatedAt AND q.id < :cursorId))
			""";

	private static final String ORDER_AND_LIMIT = """
			ORDER BY q.created_at DESC, q.id DESC
			LIMIT :limit
			""";

	private static final String BOOSTED_BASE = """
			SELECT q.id
			FROM question q
			WHERE q.status = 'ACTIVE' AND q.deleted_at IS NULL
			  AND q.boosted_until > :now
			""";

	private static final String NORMAL_BASE = """
			SELECT q.id
			FROM question q
			WHERE q.status = 'ACTIVE' AND q.deleted_at IS NULL
			  AND (q.boosted_until IS NULL OR q.boosted_until <= :now)
			""";

	private static final String HIDDEN_FROM_VIEWER = """
			SELECT CASE WHEN EXISTS (
			    SELECT 1 FROM member_block b WHERE b.blocker_id = :me AND b.blocked_id = :author
			) OR EXISTS (
			    SELECT 1 FROM member_block b WHERE b.blocker_id = :author AND b.blocked_id = :me
			) OR EXISTS (
			    SELECT 1 FROM hide_relation h
			    JOIN member a ON a.id = h.owner_id
			    WHERE h.owner_id = :author AND h.target_member_id = :me AND a.hide_from_contacts = b'1'
			) THEN 1 ELSE 0 END
			""";

	@PersistenceContext
	private EntityManager em;

	@Override
	public List<Long> findBoostedFeedIds(Long viewerId, LocalDateTime now, FeedKeyset after, int limit) {
		return feedIds(BOOSTED_BASE, viewerId, now, after, limit);
	}

	@Override
	public List<Long> findNormalFeedIds(Long viewerId, LocalDateTime now, FeedKeyset after, int limit) {
		return feedIds(NORMAL_BASE, viewerId, now, after, limit);
	}

	@Override
	public boolean isHiddenFromViewer(Long viewerId, Long authorId) {
		Object result = em.createNativeQuery(HIDDEN_FROM_VIEWER)
				.setParameter("me", viewerId)
				.setParameter("author", authorId)
				.getSingleResult();
		return ((Number) result).intValue() == 1;
	}

	@SuppressWarnings("unchecked")
	private List<Long> feedIds(String base, Long viewerId, LocalDateTime now, FeedKeyset after, int limit) {
		String sql = base + VIEWER_FILTER + (after == null ? "" : KEYSET) + ORDER_AND_LIMIT;
		Query query = em.createNativeQuery(sql)
				.setParameter("me", viewerId)
				.setParameter("now", now)
				.setParameter("limit", limit);
		if (after != null) {
			query.setParameter("cursorCreatedAt", after.createdAt()).setParameter("cursorId", after.id());
		}
		List<Object> rows = query.getResultList();
		return rows.stream().map(row -> ((Number) row).longValue()).toList();
	}

	/** EXPLAIN·문서용: 실제 실행되는 SQL 을 그대로 돌려준다 */
	public static String boostedFeedSql(boolean withKeyset) {
		return BOOSTED_BASE + VIEWER_FILTER + (withKeyset ? KEYSET : "") + ORDER_AND_LIMIT;
	}

	public static String normalFeedSql(boolean withKeyset) {
		return NORMAL_BASE + VIEWER_FILTER + (withKeyset ? KEYSET : "") + ORDER_AND_LIMIT;
	}

}
