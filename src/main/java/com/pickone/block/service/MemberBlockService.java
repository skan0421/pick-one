package com.pickone.block.service;

import com.pickone.block.dto.BlockListResponse;
import com.pickone.block.dto.BlockResponse;
import com.pickone.block.repository.MemberBlockRepository;
import com.pickone.global.error.BusinessException;
import com.pickone.global.error.ErrorCode;
import com.pickone.member.repository.MemberRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 차단 (docs/api.md 8.1~8.3).
 * 차단은 멱등이다: 이미 차단한 상대를 다시 차단해도 201 이고 최초 차단 시각을 돌려준다.
 * "존재 확인 → INSERT" 는 동시 요청에서 PK 위반(500)이 날 수 있어 INSERT ... ON DUPLICATE KEY UPDATE 한 문장으로 처리한다.
 * (JdbcTemplate 은 같은 트랜잭션의 커넥션을 쓴다)
 * 그 뒤 created_at 은 FOR UPDATE 로 읽는다. 동시 요청에서 진 쪽은 회원 조회 시점의 REPEATABLE READ 스냅샷을 갖고 있어
 * 이긴 쪽이 막 커밋한 행이 일반 SELECT 에는 보이지 않기 때문이다 (docs/troubleshooting.md 10). 행 락은 UPSERT 가 이미 잡고 있다.
 */
@Service
@RequiredArgsConstructor
public class MemberBlockService {

	private static final String UPSERT = "INSERT INTO member_block (blocker_id, blocked_id, created_at) VALUES (?, ?, NOW(6)) "
			+ "ON DUPLICATE KEY UPDATE created_at = created_at";
	private static final String CREATED_AT = "SELECT created_at FROM member_block WHERE blocker_id = ? AND blocked_id = ? FOR UPDATE";

	private final MemberBlockRepository memberBlockRepository;
	private final MemberRepository memberRepository;
	private final JdbcTemplate jdbc;

	@Transactional
	public BlockResponse block(Long memberId, Long targetId) {
		if (memberId.equals(targetId)) {
			throw new BusinessException(ErrorCode.BLOCK_SELF);
		}
		memberRepository.findById(targetId)
				.filter(m -> !m.isDeleted())
				.orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

		jdbc.update(UPSERT, memberId, targetId);
		LocalDateTime createdAt = jdbc.queryForObject(CREATED_AT, LocalDateTime.class, memberId, targetId);
		return new BlockResponse(targetId, createdAt);
	}

	/** 차단 관계가 없어도 204 */
	@Transactional
	public void unblock(Long memberId, Long targetId) {
		memberBlockRepository.deleteByIds(memberId, targetId);
	}

	@Transactional(readOnly = true)
	public BlockListResponse list(Long memberId) {
		return new BlockListResponse(memberBlockRepository.findAllByBlockerId(memberId));
	}

}
