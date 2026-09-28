package com.pickone.question.dto;

import com.pickone.question.domain.QuestionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 고민 등록 요청 (docs/api.md 4.1).
 * 여기서는 형식만 검증하고, 유형별 선택지 개수·필드 규칙은 서비스에서 api.md 의 전용 에러 코드로 검증한다.
 */
public record CreateQuestionRequest(
		@NotNull(message = "고민 유형을 선택해 주세요.")
		QuestionType questionType,

		@NotBlank(message = "고민 내용을 입력해 주세요.")
		@Size(max = 300, message = "고민 내용은 300자 이하여야 합니다.")
		String content,

		// 개수 규칙(TEXT 2~4 / IMAGE 2)은 서비스에서 QUESTION_OPTION_COUNT_INVALID 로 검증한다
		@NotNull(message = "선택지를 입력해 주세요.")
		List<@Valid OptionRequest> options
) {

	public record OptionRequest(
			@Size(max = 20, message = "선택지는 20자 이하여야 합니다.")
			String content,

			@Size(max = 500, message = "이미지 URL 은 500자 이하여야 합니다.")
			String imageUrl
	) {
	}

}
