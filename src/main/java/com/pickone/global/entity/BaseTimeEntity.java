package com.pickone.global.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.LocalDateTime;
import lombok.Getter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * created_at / updated_at 공통 컬럼.
 * 값은 Hibernate 가 INSERT / UPDATE 시점에 채운다.
 */
@Getter
@MappedSuperclass
public abstract class BaseTimeEntity {

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

}
