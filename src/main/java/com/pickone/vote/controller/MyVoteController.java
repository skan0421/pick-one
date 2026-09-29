package com.pickone.vote.controller;

import com.pickone.global.paging.CursorPage;
import com.pickone.global.security.LoginMemberId;
import com.pickone.vote.dto.MyVoteItemResponse;
import com.pickone.vote.service.VoteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 내가 투표한 고민 목록 (docs/api.md 5.3). ACTIVE 회원 전용 */
@Tag(name = "투표", description = "투표하기·결과 조회·내가 투표한 고민 (docs/api.md 5장)")
@RestController
@RequiredArgsConstructor
public class MyVoteController {

	private final VoteService voteService;

	@Operation(summary = "내가 투표한 고민 목록 (커서, 최신 투표순)")
	@GetMapping("/api/v1/members/me/votes")
	public CursorPage<MyVoteItemResponse> myVotes(@LoginMemberId Long memberId,
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer size) {
		return voteService.myVotes(memberId, cursor, size);
	}

}
