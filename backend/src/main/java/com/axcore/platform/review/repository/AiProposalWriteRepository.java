package com.axcore.platform.review.repository;

import com.axcore.platform.review.domain.ProposalItem;
import com.axcore.platform.review.domain.ProposalOption;
import com.axcore.platform.review.domain.ProposalRevision;
import com.axcore.platform.review.dto.AiProposalRequest;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AiProposalWriteRepository {
    private final ProposalItemRepository items;
    private final ProposalRevisionRepository revisions;
    private final ProposalOptionRepository options;

    public long insert(long analysisRunId, AiProposalRequest proposal) {
        ProposalItem item =
                items.saveAndFlush(
                        ProposalItem.ai(
                                analysisRunId,
                                proposal.itemKind(),
                                proposal.payload(),
                                proposal.evidence(),
                                proposal.aiConfidence()));
        long id = item.getId();
        // 개정본 1의 생성 시각은 후보의 생성 시각(JPA Auditing이 저장할 때 채운 값)과 같게 맞춘다.
        Instant createdAt = item.getCreatedAt();
        // 선택지의 FK가 개정본을 가리키므로 개정본을 먼저 넣는다. 저장 뒤 검사 등록(CandidateCheckRepository, JdbcClient)이
        // 같은 트랜잭션에서 두 테이블을 SQL로 읽으므로 둘 다 flush한다.
        revisions.saveAndFlush(
                ProposalRevision.ai(
                        id,
                        proposal.payload(),
                        proposal.evidence(),
                        proposal.aiConfidence(),
                        createdAt));
        List<ProposalOption> proposalOptions =
                proposal.options().stream()
                        .map(
                                option ->
                                        ProposalOption.ai(
                                                id,
                                                option.optionNo(),
                                                option.payload(),
                                                option.evidence(),
                                                option.aiConfidence()))
                        .toList();
        options.saveAllAndFlush(proposalOptions);
        return id;
    }
}
