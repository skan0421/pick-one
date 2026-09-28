package com.pickone.block.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * 사용자 차단. PK 는 (blocker_id, blocked_id) 복합키, 자기 자신은 DB CHECK(chk_member_block_not_self) 가 막는다.
 * 차단 즉시 양쪽 피드에서 서로의 고민이 빠진다 (QuestionQueryRepositoryImpl 의 양방향 NOT EXISTS).
 * 등록은 MemberBlockService 가 INSERT ... ON DUPLICATE KEY UPDATE 로 멱등 처리하므로 이 엔티티는 조회·삭제에만 쓴다.
 */
@Entity
@Table(name = "member_block")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberBlock {

	@EmbeddedId
	private MemberBlockId id;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Embeddable
	@Getter
	@EqualsAndHashCode
	@NoArgsConstructor(access = AccessLevel.PROTECTED)
	public static class MemberBlockId implements Serializable {

		@Column(name = "blocker_id", nullable = false)
		private Long blockerId;

		@Column(name = "blocked_id", nullable = false)
		private Long blockedId;

		public MemberBlockId(Long blockerId, Long blockedId) {
			this.blockerId = blockerId;
			this.blockedId = blockedId;
		}

	}

}
