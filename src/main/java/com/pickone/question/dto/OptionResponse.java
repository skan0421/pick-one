package com.pickone.question.dto;

import com.pickone.question.domain.QuestionOption;

/** 선택지. TEXT 유형은 imageUrl 이, IMAGE 유형은 content 가 null 이며 JSON 에서 생략된다 */
public record OptionResponse(Long id, int sortOrder, String content, String imageUrl) {

	public static OptionResponse from(QuestionOption option) {
		return new OptionResponse(option.getId(), option.getSortOrder(), option.getContent(), option.getImageUrl());
	}

}
