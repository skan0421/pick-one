package com.pickone.member.service;

import com.pickone.global.error.BusinessException;
import com.pickone.global.error.ErrorCode;
import com.pickone.member.domain.Member;
import com.pickone.member.dto.MemberResponse;
import com.pickone.member.dto.UpdateMemberRequest;
import com.pickone.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberService {

	private final MemberRepository memberRepository;

	@Transactional(readOnly = true)
	public MemberResponse getMe(Long memberId) {
		return MemberResponse.from(getActiveMember(memberId));
	}

	/**
	 * 닉네임 변경. 같은 닉네임이면 그대로 두고, 다른 회원이 쓰면 409.
	 * 동시 변경으로 선검사를 통과해도 커밋 시 uk_member_nickname 위반이 409 로 번역된다.
	 */
	@Transactional
	public MemberResponse changeNickname(Long memberId, UpdateMemberRequest request) {
		Member member = getActiveMember(memberId);
		String nickname = request.nickname();

		if (!nickname.equals(member.getNickname())) {
			if (memberRepository.existsByNickname(nickname)) {
				throw new BusinessException(ErrorCode.MEMBER_NICKNAME_DUPLICATE);
			}
			member.changeNickname(nickname);
		}
		return MemberResponse.from(member);
	}

	private Member getActiveMember(Long memberId) {
		return memberRepository.findById(memberId)
				.filter(member -> !member.isDeleted())
				.orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
	}

}
