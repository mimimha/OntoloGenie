package com.axcore.platform.review.service;

import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.review.domain.ReviewDecision;
import com.axcore.platform.review.dto.ProposalDecisionRequest;
import com.axcore.platform.review.repository.ReviewDecisionRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
@RequiredArgsConstructor
public class ProposalDecisionHistoryService {
    private final ProposalDecisionValidator validator;
    private final ReviewDecisionRepository decisions;
    private final Clock clock;

    /** 호출자는 같은 트랜잭션에서 후보 상태 변경·반영·감사를 마쳐야 한다. 결정 ID와 검증에 사용한 검사 ID를 반환한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public RecordedDecision recordValidated(
            @Min(1) long proposalId,
            @NotNull @Valid ProposalDecisionRequest request,
            LoginAccount account) {
        var validated = validator.validate(proposalId, request, account);
        ReviewDecision decision =
                ReviewDecision.record(
                        validated.item(),
                        request.decision(),
                        request.selectedOptionId(),
                        request.reason(),
                        account.id(),
                        Instant.now(clock));
        // 호출자가 같은 트랜잭션에서 이 행을 SQL(ProposalDecisionAuditRepository)로 다시 읽으므로 바로 DB에 쓴다.
        long decisionId = decisions.saveAndFlush(decision).getId();
        return new RecordedDecision(decisionId, validated.basisCheckRunId());
    }

    public record RecordedDecision(long decisionId, Long basisCheckRunId) {}
}
