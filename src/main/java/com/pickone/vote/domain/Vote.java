package com.pickone.vote.domain;

import com.pickone.question.domain.Question;
import com.pickone.question.domain.QuestionOption;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * 투표. 스키마는 V1 의 vote 테이블.
 * - (member_id, question_id) 유니크 uk_vote_member_id_question_id 가 중복 투표를 막는다
 * - (option_id, question_id) 복합 FK fk_vote_option 이 선택지가 그 고민의 것임을 DB 에서 보장한다
 * 둘 다 애플리케이션에서 먼저 검사하지만 동시 요청에서는 DB 제약이 최종 방어선이고, GlobalExceptionHandler 가 에러 코드로 번역한다.
 */
@Entity
@Table(name = "vote")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Vote {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "question_id", nullable = false)
	private Question question;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "option_id", nullable = false)
	private QuestionOption option;

	@Column(name = "member_id", nullable = false)
	private Long memberId;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private Vote(Question question, QuestionOption option, Long memberId) {
		this.question = question;
		this.option = option;
		this.memberId = memberId;
	}

	public static Vote cast(Question question, QuestionOption option, Long memberId) {
		return new Vote(question, option, memberId);
	}

}
