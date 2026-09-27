package com.pickone.member.repository;

import com.pickone.member.domain.Member;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<Member, Long> {

	boolean existsByEmail(String email);

	boolean existsByNickname(String nickname);

	boolean existsByPhoneHmac(String phoneHmac);

	/** 이메일 로그인 대상. 소프트 삭제된 회원은 제외 */
	Optional<Member> findByEmailAndDeletedAtIsNull(String email);

}
