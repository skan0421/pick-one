package com.pickone.question.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 선택지. (question_id, sort_order) 유니크, (id, question_id) 유니크(투표의 복합 FK 참조 대상).
 * TEXT 유형은 content, IMAGE 유형은 imageUrl 만 채운다. 개수·형식 규칙은 등록 서비스에서 검증한다.
 */
@Entity
@Table(name = "question_option")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuestionOption {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "question_id", nullable = false)
	private Question question;

	/** 1~4. 컬럼이 TINYINT 라 JDBC 타입을 명시해 int 로 매핑한다 (CHECK chk_question_option_sort_order) */
	@JdbcTypeCode(SqlTypes.TINYINT)
	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	@Column(name = "content", length = 20)
	private String content;

	@Column(name = "image_url", length = 500)
	private String imageUrl;

	QuestionOption(Question question, int sortOrder, String content, String imageUrl) {
		this.question = question;
		this.sortOrder = sortOrder;
		this.content = content;
		this.imageUrl = imageUrl;
	}

}
