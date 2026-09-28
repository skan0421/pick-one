package com.pickone.hide.controller;

import com.pickone.global.security.LoginMemberId;
import com.pickone.hide.dto.ContactsRequest;
import com.pickone.hide.dto.ContactsResponse;
import com.pickone.hide.dto.HideFromContactsRequest;
import com.pickone.hide.dto.HideFromContactsResponse;
import com.pickone.hide.service.ContactHideService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 지인에게 숨기기 (docs/api.md 7장). ACTIVE 회원 전용. 요청 본문(연락처)은 로그에 남기지 않는다 */
@Tag(name = "지인에게 숨기기", description = "연락처 업로드·숨기기 켜기/끄기 (docs/api.md 7장)")
@RestController
@RequestMapping("/api/v1/members/me")
@RequiredArgsConstructor
public class HideController {

	private final ContactHideService contactHideService;

	@Operation(summary = "연락처 업로드 (전체 교체, 최대 5,000건)")
	@PutMapping("/contacts")
	public ContactsResponse replaceContacts(@LoginMemberId Long memberId, @Valid @RequestBody ContactsRequest request) {
		return contactHideService.replaceContacts(memberId, request.phones());
	}

	@Operation(summary = "지인에게 숨기기 켜기/끄기")
	@PutMapping("/hide-from-contacts")
	public HideFromContactsResponse setHideFromContacts(@LoginMemberId Long memberId, @Valid @RequestBody HideFromContactsRequest request) {
		return contactHideService.setHideFromContacts(memberId, request.enabled());
	}

}
