package com.pickone.hide.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 지인 숨김 대기. owner 가 올린 연락처 중 아직 가입하지 않은 번호의 HMAC.
 * 그 번호가 가입해 휴대폰 인증을 마치면 hide_relation 으로 옮겨지고 이 행은 삭제된다.
 */
@Entity
@Table(name = "hide_pending")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HidePending {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "owner_id", nullable = false)
	private Long ownerId;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "phone_hmac", nullable = false, length = 64)
	private String phoneHmac;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	public HidePending(Long ownerId, String phoneHmac) {
		this.ownerId = ownerId;
		this.phoneHmac = phoneHmac;
	}

}
