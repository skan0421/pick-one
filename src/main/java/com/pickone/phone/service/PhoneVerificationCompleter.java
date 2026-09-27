package com.pickone.phone.service;

import com.pickone.global.error.BusinessException;
import com.pickone.global.error.ErrorCode;
import com.pickone.hide.domain.HidePending;
import com.pickone.hide.domain.HideRelation;
import com.pickone.hide.repository.HidePendingRepository;
import com.pickone.hide.repository.HideRelationRepository;
import com.pickone.member.domain.Member;
import com.pickone.member.repository.MemberRepository;
import com.pickone.point.domain.PointWallet;
import com.pickone.point.repository.PointWalletRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인증 확인 성공 뒤의 DB 작업을 한 트랜잭션으로 묶는다 (docs/api.md 3.4).
 * 1) member: 휴대폰 암호화본·HMAC 저장, signup_status = ACTIVE
 * 2) point_wallet 생성 (잔액 0)
 * 3) hide_pending 에서 같은 phone_hmac 을 찾아 hide_relation 으로 옮기고 삭제
 * 같은 번호를 동시에 확인하면 커밋 시 uk_member_phone_hmac 위반이 나고 GlobalExceptionHandler 가 409 로 번역한다.
 * 토큰 발급은 이 트랜잭션 밖(호출자)에서 한다. 커밋이 실패했는데 토큰이 남는 일을 피하기 위해서다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PhoneVerificationCompleter {

	private final MemberRepository memberRepository;
	private final PointWalletRepository pointWalletRepository;
	private final HidePendingRepository hidePendingRepository;
	private final HideRelationRepository hideRelationRepository;

	@Transactional
	public Member complete(Long memberId, String phoneEncrypted, String phoneHmac) {
		Member member = memberRepository.findById(memberId)
				.orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
		if (member.isSignupCompleted()) {
			throw new BusinessException(ErrorCode.PHONE_ALREADY_VERIFIED);
		}
		// 다른 회원이 먼저 같은 번호를 인증한 경우. 동시 요청은 이 검사를 둘 다 통과할 수 있고, 그때는 커밋 시 유니크 제약이 막는다
		if (memberRepository.existsByPhoneHmac(phoneHmac)) {
			throw new BusinessException(ErrorCode.PHONE_ALREADY_REGISTERED);
		}

		member.completePhoneVerification(phoneEncrypted, phoneHmac);
		pointWalletRepository.save(PointWallet.open(member.getId()));
		int moved = movePendingHides(member.getId(), phoneHmac);

		// flush 를 여기서 해 유니크 위반이 이 메서드 안에서 드러나게 한다 (커밋 시점 예외도 같은 409 로 번역되지만 로그 위치가 명확해진다)
		memberRepository.flush();
		log.info("휴대폰 인증 트랜잭션 완료: memberId={}, hide_pending → hide_relation {}건", member.getId(), moved);
		return member;
	}

	private int movePendingHides(Long newMemberId, String phoneHmac) {
		List<HidePending> pendings = hidePendingRepository.findAllByPhoneHmac(phoneHmac);
		if (pendings.isEmpty()) {
			return 0;
		}
		int moved = 0;
		for (HidePending pending : pendings) {
			if (pending.getOwnerId().equals(newMemberId)) {
				continue; // 자기 번호를 자기 연락처에 올린 경우. CHECK 제약과도 충돌하므로 건너뛴다
			}
			hideRelationRepository.save(new HideRelation(pending.getOwnerId(), newMemberId));
			moved++;
		}
		hidePendingRepository.deleteAll(pendings);
		return moved;
	}

}
