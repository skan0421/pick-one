package com.pickone.vote.controller;

import com.pickone.global.security.LoginMemberId;
import com.pickone.vote.dto.VoteRequest;
import com.pickone.vote.dto.VoteResponse;
import com.pickone.vote.dto.VoteResultResponse;
import com.pickone.vote.service.VoteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 투표·결과 조회 (docs/api.md 5장). ACTIVE 회원 전용 */
@RestController
@RequestMapping("/api/v1/questions/{id}")
@RequiredArgsConstructor
public class VoteController {

	private final VoteService voteService;

	@PostMapping("/votes")
	@ResponseStatus(HttpStatus.CREATED)
	public VoteResponse vote(@LoginMemberId Long memberId, @PathVariable Long id, @Valid @RequestBody VoteRequest request) {
		return voteService.vote(memberId, id, request.optionId());
	}

	@GetMapping("/results")
	public VoteResultResponse results(@LoginMemberId Long memberId, @PathVariable Long id) {
		return voteService.results(memberId, id);
	}

}
