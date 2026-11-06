package com.axcore.platform.review.repository;

import com.axcore.platform.review.domain.ProposalRevision;
import com.axcore.platform.review.domain.ProposalRevisionId;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/** 개정본은 만든 뒤 바꾸지 않으므로 저장·조회 메서드만 연다. */
public interface ProposalRevisionRepository
        extends Repository<ProposalRevision, ProposalRevisionId> {

    <S extends ProposalRevision> S saveAndFlush(S revision);

    Page<ProposalRevision> findByProposalItemIdOrderByRevisionAsc(
            long proposalItemId, Pageable pageable);

    Optional<ProposalRevision> findByProposalItemIdAndRevision(long proposalItemId, int revision);

    boolean existsByProposalItemIdAndRevision(long proposalItemId, int revision);
}
