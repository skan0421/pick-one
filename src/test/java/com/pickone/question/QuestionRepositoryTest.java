package com.pickone.question;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pickone.member.domain.Member;
import com.pickone.member.repository.MemberRepository;
import com.pickone.question.domain.Question;
import com.pickone.question.domain.Question.OptionDraft;
import com.pickone.question.domain.QuestionOption;
import com.pickone.question.domain.QuestionStatus;
import com.pickone.question.domain.QuestionType;
import com.pickone.question.repository.QuestionRepository;
import com.pickone.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/** Question / QuestionOption 매핑 검증 (컨텍스트 기동 = validate 통과, sort_order TINYINT 매핑 포함) */
@IntegrationTest
@Transactional
class QuestionRepositoryTest {

	@Autowired MemberRepository memberRepository;
	@Autowired QuestionRepository questionRepository;
	@Autowired EntityManager em;

	@Test
	void 고민을_저장하면_선택지가_순서대로_함께_저장된다() {
		Member author = newMember();
		Question question = Question.create(author, QuestionType.TEXT, "점심 뭐 먹지",
				List.of(new OptionDraft("김치찌개", null), new OptionDraft("된장찌개", null), new OptionDraft("라면", null)));

		questionRepository.saveAndFlush(question);
		em.clear();

		Question found = questionRepository.findWithAuthorAndOptionsById(question.getId()).orElseThrow();
		assertThat(found.getStatus()).isEqualTo(QuestionStatus.ACTIVE);
		assertThat(found.getAuthor().getId()).isEqualTo(author.getId());
		assertThat(found.getOptions()).extracting(QuestionOption::getSortOrder).containsExactly(1, 2, 3);
		assertThat(found.getOptions()).extracting(QuestionOption::getContent).containsExactly("김치찌개", "된장찌개", "라면");
		assertThat(found.getCreatedAt()).isNotNull();
	}

	@Test
	void 소프트_삭제하면_deleted_at이_채워진다() {
		Question question = questionRepository.saveAndFlush(Question.create(newMember(), QuestionType.IMAGE, "뭐 입지",
				List.of(new OptionDraft(null, "https://img/a.jpg"), new OptionDraft(null, "https://img/b.jpg"))));

		question.delete();
		questionRepository.flush();

		assertThat(question.isDeleted()).isTrue();
		assertThat(question.isVisible()).isFalse();
	}

	@Test
	void sort_order가_범위를_벗어나면_DB_CHECK가_막는다() {
		Member author = newMember();
		List<OptionDraft> five = List.of(new OptionDraft("a", null), new OptionDraft("b", null), new OptionDraft("c", null),
				new OptionDraft("d", null), new OptionDraft("e", null));

		assertThatThrownBy(() -> questionRepository.saveAndFlush(Question.create(author, QuestionType.TEXT, "다섯 개", five)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	private Member newMember() {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		return memberRepository.saveAndFlush(Member.signupByEmail("q" + suffix + "@test.com", "hash", "질문" + suffix));
	}

}
