package com.pickone.hide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pickone.hide.domain.HidePending;
import com.pickone.hide.domain.HideRelation;
import com.pickone.hide.repository.HidePendingRepository;
import com.pickone.hide.repository.HideRelationRepository;
import com.pickone.member.domain.Member;
import com.pickone.member.repository.MemberRepository;
import com.pickone.point.domain.PointWallet;
import com.pickone.point.repository.PointWalletRepository;
import com.pickone.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/** PointWallet / HidePending / HideRelation 매핑 검증 (컨텍스트 기동 = validate 통과) */
@IntegrationTest
@Transactional
class HideRepositoryTest {

	@Autowired
	MemberRepository memberRepository;

	@Autowired
	PointWalletRepository pointWalletRepository;

	@Autowired
	HidePendingRepository hidePendingRepository;

	@Autowired
	HideRelationRepository hideRelationRepository;

	@Test
	void 지갑은_회원_ID를_PK로_잔액_0으로_열린다() {
		Member member = newMember();

		PointWallet wallet = pointWalletRepository.saveAndFlush(PointWallet.open(member.getId()));

		assertThat(wallet.getMemberId()).isEqualTo(member.getId());
		assertThat(wallet.getBalance()).isZero();
		assertThat(wallet.getUpdatedAt()).isNotNull();
	}

	@Test
	void 숨김_대기와_숨김_관계를_저장하고_조회한다() {
		Member owner = newMember();
		Member target = newMember();
		String hmac = "a".repeat(64);

		hidePendingRepository.saveAndFlush(new HidePending(owner.getId(), hmac));
		hideRelationRepository.saveAndFlush(new HideRelation(owner.getId(), target.getId()));

		assertThat(hidePendingRepository.findAllByPhoneHmac(hmac)).extracting(HidePending::getOwnerId).containsExactly(owner.getId());
		assertThat(hideRelationRepository.findAllByIdTargetMemberId(target.getId()))
				.extracting(HideRelation::getOwnerId).containsExactly(owner.getId());
	}

	@Test
	void 자기_자신을_숨김_대상으로_넣으면_DB_CHECK가_막는다() {
		Member owner = newMember();

		assertThatThrownBy(() -> hideRelationRepository.saveAndFlush(new HideRelation(owner.getId(), owner.getId())))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	private Member newMember() {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		return memberRepository.saveAndFlush(Member.signupByEmail("h" + suffix + "@test.com", "hash", "숨김" + suffix));
	}

}
