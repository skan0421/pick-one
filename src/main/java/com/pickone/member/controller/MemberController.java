package com.pickone.member.controller;

import com.pickone.global.security.LoginMemberId;
import com.pickone.member.dto.MemberResponse;
import com.pickone.member.dto.UpdateMemberRequest;
import com.pickone.member.service.MemberService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "회원", description = "내 정보 조회·수정 (docs/api.md 2.7, 2.8)")
@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
public class MemberController {

	private final MemberService memberService;

	@Operation(summary = "내 정보")
	@GetMapping("/me")
	public MemberResponse getMe(@LoginMemberId Long memberId) {
		return memberService.getMe(memberId);
	}

	@Operation(summary = "내 정보 수정 (보낸 필드만 변경. 지금은 닉네임)")
	@PatchMapping("/me")
	public MemberResponse updateMe(@LoginMemberId Long memberId, @Valid @RequestBody UpdateMemberRequest request) {
		return memberService.update(memberId, request);
	}

}
