package com.pickone.member.domain;

import com.pickone.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 회원. 스키마는 V1__init_schema.sql + V2__social_login_and_signup_status.sql 을 따른다.
 * 휴대폰 번호는 원본을 저장하지 않고 AES 암호화본(phoneEncrypted)과 HMAC(phoneHmac)만 둔다.
 */
@Entity
@Table(name = "member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 소셜 가입자는 제공자가 이메일을 주지 않으면 NULL */
	@Column(name = "email", length = 100)
	private String email;

	/** BCrypt 해시. 소셜 가입자는 NULL */
	@Column(name = "password_hash", length = 255)
	private String passwordHash;

	@Column(name = "nickname", nullable = false, length = 30)
	private String nickname;

	/** AES-GCM 암호화본. 휴대폰 인증 전 NULL */
	@Column(name = "phone_encrypted", length = 255)
	private String phoneEncrypted;

	/** HMAC-SHA256 (검색/매칭 전용). 컬럼이 CHAR(64) 라 JDBC 타입을 CHAR 로 명시 */
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "phone_hmac", length = 64)
	private String phoneHmac;

	/** 컬럼이 BIT(1). Hibernate 의 MariaDB 기본 boolean 매핑도 BIT 지만 의도를 드러내기 위해 명시 */
	@JdbcTypeCode(SqlTypes.BIT)
	@Column(name = "hide_from_contacts", nullable = false)
	private boolean hideFromContacts;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private MemberStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "signup_status", nullable = false, length = 20)
	private SignupStatus signupStatus;

	@Column(name = "deleted_at")
	private LocalDateTime deletedAt;

	private Member(String email, String passwordHash, String nickname) {
		this.email = email;
		this.passwordHash = passwordHash;
		this.nickname = nickname;
		this.hideFromContacts = false;
		this.status = MemberStatus.ACTIVE;
		this.signupStatus = SignupStatus.PENDING_PHONE;
	}

	/** 이메일 가입. 휴대폰 인증 전이므로 PENDING_PHONE 으로 시작한다 */
	public static Member signupByEmail(String email, String passwordHash, String nickname) {
		return new Member(email, passwordHash, nickname);
	}

	public void changeNickname(String nickname) {
		this.nickname = nickname;
	}

	public boolean isSignupCompleted() {
		return this.signupStatus == SignupStatus.ACTIVE;
	}

	public boolean isSuspended() {
		return this.status == MemberStatus.SUSPENDED;
	}

	public boolean isDeleted() {
		return this.deletedAt != null;
	}

	public boolean hasPassword() {
		return this.passwordHash != null;
	}

}
