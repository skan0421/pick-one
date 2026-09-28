package com.pickone.question.repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 피드 조회용 네이티브 쿼리 (docs/api.md 4.2).
 * 정렬은 "상단 노출 중인 고민 → 최신순" 이며, ORDER BY 에 계산식(boosted_until > now)을 넣으면 인덱스를 탈 수 없어
 * 두 단계로 나눈다. 각 단계는 (created_at, id) 키셋 커서로 넘긴다.
 */
public interface QuestionQueryRepository {

	/** 상단 노출 중인 고민 ID. 뷰어 기준 필터(내 글·투표한 글·차단·지인 숨김·ACTIVE) 적용 */
	List<Long> findBoostedFeedIds(Long viewerId, LocalDateTime now, FeedKeyset after, int limit);

	/** 상단 노출이 아닌 고민 ID. 같은 필터 적용 */
	List<Long> findNormalFeedIds(Long viewerId, LocalDateTime now, FeedKeyset after, int limit);

	/** 상세 조회 시 뷰어에게 보여도 되는 글인지 (양방향 차단, 지인 숨김) */
	boolean isHiddenFromViewer(Long viewerId, Long authorId);

	/** 키셋 커서. null 이면 첫 페이지 */
	record FeedKeyset(LocalDateTime createdAt, Long id) {
	}

}
