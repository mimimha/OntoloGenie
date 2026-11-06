package com.axcore.platform.review.repository;

import com.axcore.platform.review.domain.ReviewDecision;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewDecisionRepository extends JpaRepository<ReviewDecision, Long> {

    /** 결정 이력은 최근 결정부터, 같은 시각이면 나중에 저장한 것부터 보여 준다. */
    Page<ReviewDecision> findByProposalItemIdOrderByDecidedAtDescIdDesc(
            long proposalItemId, Pageable pageable);
}
