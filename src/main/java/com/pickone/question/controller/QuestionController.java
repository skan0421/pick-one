package com.pickone.question.controller;

import com.pickone.global.paging.CursorPage;
import com.pickone.global.security.LoginMemberId;
import com.pickone.question.dto.CreateQuestionRequest;
import com.pickone.question.dto.FeedItemResponse;
import com.pickone.question.dto.MyQuestionResponse;
import com.pickone.question.dto.QuestionResponse;
import com.pickone.question.service.QuestionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 모두 ACTIVE 회원 전용 (SecurityConfig 의 anyRequest 규칙) */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class QuestionController {

	private final QuestionService questionService;

	@PostMapping("/questions")
	@ResponseStatus(HttpStatus.CREATED)
	public QuestionResponse create(@LoginMemberId Long memberId, @Valid @RequestBody CreateQuestionRequest request) {
		return questionService.create(memberId, request);
	}

	@GetMapping("/questions/feed")
	public CursorPage<FeedItemResponse> feed(@LoginMemberId Long memberId,
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer size) {
		return questionService.feed(memberId, cursor, size);
	}

	@GetMapping("/questions/{id}")
	public QuestionResponse detail(@LoginMemberId Long memberId, @PathVariable Long id) {
		return questionService.detail(memberId, id);
	}

	@GetMapping("/members/me/questions")
	public CursorPage<MyQuestionResponse> myQuestions(@LoginMemberId Long memberId,
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer size) {
		return questionService.myQuestions(memberId, cursor, size);
	}

	@DeleteMapping("/questions/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@LoginMemberId Long memberId, @PathVariable Long id) {
		questionService.delete(memberId, id);
	}

}
