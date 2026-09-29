package com.pickone.vote.repository;

import com.pickone.vote.domain.Vote;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VoteRepository extends JpaRepository<Vote, Long>, VoteQueryRepository {

	/** 내 투표 (uk_vote_member_id_question_id 인덱스) */
	Optional<Vote> findByMemberIdAndQuestionId(Long memberId, Long questionId);

	/** 결과 집계 (idx_vote_question_id_option_id) */
	@Query("SELECT new com.pickone.vote.repository.OptionCount(v.question.id, v.option.id, COUNT(v)) "
			+ "FROM Vote v WHERE v.question.id = :questionId GROUP BY v.question.id, v.option.id")
	List<OptionCount> countByQuestion(@Param("questionId") Long questionId);

	/** 여러 고민의 결과를 한 번에 집계 (내 고민 목록 한 페이지) */
	@Query("SELECT new com.pickone.vote.repository.OptionCount(v.question.id, v.option.id, COUNT(v)) "
			+ "FROM Vote v WHERE v.question.id IN :questionIds GROUP BY v.question.id, v.option.id")
	List<OptionCount> countByQuestions(@Param("questionIds") List<Long> questionIds);

}
