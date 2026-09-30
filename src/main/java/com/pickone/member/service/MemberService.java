package com.pickone.member.service;

import com.pickone.global.error.BusinessException;
import com.pickone.global.error.ErrorCode;
import com.pickone.member.domain.Member;
import com.pickone.member.dto.MemberResponse;
import com.pickone.member.dto.UpdateMemberRequest;
import com.pickone.member.repository.MemberRepository;
import com.pickone.point.domain.PointWallet;
import com.pickone.point.repository.PointWalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberService {

	private final MemberRepository memberRepository;
	private final PointWalletRepository pointWalletRepository;

	@Transactional(readOnly = true)
	public MemberResponse getMe(Long memberId) {
		return toResponse(getActiveMember(memberId));
	}

	/**
	 * 내 정보 수정. 요청에 들어 있는 필드만 바꾼다 (docs/api.md 2.8). 바꿀 필드가 없으면 현재 정보를 그대로 돌려준다.
	 */
	@Transactional
	public MemberResponse update(Long memberId, UpdateMemberRequest request) {
		Member member = getActiveMember(memberId);
		if (request.nickname() != null) {
			changeNickname(member, request.nickname());
		}
		return toResponse(member);
	}

	/**
	 * 닉네임 변경. 같은 닉네임이면 그대로 두고, 다른 회원이 쓰면 409.
	 * 동시 변경으로 선검사를 통과해도 커밋 시 uk_member_nickname 위반이 409 로 번역된다.
	 */
	private void changeNickname(Member member, String nickname) {
		if (nickname.equals(member.getNickname())) {
			return;
		}
		if (memberRepository.existsByNickname(nickname)) {
			throw new BusinessException(ErrorCode.MEMBER_NICKNAME_DUPLICATE);
		}
		member.changeNickname(nickname);
	}

	/** pointBalance 는 지갑 잔액. 휴대폰 인증 전(PENDING_PHONE)에는 지갑이 없으므로 0 */
	private MemberResponse toResponse(Member member) {
		long pointBalance = pointWalletRepository.findById(member.getId())
				.map(PointWallet::getBalance)
				.orElse(0L);
		return MemberResponse.of(member, pointBalance);
	}

	private Member getActiveMember(Long memberId) {
		return memberRepository.findById(memberId)
				.filter(member -> !member.isDeleted())
				.orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
	}

}
