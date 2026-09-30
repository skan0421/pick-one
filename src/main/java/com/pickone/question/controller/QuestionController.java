package com.pickone.question.controller;

import com.pickone.global.idempotency.IdempotencyKeys;
import com.pickone.global.paging.CursorPage;
import com.pickone.global.security.LoginMemberId;
import com.pickone.question.dto.CreateQuestionRequest;
import com.pickone.question.dto.FeedItemResponse;
import com.pickone.question.dto.MyQuestionResponse;
import com.pickone.question.dto.QuestionResponse;
import com.pickone.question.service.QuestionService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 모두 ACTIVE 회원 전용 (SecurityConfig 의 anyRequest 규칙) */
@Tag(name = "고민", description = "고민 등록·피드·상세·내 목록·삭제 (docs/api.md 4장)")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class QuestionController {

	private final QuestionService questionService;

	/** Idempotency-Key 헤더는 선택. 같은 키의 재요청에도 처음과 같은 201 과 본문을 돌려준다 (docs/api.md 4.1) */
	@Operation(summary = "고민 등록 (Idempotency-Key 헤더 선택)")
	@PostMapping("/questions")
	@ResponseStatus(HttpStatus.CREATED)
	public QuestionResponse create(@LoginMemberId Long memberId, @Valid @RequestBody CreateQuestionRequest request,
			@RequestHeader(value = IdempotencyKeys.HEADER, required = false) String idempotencyKey) {
		return questionService.create(memberId, request, idempotencyKey);
	}

	@Operation(summary = "투표 피드 (커서, 상단 노출 우선)")
	@GetMapping("/questions/feed")
	public CursorPage<FeedItemResponse> feed(@LoginMemberId Long memberId,
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer size) {
		return questionService.feed(memberId, cursor, size);
	}

	@Operation(summary = "고민 상세 (투표했거나 내 고민이면 결과 포함)")
	@GetMapping("/questions/{id}")
	public QuestionResponse detail(@LoginMemberId Long memberId, @PathVariable Long id) {
		return questionService.detail(memberId, id);
	}

	@Operation(summary = "내 고민 목록 (커서)")
	@GetMapping("/members/me/questions")
	public CursorPage<MyQuestionResponse> myQuestions(@LoginMemberId Long memberId,
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer size) {
		return questionService.myQuestions(memberId, cursor, size);
	}

	@Operation(summary = "고민 삭제 (작성자만, 소프트 삭제)")
	@DeleteMapping("/questions/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@LoginMemberId Long memberId, @PathVariable Long id) {
		questionService.delete(memberId, id);
	}

}
