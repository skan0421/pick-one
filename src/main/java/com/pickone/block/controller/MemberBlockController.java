package com.pickone.block.controller;

import com.pickone.block.dto.BlockListResponse;
import com.pickone.block.dto.BlockResponse;
import com.pickone.block.service.MemberBlockService;
import com.pickone.global.security.LoginMemberId;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 차단·해제·목록 (docs/api.md 8.1~8.3). ACTIVE 회원 전용 */
@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
public class MemberBlockController {

	private final MemberBlockService memberBlockService;

	@PostMapping("/{id}/blocks")
	@ResponseStatus(HttpStatus.CREATED)
	public BlockResponse block(@LoginMemberId Long memberId, @PathVariable Long id) {
		return memberBlockService.block(memberId, id);
	}

	@DeleteMapping("/{id}/blocks")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void unblock(@LoginMemberId Long memberId, @PathVariable Long id) {
		memberBlockService.unblock(memberId, id);
	}

	@GetMapping("/me/blocks")
	public BlockListResponse list(@LoginMemberId Long memberId) {
		return memberBlockService.list(memberId);
	}

}
