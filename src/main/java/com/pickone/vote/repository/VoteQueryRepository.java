package com.pickone.vote.repository;

import com.pickone.global.paging.KeysetCursor;
import java.time.LocalDateTime;
import java.util.List;

/** 내가 투표한 고민 목록의 ID 단계 조회 (네이티브 SQL, VoteQueryRepositoryImpl) */
public interface VoteQueryRepository {

	/** 최신 투표순. after 가 null 이면 첫 페이지. limit 은 size + 1 (hasNext 판정) */
	List<MyVoteRow> findMyVotes(Long memberId, KeysetCursor after, int limit);

	record MyVoteRow(Long voteId, Long questionId, Long optionId, LocalDateTime votedAt) {
	}

}
