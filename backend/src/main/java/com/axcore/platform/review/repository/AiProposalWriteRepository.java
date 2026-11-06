package com.axcore.platform.review.repository;

import com.axcore.platform.review.domain.ProposalOption;
import com.axcore.platform.review.domain.ProposalRevision;
import com.axcore.platform.review.dto.AiProposalRequest;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
@RequiredArgsConstructor
public class AiProposalWriteRepository {
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final ProposalRevisionRepository revisions;
    private final ProposalOptionRepository options;

    public long insert(long analysisRunId, AiProposalRequest proposal, Instant now) {
        String payload = json.writeValueAsString(proposal.payload());
        String evidence = json.writeValueAsString(proposal.evidence());
        long id =
                jdbc.sql(
                                """
                insert into proposal_item(analysis_run_id, origin, item_kind, revision, payload, evidence,
                    ai_confidence, review_status, apply_status, created_at, updated_at)
                values (:analysis, 'AI', :kind, 1, cast(:payload as jsonb), cast(:evidence as jsonb),
                    :confidence, 'PENDING', 'NOT_APPLIED', :now, :now) returning id
                """)
                        .param("analysis", analysisRunId)
                        .param("kind", proposal.itemKind())
                        .param("payload", payload)
                        .param("evidence", evidence)
                        .param("confidence", proposal.aiConfidence(), Types.NUMERIC)
                        .param("now", Timestamp.from(now))
                        .query(Long.class)
                        .single();
        // 후보 행은 위 SQL로 이미 들어갔고 영속성 컨텍스트에는 없다. 개정본·선택지는 그 ID만 참조하므로 앞선 flush가 필요 없다.
        // 선택지의 FK가 개정본을 가리키므로 개정본을 먼저 넣는다. 저장 뒤 검사 등록(CandidateCheckRepository, JdbcClient)이
        // 같은 트랜잭션에서 두 테이블을 SQL로 읽으므로 둘 다 flush한다.
        revisions.saveAndFlush(
                ProposalRevision.ai(
                        id, proposal.payload(), proposal.evidence(), proposal.aiConfidence(), now));
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
