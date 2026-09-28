package com.pickone.question.service;

import com.pickone.global.error.BusinessException;
import com.pickone.global.error.ErrorCode;
import com.pickone.global.paging.CursorCodec;
import com.pickone.global.paging.CursorPage;
import com.pickone.global.paging.KeysetCursor;
import com.pickone.member.domain.Member;
import com.pickone.member.repository.MemberRepository;
import com.pickone.question.domain.Question;
import com.pickone.question.domain.Question.OptionDraft;
import com.pickone.question.domain.QuestionType;
import com.pickone.question.dto.CreateQuestionRequest;
import com.pickone.question.dto.CreateQuestionRequest.OptionRequest;
import com.pickone.question.dto.FeedItemResponse;
import com.pickone.question.dto.MyQuestionResponse;
import com.pickone.question.dto.QuestionResponse;
import com.pickone.question.repository.QuestionQueryRepository.FeedKeyset;
import com.pickone.question.repository.QuestionRepository;
import com.pickone.upload.service.ImageUrlValidator;
import com.pickone.vote.domain.Vote;
import com.pickone.vote.repository.OptionCount;
import com.pickone.vote.repository.VoteRepository;
import com.pickone.vote.service.VoteResultCalculator;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class QuestionService {

	public static final int DEFAULT_PAGE_SIZE = 20;
	public static final int MAX_PAGE_SIZE = 50;
	private static final int TEXT_MIN_OPTIONS = 2;
	private static final int TEXT_MAX_OPTIONS = 4;
	private static final int IMAGE_OPTIONS = 2;

	private final QuestionRepository questionRepository;
	private final MemberRepository memberRepository;
	private final VoteRepository voteRepository;
	private final ImageUrlValidator imageUrlValidator;
	private final CursorCodec cursorCodec;

	// ---------- 등록 ----------

	@Transactional
	public QuestionResponse create(Long memberId, CreateQuestionRequest request) {
		Member author = memberRepository.findById(memberId)
				.filter(m -> !m.isDeleted())
				.orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
		List<OptionDraft> drafts = validateOptions(memberId, request.questionType(), request.options());

		Question question = questionRepository.save(Question.create(author, request.questionType(), request.content(), drafts));
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

	// ---------- 피드 ----------

	/**
	 * 상단 노출 단계(B) → 일반 단계(N) 순으로 채운다. 커서에 단계가 들어 있어 다음 페이지가 어디서 이어질지 안다.
	 * 쿼리 수: ID 조회 1~2회(단계 전환 시 2회) + 작성자·선택지 fetch join 1회 = 페이지당 2~3회.
	 */
	@Transactional(readOnly = true)
	public CursorPage<FeedItemResponse> feed(Long viewerId, String cursor, Integer size) {
		int pageSize = normalizeSize(size);
		LocalDateTime now = LocalDateTime.now();
		FeedCursor from = cursor == null ? FeedCursor.first() : cursorCodec.decode(cursor, FeedCursor.class);

		List<Long> ids = new ArrayList<>();
		FeedCursor next = null;
		boolean hasNext = false;

		if (from.isBoostedPhase()) {
			List<Long> boosted = questionRepository.findBoostedFeedIds(viewerId, now, from.keyset(), pageSize + 1);
			if (boosted.size() > pageSize) {
				ids.addAll(boosted.subList(0, pageSize));
				hasNext = true;
			}
			else {
				ids.addAll(boosted);
			}
		}
		if (!hasNext) {
			// 상단 노출 단계가 끝났으면(또는 처음부터 일반 단계면) 남은 자리를 일반 단계로 채운다
			int remaining = pageSize - ids.size();
			FeedKeyset keyset = from.isBoostedPhase() ? null : from.keyset();
			List<Long> normal = questionRepository.findNormalFeedIds(viewerId, now, keyset, remaining + 1);
			if (normal.size() > remaining) {
				ids.addAll(normal.subList(0, remaining));
				hasNext = true;
			}
			else {
				ids.addAll(normal);
			}
		}

		List<Question> questions = loadInOrder(ids);
		if (hasNext && !questions.isEmpty()) {
			Question last = questions.get(questions.size() - 1);
			next = new FeedCursor(last.isBoostedAt(now) ? FeedCursor.PHASE_BOOSTED : FeedCursor.PHASE_NORMAL,
					last.getCreatedAt(), last.getId());
		}
		List<FeedItemResponse> items = questions.stream().map(q -> FeedItemResponse.of(q, now)).toList();
		return CursorPage.of(items, next == null ? null : cursorCodec.encode(next), hasNext);
	}

	// ---------- 상세 ----------

	@Transactional(readOnly = true)
	public QuestionResponse detail(Long viewerId, Long questionId) {
		Question question = questionRepository.findWithAuthorAndOptionsById(questionId)
				.filter(q -> q.isOwnedBy(viewerId) || q.isVisible())
				.orElseThrow(() -> new BusinessException(ErrorCode.QUESTION_NOT_FOUND));
		// 차단·지인 숨김 관계면 존재 자체를 숨긴다 (404)
		if (!question.isOwnedBy(viewerId) && questionRepository.isHiddenFromViewer(viewerId, question.getAuthor().getId())) {
			throw new BusinessException(ErrorCode.QUESTION_NOT_FOUND);
		}
		// myVote / result 는 내가 투표했거나 내 고민일 때만 (투표 전 결과 노출로 인한 편향 방지)
		Optional<Vote> myVote = voteRepository.findByMemberIdAndQuestionId(viewerId, questionId);
		Long myOptionId = myVote.map(v -> v.getOption().getId()).orElse(null);
		VoteResultCalculator.Tally tally = null;
		if (question.isOwnedBy(viewerId) || myVote.isPresent()) {
			tally = VoteResultCalculator.tally(question.getOptions(), voteRepository.countByQuestion(questionId));
		}
		return QuestionResponse.of(question, viewerId, LocalDateTime.now(), myOptionId, tally);
	}

	// ---------- 내 고민 ----------

	@Transactional(readOnly = true)
	public CursorPage<MyQuestionResponse> myQuestions(Long memberId, String cursor, Integer size) {
		int pageSize = normalizeSize(size);
		Limit limit = Limit.of(pageSize + 1);
		List<Question> page = cursor == null
				? questionRepository.findMineFirstPage(memberId, limit)
				: decodeMyCursor(cursor, memberId, limit);

		boolean hasNext = page.size() > pageSize;
		List<Question> visible = hasNext ? page.subList(0, pageSize) : page;
		List<Question> questions = loadInOrder(visible.stream().map(Question::getId).toList());

		String next = null;
		if (hasNext) {
			Question last = questions.get(questions.size() - 1);
			next = cursorCodec.encode(new KeysetCursor(last.getCreatedAt(), last.getId()));
		}
		// 페이지 전체의 득표수를 한 쿼리로 집계한다 (항목 수와 무관하게 쿼리 1회)
		Map<Long, List<OptionCount>> countsByQuestion = questions.isEmpty() ? Map.of()
				: VoteResultCalculator.groupByQuestion(voteRepository.countByQuestions(questions.stream().map(Question::getId).toList()));
		List<MyQuestionResponse> items = questions.stream()
				.map(q -> MyQuestionResponse.of(q, VoteResultCalculator.tally(q.getOptions(), countsByQuestion.getOrDefault(q.getId(), List.of()))))
				.toList();
		return CursorPage.of(items, next, hasNext);
	}

	private List<Question> decodeMyCursor(String cursor, Long memberId, Limit limit) {
		KeysetCursor keyset = cursorCodec.decode(cursor, KeysetCursor.class);
		return questionRepository.findMineAfter(memberId, keyset.createdAt(), keyset.id(), limit);
	}

	// ---------- 삭제 ----------

	@Transactional
	public void delete(Long memberId, Long questionId) {
		Question question = questionRepository.findById(questionId)
				.filter(q -> !q.isDeleted())
				.orElseThrow(() -> new BusinessException(ErrorCode.QUESTION_NOT_FOUND));
		if (!question.isOwnedBy(memberId)) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
		question.delete();
	}

	// ---------- 공통 ----------

	/** ID 목록 순서를 유지한 채 작성자·선택지를 한 쿼리로 가져온다 */
	private List<Question> loadInOrder(List<Long> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		Map<Long, Question> byId = questionRepository.findAllWithAuthorAndOptionsByIdIn(ids).stream()
				.collect(Collectors.toMap(Question::getId, Function.identity()));
		return ids.stream().map(byId::get).filter(q -> q != null).toList();
	}

	private static int normalizeSize(Integer size) {
		if (size == null || size < 1) {
			return DEFAULT_PAGE_SIZE;
		}
		return Math.min(size, MAX_PAGE_SIZE);
	}

	/** 피드 커서: 단계(B/N) + 키셋 */
	public record FeedCursor(String p, LocalDateTime t, Long id) {

		static final String PHASE_BOOSTED = "B";
		static final String PHASE_NORMAL = "N";

		static FeedCursor first() {
			return new FeedCursor(PHASE_BOOSTED, null, null);
		}

		boolean isBoostedPhase() {
			return PHASE_BOOSTED.equals(p);
		}

		FeedKeyset keyset() {
			return t == null || id == null ? null : new FeedKeyset(t, id);
		}

	}

}
