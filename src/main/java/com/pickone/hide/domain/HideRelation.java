package com.pickone.hide.domain;

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
 * 지인 숨김 (가입한 지인). owner 의 고민을 target 에게 노출하지 않는다.
 * PK 는 (owner_id, target_member_id) 복합키. 자기 자신은 DB CHECK(chk_hide_relation_not_self) 가 막는다.
 */
@Entity
@Table(name = "hide_relation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HideRelation {

	@EmbeddedId
	private HideRelationId id;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	public HideRelation(Long ownerId, Long targetMemberId) {
		this.id = new HideRelationId(ownerId, targetMemberId);
	}

	public Long getOwnerId() {
		return id.getOwnerId();
	}

	public Long getTargetMemberId() {
		return id.getTargetMemberId();
	}

	@Embeddable
	@Getter
	@EqualsAndHashCode
	@NoArgsConstructor(access = AccessLevel.PROTECTED)
	public static class HideRelationId implements Serializable {

		@Column(name = "owner_id", nullable = false)
		private Long ownerId;

		@Column(name = "target_member_id", nullable = false)
		private Long targetMemberId;

		public HideRelationId(Long ownerId, Long targetMemberId) {
			this.ownerId = ownerId;
			this.targetMemberId = targetMemberId;
		}

	}

}
