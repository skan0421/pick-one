package com.pickone.question.service;

import com.pickone.global.error.BusinessException;
import com.pickone.global.error.ErrorCode;
import com.pickone.member.domain.Member;
import com.pickone.member.repository.MemberRepository;
import com.pickone.question.domain.Question;
import com.pickone.question.domain.Question.OptionDraft;
import com.pickone.question.domain.QuestionType;
import com.pickone.question.dto.CreateQuestionRequest;
import com.pickone.question.dto.CreateQuestionRequest.OptionRequest;
import com.pickone.question.dto.QuestionResponse;
import com.pickone.question.repository.QuestionRepository;
import com.pickone.upload.service.ImageUrlValidator;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고민 등록 1건의 DB 트랜잭션 (docs/api.md 4.1). 상단 노출(BoostTransaction)과 같은 구조다.
 *
 * Idempotency-Key 가 있을 때
 * 1. question 에서 idempotency_key 조회. 있으면 같은 회원·같은 내용일 때만 그 고민으로 재응답(등록 없음), 아니면 IDEMPOTENCY_KEY_CONFLICT
 * 2. 없으면 검증 후 INSERT (idempotency_key, request_hash 포함. IDENTITY 라 즉시 실행)
 *
 * 같은 키로 동시에 두 요청이 오면: 둘 다 1단계를 통과하지만 INSERT 에서 진 쪽이 유니크 대기 → 이긴 쪽 커밋 후
 * uk_question_idempotency_key 위반. OptimisticRetryExecutor 가 이 위반을 감지해 이 메서드를 한 번 더 실행하고,
 * 그때는 1단계에서 이긴 쪽의 고민을 찾아 같은 응답을 돌려준다. 고민은 하나만 등록된다.
 *
 * 키가 없으면 조회 없이 등록만 한다 (키를 보내지 않는 클라이언트는 전과 같이 동작).
 */
@Component
@RequiredArgsConstructor
public class QuestionCreateTransaction {

	private static final int TEXT_MIN_OPTIONS = 2;
	private static final int TEXT_MAX_OPTIONS = 4;
	private static final int IMAGE_OPTIONS = 2;

	private final QuestionRepository questionRepository;
	private final MemberRepository memberRepository;
	private final ImageUrlValidator imageUrlValidator;

	/** idempotencyKey 는 검증을 마친 값이거나 null(헤더 없음) */
	@Transactional
	public QuestionResponse execute(Long memberId, CreateQuestionRequest request, String idempotencyKey) {
		String requestHash = null;
		if (idempotencyKey != null) {
			requestHash = QuestionRequestHash.of(request);
			Optional<Question> processed = questionRepository.findByIdempotencyKey(idempotencyKey);
			if (processed.isPresent()) {
				return replay(processed.get(), memberId, requestHash);
			}
		}

		Member author = memberRepository.findById(memberId)
				.filter(m -> !m.isDeleted())
				.orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
		List<OptionDraft> drafts = validateOptions(memberId, request.questionType(), request.options());

		Question question = questionRepository.save(
				Question.create(author, request.questionType(), request.content(), drafts, idempotencyKey, requestHash));
		return QuestionResponse.of(question, memberId, LocalDateTime.now());
	}

	/**
	 * 이미 처리된 키: 처음 등록한 고민을 그대로 돌려준다. 다른 회원의 키이거나 내용이 다르면 거절한다
	 * (키는 전역 유니크라 타인의 키 재사용도 막는다).
	 * 고민의 지금 상태를 돌려주므로, 그 사이 상단 노출을 썼거나 삭제했다면 그 값이 반영된다.
	 */
	private QuestionResponse replay(Question question, Long memberId, String requestHash) {
		if (!question.isCreatedBy(memberId, requestHash)) {
			throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
		}
		return QuestionResponse.of(question, memberId, LocalDateTime.now());
	}

	/**
	 * 유형별 선택지 규칙 (docs/api.md 4.1, erd.md 설계 메모). DB 는 개수를 강제하지 않으므로 여기가 유일한 검증 지점.
	 * 사진형의 imageUrl 은 개수·형식 검사를 모두 통과한 뒤 본인이 발급받아 업로드한 주소인지 확인한다 (4.6, HEAD 포함)
	 */
	private List<OptionDraft> validateOptions(Long memberId, QuestionType type, List<OptionRequest> options) {
		int count = options.size();
		if (type == QuestionType.TEXT && (count < TEXT_MIN_OPTIONS || count > TEXT_MAX_OPTIONS)) {
			throw new BusinessException(ErrorCode.QUESTION_OPTION_COUNT_INVALID, "텍스트형 선택지는 2~4개여야 합니다.");
		}
		if (type == QuestionType.IMAGE && count != IMAGE_OPTIONS) {
			throw new BusinessException(ErrorCode.QUESTION_OPTION_COUNT_INVALID, "사진형 선택지는 정확히 2개여야 합니다.");
		}

		List<OptionDraft> drafts = new ArrayList<>();
		for (OptionRequest option : options) {
			boolean hasContent = option.content() != null && !option.content().isBlank();
			boolean hasImage = option.imageUrl() != null && !option.imageUrl().isBlank();
			if (type == QuestionType.TEXT) {
				if (!hasContent || hasImage) {
					throw new BusinessException(ErrorCode.QUESTION_OPTION_TYPE_MISMATCH, "텍스트형 선택지는 content 만 입력합니다.");
				}
				drafts.add(new OptionDraft(option.content().trim(), null));
			}
			else {
				if (!hasImage || hasContent) {
					throw new BusinessException(ErrorCode.QUESTION_OPTION_TYPE_MISMATCH, "사진형 선택지는 imageUrl 만 입력합니다.");
				}
				drafts.add(new OptionDraft(null, option.imageUrl().trim()));
			}
		}
		if (type == QuestionType.IMAGE) {
			drafts = drafts.stream()
					.map(d -> new OptionDraft(null, imageUrlValidator.validateOwned(memberId, d.imageUrl())))
					.toList();
		}
		return drafts;
	}

}
