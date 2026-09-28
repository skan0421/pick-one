package com.pickone.question.domain;

import com.pickone.global.entity.BaseTimeEntity;
import com.pickone.member.domain.Member;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 고민. 스키마는 V1__init_schema.sql 의 question 테이블.
 * 선택지는 고민과 생명주기를 같이 하므로 cascade + orphanRemoval 로 관리한다.
 */
@Entity
@Table(name = "question")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Question extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "member_id", nullable = false)
	private Member author;

	@Enumerated(EnumType.STRING)
	@Column(name = "question_type", nullable = false, length = 10)
	private QuestionType questionType;

	@Column(name = "content", nullable = false, length = 300)
	private String content;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private QuestionStatus status;

	/** 포인트로 상단 노출 중이면 만료 시각, 아니면 NULL */
	@Column(name = "boosted_until")
	private LocalDateTime boostedUntil;

	@Column(name = "deleted_at")
	private LocalDateTime deletedAt;

	@OneToMany(mappedBy = "question", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("sortOrder ASC")
	private List<QuestionOption> options = new ArrayList<>();

	private Question(Member author, QuestionType questionType, String content) {
		this.author = author;
		this.questionType = questionType;
		this.content = content;
		this.status = QuestionStatus.ACTIVE;
	}

	/** 선택지 개수·형식 규칙은 호출자(서비스)가 검증한 뒤 넘긴다. sort_order 는 넘어온 순서대로 1부터 부여 */
	public static Question create(Member author, QuestionType questionType, String content, List<OptionDraft> drafts) {
		Question question = new Question(author, questionType, content);
		int order = 1;
		for (OptionDraft draft : drafts) {
			question.options.add(new QuestionOption(question, order++, draft.content(), draft.imageUrl()));
		}
		return question;
	}

	public boolean isOwnedBy(Long memberId) {
		return author.getId().equals(memberId);
	}

	public boolean isDeleted() {
		return deletedAt != null;
	}

	public boolean isVisible() {
		return status == QuestionStatus.ACTIVE && !isDeleted();
	}

	public boolean isClosed() {
		return status == QuestionStatus.CLOSED;
	}

	public boolean isBoostedAt(LocalDateTime now) {
		return boostedUntil != null && boostedUntil.isAfter(now);
	}

	/**
	 * 상단 노출 연장: 이미 노출 중이면 남은 시간에 이어 붙인다 (GREATEST(now, boosted_until) + duration).
	 * 컬럼이 DATETIME(6) 이라 마이크로초로 잘라 둔다. 그래야 응답값과 저장값이 같고, 멱등 재응답이 최초 응답과 일치한다.
	 */
	public void extendBoost(LocalDateTime now, Duration duration) {
		LocalDateTime base = isBoostedAt(now) ? boostedUntil : now;
		this.boostedUntil = base.plus(duration).truncatedTo(ChronoUnit.MICROS);
	}

	/** 신고 누적 자동 숨김. ACTIVE 일 때만 바꾸고, 바꿨으면 true (CLOSED 는 건드리지 않는다) */
	public boolean hideByReports() {
		if (status != QuestionStatus.ACTIVE) {
			return false;
		}
		this.status = QuestionStatus.HIDDEN;
		return true;
	}

	/** 소프트 삭제. 투표·원장은 남긴다 */
	public void delete() {
		this.deletedAt = LocalDateTime.now();
	}

	/** 등록 시 선택지 입력값. content 는 TEXT, imageUrl 은 IMAGE 유형에서만 쓴다 */
	public record OptionDraft(String content, String imageUrl) {
	}

}
