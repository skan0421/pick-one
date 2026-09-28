package com.pickone.block.repository;

import com.pickone.block.domain.MemberBlock;
import com.pickone.block.domain.MemberBlock.MemberBlockId;
import com.pickone.block.dto.BlockedMemberResponse;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberBlockRepository extends JpaRepository<MemberBlock, MemberBlockId> {

	/** 내 차단 목록 (닉네임 포함, 최신순) */
	@Query("SELECT new com.pickone.block.dto.BlockedMemberResponse(b.id.blockedId, m.nickname, b.createdAt) "
			+ "FROM MemberBlock b JOIN Member m ON m.id = b.id.blockedId "
			+ "WHERE b.id.blockerId = :blockerId ORDER BY b.createdAt DESC, b.id.blockedId DESC")
	List<BlockedMemberResponse> findAllByBlockerId(@Param("blockerId") Long blockerId);

	/** 없어도 0 을 돌려준다 (204 멱등) */
	@Modifying
	@Query("DELETE FROM MemberBlock b WHERE b.id.blockerId = :blockerId AND b.id.blockedId = :blockedId")
	int deleteByIds(@Param("blockerId") Long blockerId, @Param("blockedId") Long blockedId);

}
