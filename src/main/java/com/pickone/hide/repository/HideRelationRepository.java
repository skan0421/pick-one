package com.pickone.hide.repository;

import com.pickone.hide.domain.HideRelation;
import com.pickone.hide.domain.HideRelation.HideRelationId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HideRelationRepository extends JpaRepository<HideRelation, HideRelationId> {

	List<HideRelation> findAllByIdTargetMemberId(Long targetMemberId);

}
