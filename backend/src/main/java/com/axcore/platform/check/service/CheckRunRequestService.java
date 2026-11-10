package com.axcore.platform.check.service;

import com.axcore.platform.check.domain.CheckDataBasis;
import com.axcore.platform.check.dto.CheckRunCreateRequest;
import com.axcore.platform.check.dto.CheckRunCreateRequest.Scope;
import com.axcore.platform.check.dto.CheckRunRequestResponse;
import com.axcore.platform.check.repository.CheckRunRepository;
import com.axcore.platform.check.repository.CheckRunRepository.NewRun;
import com.axcore.platform.check.repository.CheckRunRepository.RunState;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.issue.domain.Issue;
import com.axcore.platform.issue.domain.IssueStatus;
import com.axcore.platform.issue.repository.IssueRepository;
import com.axcore.platform.ontology.domain.LinkMapping;
import com.axcore.platform.ontology.domain.LinkMappingStatus;
import com.axcore.platform.ontology.repository.LinkMappingRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * API 060 수동 검사·재검증 요청 접수(S15P31C106-191). 백엔드는 원천에 접속하지 않고 check_run에 QUEUED 작업만 넣는다. 실행은 배치가 한다.
 *
 * <p>같은 종류·비교 대상·범위(규칙 개정본 목록)·데이터 기준이면 같은 작업이다. 성공한 같은 작업이 있으면 다시 쓰고(200), 대기·실행 중이면 합류한다(202). 이
 * API는 중복 요청에 409를 쓰지 않는다.
 */
@Service
@RequiredArgsConstructor
public class CheckRunRequestService {

    private static final int MAX_ATTEMPTS = 3;

    private final CheckRunRepository checkRunRepository;
    private final LinkMappingRepository linkMappingRepository;
    private final IssueRepository issueRepository;
    private final LinkMappingRuleLoader ruleLoader;
    private final JsonMapper json;
    private final Clock clock;

    @Transactional
    public CheckRunRequestResponse request(CheckRunCreateRequest request, long accountId) {
        String triggerKind = request.triggerKind();
        Scope scope = request.scope();
        requireShape(triggerKind, scope, request.comparedToRunId());
        if (request.comparedToRunId() != null) {
            requireComparable(request.comparedToRunId());
        }

        Map<String, Object> storedScope = resolveScope(scope);
        @SuppressWarnings("unchecked")
        List<Long> ruleIds = (List<Long>) storedScope.get("linkMappingIds");

        TreeSet<Long> resourceIds = new TreeSet<>();
        ruleIds.forEach(id -> resourceIds.addAll(ruleLoader.resourceIds(id)));
        CheckDataBasis basis = new CheckDataBasis(checkRunRepository.loadCheckpoints(resourceIds));
        // 실행기가 같은 변환을 다시 하므로, 바꿀 수 없는 규칙은 접수 때 거절한다.
        for (long ruleId : ruleIds) {
            try {
                ruleLoader.load(ruleId, basis);
            } catch (IllegalArgumentException | IllegalStateException ex) {
                throw new BusinessException(
                        CommonErrorCode.BUSINESS_RULE_VIOLATION,
                        "검사할 수 없는 규칙이 있습니다.",
                        Map.of(
                                "field",
                                "scope",
                                "linkMappingId",
                                ruleId,
                                "reason",
                                ex.getMessage()));
            }
        }

        String scopeJson = json.writeValueAsString(storedScope);
        String basisJson = json.writeValueAsString(basis);
        String workKey =
                triggerKind
                        + ":"
                        + (request.comparedToRunId() == null ? "-" : request.comparedToRunId())
                        + ":"
                        + sha256(scopeJson + "|" + basisJson);
        NewRun run =
                new NewRun(
                        triggerKind,
                        scopeJson,
                        workKey,
                        basisJson,
                        request.comparedToRunId(),
                        accountId,
                        Instant.now(clock));

        // 같은 작업이 그 사이 끝나거나 롤백되면 다시 본다.
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Optional<Long> succeeded = checkRunRepository.findSucceeded(workKey);
            if (succeeded.isPresent()) {
                return response(succeeded.get(), false, true);
            }
            Optional<Long> inserted = checkRunRepository.insertQueued(run);
            if (inserted.isPresent()) {
                return response(inserted.get(), false, false);
            }
            Optional<Long> active = checkRunRepository.findActive(workKey);
            if (active.isPresent()) {
                return response(active.get(), true, false);
            }
        }
        throw new IllegalStateException("검사 요청을 접수하지 못했습니다: " + workKey);
    }

    /** 종류·범위·비교 대상의 조합. 틀리면 400이다. */
    private static void requireShape(String triggerKind, Scope scope, Long comparedToRunId) {
        boolean reverify = "REVERIFY".equals(triggerKind);
        if (reverify && comparedToRunId == null) {
            throw BusinessException.invalidField("comparedToRunId", "REVERIFY에는 필수입니다.");
        }
        if (!reverify && comparedToRunId != null) {
            throw BusinessException.invalidField("comparedToRunId", "REVERIFY일 때만 보냅니다.");
        }
        boolean hasRules = scope.linkMappingIds() != null && !scope.linkMappingIds().isEmpty();
        boolean hasIssues = scope.issueIds() != null && !scope.issueIds().isEmpty();
        switch (scope.kind()) {
            case "ALL" -> {
                if (hasRules || hasIssues) {
                    throw BusinessException.invalidField("scope", "ALL에는 ID 목록을 보내지 않습니다.");
                }
            }
            case "LINK_MAPPINGS" -> {
                if (!hasRules) {
                    throw BusinessException.invalidField(
                            "scope.linkMappingIds", "LINK_MAPPINGS에는 규칙 ID가 하나 이상 필요합니다.");
                }
                if (hasIssues) {
                    throw BusinessException.invalidField(
                            "scope.issueIds", "LINK_MAPPINGS에는 문제 ID를 보내지 않습니다.");
                }
            }
            default -> {
                if (!reverify) {
                    throw BusinessException.invalidField("scope.kind", "ISSUES는 REVERIFY에서만 씁니다.");
                }
                if (!hasIssues) {
                    throw BusinessException.invalidField(
                            "scope.issueIds", "ISSUES에는 문제 ID가 하나 이상 필요합니다.");
                }
                if (hasRules) {
                    throw BusinessException.invalidField(
                            "scope.linkMappingIds", "ISSUES에는 규칙 ID를 보내지 않습니다.");
                }
            }
        }
    }

    /** 재검증이 비교할 이전 검사. 성공한 규칙 검사(MANUAL·SYNC·REVERIFY)여야 한다. */
    private void requireComparable(long comparedToRunId) {
        RunState previous =
                checkRunRepository
                        .findState(comparedToRunId)
                        .orElseThrow(
                                () ->
                                        rejected(
                                                "comparedToRunId",
                                                "없는 검사 실행입니다.",
                                                Map.of("comparedToRunId", comparedToRunId)));
        if ("CANDIDATE".equals(previous.triggerKind())) {
            throw rejected(
                    "comparedToRunId",
                    "승인 전 후보 시험과는 비교할 수 없습니다.",
                    Map.of("triggerKind", previous.triggerKind()));
        }
        if (!"SUCCEEDED".equals(previous.status())) {
            throw rejected(
                    "comparedToRunId", "성공한 검사와만 비교할 수 있습니다.", Map.of("status", previous.status()));
        }
    }

    /**
     * 요청 범위를 검사할 규칙 개정본 목록으로 바꿔 저장 형식으로 만든다. ALL도 접수 시점의 규칙 목록을 남겨, 실행기가 같은 규칙을 검사하고 같은 목록끼리만 합류하게
     * 한다.
     */
    private Map<String, Object> resolveScope(Scope scope) {
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("kind", scope.kind());
        switch (scope.kind()) {
            case "ALL" -> {
                List<Long> active =
                        linkMappingRepository
                                .findByStatusOrderById(LinkMappingStatus.ACTIVE)
                                .stream()
                                .map(LinkMapping::getId)
                                .toList();
                if (active.isEmpty()) {
                    throw rejected("scope", "반영 중인 연결 규칙이 없습니다.", Map.of());
                }
                stored.put("linkMappingIds", active);
            }
            case "LINK_MAPPINGS" -> {
                List<Long> requested = List.copyOf(new TreeSet<>(scope.linkMappingIds()));
                stored.put("linkMappingIds", requireActive(requested, "scope.linkMappingIds"));
            }
            default -> {
                List<Long> issueIds = List.copyOf(new TreeSet<>(scope.issueIds()));
                List<Issue> issues =
                        issueRepository.findAllById(issueIds).stream()
                                .sorted(java.util.Comparator.comparing(Issue::getId))
                                .toList();
                List<Long> found = issues.stream().map(Issue::getId).toList();
                List<Long> missing = issueIds.stream().filter(id -> !found.contains(id)).toList();
                if (!missing.isEmpty()) {
                    throw rejected("scope.issueIds", "없는 문제입니다.", Map.of("issueIds", missing));
                }
                List<Long> resolved =
                        issues.stream()
                                .filter(issue -> issue.getStatus() == IssueStatus.RESOLVED)
                                .map(Issue::getId)
                                .toList();
                if (!resolved.isEmpty()) {
                    throw rejected(
                            "scope.issueIds", "해결된 문제는 재검증하지 않습니다.", Map.of("issueIds", resolved));
                }
                List<Long> fromProposals =
                        issues.stream()
                                .filter(issue -> issue.getLinkMappingId() == null)
                                .map(Issue::getId)
                                .toList();
                if (!fromProposals.isEmpty()) {
                    throw rejected(
                            "scope.issueIds",
                            "승인 전 후보에서 나온 문제는 재검증 대상이 아닙니다.",
                            Map.of("issueIds", fromProposals));
                }
                List<Long> rules =
                        List.copyOf(
                                new TreeSet<>(
                                        issues.stream().map(Issue::getLinkMappingId).toList()));
                stored.put("linkMappingIds", requireActive(rules, "scope.issueIds"));
                stored.put("issueIds", issueIds);
            }
        }
        return stored;
    }

    private List<Long> requireActive(List<Long> ruleIds, String field) {
        Set<Long> active =
                linkMappingRepository
                        .findByIdInAndStatus(ruleIds, LinkMappingStatus.ACTIVE)
                        .stream()
                        .map(LinkMapping::getId)
                        .collect(Collectors.toSet());
        List<Long> inactive = ruleIds.stream().filter(id -> !active.contains(id)).toList();
        if (!inactive.isEmpty()) {
            throw rejected(field, "없거나 반영 중이 아닌 규칙입니다.", Map.of("linkMappingIds", inactive));
        }
        return ruleIds;
    }

    private CheckRunRequestResponse response(long runId, boolean joined, boolean reused) {
        return new CheckRunRequestResponse(
                checkRunRepository.findById(runId).orElseThrow(), joined, reused);
    }

    private static BusinessException rejected(
            String field, String reason, Map<String, Object> extra) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("field", field);
        details.put("reason", reason);
        details.putAll(extra);
        return new BusinessException(CommonErrorCode.BUSINESS_RULE_VIOLATION, reason, details);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", ex);
        }
    }
}
