package com.axcore.platform.review.service;

import com.axcore.platform.check.domain.CandidateCheckTarget;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.review.domain.ProposalItem;
import com.axcore.platform.review.dto.LatestCheckResponse;
import com.axcore.platform.review.dto.ProposalDecisionResponse;
import com.axcore.platform.review.dto.ProposalOptionResponse;
import com.axcore.platform.review.dto.ProposalResponse;
import com.axcore.platform.review.dto.ProposalRevisionResponse;
import com.axcore.platform.review.dto.ProposalSearchRequest;
import com.axcore.platform.review.repository.ProposalItemRepository;
import com.axcore.platform.review.repository.ProposalItemSpecifications;
import com.axcore.platform.review.repository.ProposalLatestCheckRepository;
import com.axcore.platform.review.repository.ProposalOptionRepository;
import com.axcore.platform.review.repository.ProposalRevisionRepository;
import com.axcore.platform.review.repository.ReviewDecisionRepository;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProposalService {

    private final ProposalItemRepository proposalItemRepository;
    private final ProposalLatestCheckRepository proposalLatestCheckRepository;
    private final ProposalOptionRepository proposalOptionRepository;
    private final ProposalRevisionRepository proposalRevisionRepository;
    private final ReviewDecisionRepository reviewDecisionRepository;

    public ProposalResponse getProposal(long id) {
        ProposalItem proposal =
                proposalItemRepository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                CommonErrorCode.RESOURCE_NOT_FOUND,
                                                Map.of("resource", "proposal_item", "id", id)));

        Map<Long, LatestCheckResponse> latestChecks = loadLatestChecks(List.of(proposal));

        return toResponse(proposal, latestChecks.get(proposal.getId()));
    }

    public Page<ProposalResponse> search(ProposalSearchRequest request, Pageable pageable) {
        return search(request, pageable, Specification.unrestricted());
    }

    public Page<ProposalResponse> searchByAnalysis(
            long analysisRunId, ProposalSearchRequest request, Pageable pageable) {
        return search(
                request,
                pageable,
                (root, query, builder) -> builder.equal(root.get("analysisRunId"), analysisRunId));
    }

    private Page<ProposalResponse> search(
            ProposalSearchRequest request, Pageable pageable, Specification<ProposalItem> scope) {
        Page<ProposalItem> proposals =
                proposalItemRepository.findAll(
                        ProposalItemSpecifications.byFilters(
                                        request.origin(),
                                        request.itemKind(),
                                        request.reviewStatus(),
                                        request.applyStatus())
                                .and(scope),
                        pageable);

        Map<Long, LatestCheckResponse> latestChecks = loadLatestChecks(proposals.getContent());

        return proposals.map(proposal -> toResponse(proposal, latestChecks.get(proposal.getId())));
    }

    public List<ProposalOptionResponse> getOptions(long id, Integer revision) {
        ProposalItem proposal =
                proposalItemRepository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                CommonErrorCode.RESOURCE_NOT_FOUND,
                                                Map.of("resource", "proposal_item", "id", id)));

        int targetRevision = revision == null ? proposal.getRevision() : revision;
        if (!proposalRevisionRepository.existsByProposalItemIdAndRevision(id, targetRevision)) {
            throw new BusinessException(
                    CommonErrorCode.RESOURCE_NOT_FOUND,
                    Map.of(
                            "resource", "proposal_revision",
                            "proposalItemId", id,
                            "revision", targetRevision));
        }

        return proposalOptionRepository
                .findByProposalItemIdAndRevisionOrderByOptionNo(id, targetRevision)
                .stream()
                .map(ProposalOptionResponse::from)
                .toList();
    }

    public Page<ProposalRevisionResponse> getRevisions(long id, Pageable pageable) {
        if (!proposalItemRepository.existsById(id)) {
            throw new BusinessException(
                    CommonErrorCode.RESOURCE_NOT_FOUND,
                    Map.of("resource", "proposal_item", "id", id));
        }

        return proposalRevisionRepository
                .findByProposalItemIdOrderByRevisionAsc(id, pageable)
                .map(ProposalRevisionResponse::from);
    }

    public Page<ProposalDecisionResponse> getDecisions(long id, Pageable pageable) {
        if (!proposalItemRepository.existsById(id)) {
            throw new BusinessException(
                    CommonErrorCode.RESOURCE_NOT_FOUND,
                    Map.of("resource", "proposal_item", "id", id));
        }
        return reviewDecisionRepository
                .findByProposalItemIdOrderByDecidedAtDescIdDesc(id, pageable)
                .map(ProposalDecisionResponse::from);
    }

    private Map<Long, LatestCheckResponse> loadLatestChecks(List<ProposalItem> proposals) {
        if (proposals.isEmpty()) {
            return Map.of();
        }

        Map<Long, Integer> proposalRevisions =
                proposals.stream()
                        .collect(Collectors.toMap(ProposalItem::getId, ProposalItem::getRevision));

        return proposalLatestCheckRepository.findLatestByProposalRevisions(proposalRevisions);
    }

    private ProposalResponse toResponse(ProposalItem proposal, LatestCheckResponse latestCheck) {
        // 검사 대상이 아닌 종류는 latestCheck를 주지 않는다. 이 정책 전에 접수돼 "자동 시험 미지원"으로 실패한 검사가 남아 있어도 승인과 무관하다.
        boolean checkRequired = CandidateCheckTarget.requiresCheck(proposal.getItemKind());
        return new ProposalResponse(
                proposal.getId(),
                proposal.getAnalysisRunId(),
                proposal.getOrigin(),
                proposal.getItemKind(),
                proposal.getRevision(),
                proposal.getPayload(),
                proposal.getEvidence(),
                proposal.getAiConfidence(),
                proposal.getReviewStatus(),
                proposal.getApplyStatus(),
                proposal.getApplyError(),
                proposal.getAppliedAt(),
                checkRequired,
                checkRequired ? latestCheck : null);
    }
}
