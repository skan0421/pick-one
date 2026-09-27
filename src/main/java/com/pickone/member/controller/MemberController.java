package com.pickone.member.controller;

import com.pickone.global.security.LoginMemberId;
import com.pickone.member.dto.MemberResponse;
import com.pickone.member.dto.UpdateMemberRequest;
import com.pickone.member.service.MemberService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
public class MemberController {

	private final MemberService memberService;

	@GetMapping("/me")
	public MemberResponse getMe(@LoginMemberId Long memberId) {
		return memberService.getMe(memberId);
	}

	@PatchMapping("/me")
	public MemberResponse updateMe(@LoginMemberId Long memberId, @Valid @RequestBody UpdateMemberRequest request) {
		return memberService.changeNickname(memberId, request);
	}

}
