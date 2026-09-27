package com.pickone.member;

import static org.assertj.core.api.Assertions.assertThat;

import com.pickone.TestcontainersConfiguration;
import com.pickone.member.domain.Member;
import com.pickone.member.domain.MemberStatus;
import com.pickone.member.domain.SignupStatus;
import com.pickone.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * Member 엔티티 매핑 검증. 컨텍스트가 뜨는 것 자체가 ddl-auto=validate 통과를 뜻하고,
 * 여기서는 저장·조회가 실제 컬럼과 맞는지 확인한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class MemberRepositoryTest {

	@Autowired
	MemberRepository memberRepository;

	@Test
	void 이메일_가입_회원을_저장하면_PENDING_PHONE_상태로_시작한다() {
		Member saved = memberRepository.save(Member.signupByEmail("repo@test.com", "hash", "레포테스트"));

		Member found = memberRepository.findById(saved.getId()).orElseThrow();
		assertThat(found.getEmail()).isEqualTo("repo@test.com");
		assertThat(found.getSignupStatus()).isEqualTo(SignupStatus.PENDING_PHONE);
		assertThat(found.getStatus()).isEqualTo(MemberStatus.ACTIVE);
		assertThat(found.isHideFromContacts()).isFalse();
		assertThat(found.getPhoneHmac()).isNull();
		assertThat(found.getCreatedAt()).isNotNull();
		assertThat(found.getUpdatedAt()).isNotNull();
	}

	@Test
	void 이메일과_닉네임_존재_여부를_조회한다() {
		memberRepository.save(Member.signupByEmail("exists@test.com", "hash", "존재함"));

		assertThat(memberRepository.existsByEmail("exists@test.com")).isTrue();
		assertThat(memberRepository.existsByEmail("none@test.com")).isFalse();
		assertThat(memberRepository.existsByNickname("존재함")).isTrue();
		assertThat(memberRepository.findByEmailAndDeletedAtIsNull("exists@test.com")).isPresent();
	}

}
