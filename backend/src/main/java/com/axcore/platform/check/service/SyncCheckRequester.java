package com.axcore.platform.check.service;

import com.axcore.platform.check.domain.CheckDataBasis;
import com.axcore.platform.check.domain.LinkCheckPending.Status;
import com.axcore.platform.check.repository.CheckRunRepository;
import com.axcore.platform.check.repository.CheckRunRepository.NewRun;
import com.axcore.platform.check.repository.LinkCheckPendingRepository;
import com.axcore.platform.ontology.domain.LinkMapping;
import com.axcore.platform.ontology.domain.LinkMappingStatus;
import com.axcore.platform.ontology.repository.LinkMappingRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 수집 뒤 검사(SYNC)를 넣는다(S15P31C106-191-4, 191 설계안 5장). 배치가 주기적으로 부른다({@link SyncCheckJob}).
 *
 * <ul>
 *   <li>범위는 반영 중인 규칙 전부(ALL)다. 요청자와 {@code sync_run_id}는 없다(V12). 반영 중인 규칙이 없으면 넣지 않는다.
 *   <li>데이터 기준은 060과 같다(원천 테이블별 마지막 성공 수집). CDC 반영도 원천 트랜잭션마다 성공 수집을 남기므로 데이터가 바뀌면 기준도 바뀐다.
 *   <li>같은 검사 판단도 060과 같다. 같은 기준(수집 기준과 연결 상태, 367)으로 성공한 SYNC가 있으면 넣지 않고(A4), 대기·실행 중이면 합류한다.
 *   <li>확인 대기(WAITING)가 있으면 데이터가 그대로여도 시간이 지나면 승격돼야 하므로, {@code work_key}에 주기 구간을 섞어 주기마다 새로 넣는다.
 * </ul>
 */
@Service
public class SyncCheckRequester {

    private static final int MAX_ATTEMPTS = 3;

    private final CheckRunRepository runs;
    private final LinkMappingRepository linkMappings;
    private final LinkMappingRuleLoader ruleLoader;
    private final LinkCheckPendingRepository pendings;
    private final JsonMapper json;
    private final Clock clock;
    private final Duration interval;

    public SyncCheckRequester(
            CheckRunRepository runs,
            LinkMappingRepository linkMappings,
            LinkMappingRuleLoader ruleLoader,
            LinkCheckPendingRepository pendings,
            JsonMapper json,
            Clock clock,
            @Value("${axcore.check.sync.interval:5m}") Duration interval) {
        this.runs = runs;
        this.linkMappings = linkMappings;
        this.ruleLoader = ruleLoader;
        this.pendings = pendings;
        this.json = json;
        this.clock = clock;
        this.interval = interval;
    }

    /** SYNC 하나를 넣거나 같은 검사를 찾는다. 반영 중인 규칙이 없으면 빈 값이다. */
    @Transactional
    public Optional<SyncRequest> request() {
        List<Long> ruleIds =
                linkMappings.findByStatusOrderById(LinkMappingStatus.ACTIVE).stream()
                        .map(LinkMapping::getId)
                        .toList();
        if (ruleIds.isEmpty()) {
            return Optional.empty();
        }
        TreeSet<Long> resourceIds = new TreeSet<>();
        ruleIds.forEach(id -> resourceIds.addAll(ruleLoader.resourceIds(id)));
        CheckDataBasis basis = new CheckDataBasis(runs.loadCheckpoints(resourceIds));

        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("kind", "ALL");
        scope.put("linkMappingIds", ruleIds);
        String scopeJson = json.writeValueAsString(scope);
        String basisJson = json.writeValueAsString(basis);
        Instant now = Instant.now(clock);
        String linkState =
                runs.linkState(CheckRunRequestService.linkTypeIds(linkMappings, ruleIds));
        String workKey =
                "SYNC:-:"
                        + CheckRunRequestService.sha256(
                                scopeJson + "|" + basisJson + "|" + linkState);
        if (pendings.existsByStatus(Status.WAITING)) {
            workKey += ":t" + now.toEpochMilli() / interval.toMillis();
        }
        NewRun run = new NewRun("SYNC", scopeJson, workKey, basisJson, null, null, now);

        // 같은 작업이 그 사이 끝나거나 롤백되면 다시 본다(060과 같다).
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Optional<Long> succeeded = runs.findSucceeded(workKey);
            if (succeeded.isPresent()) {
                return Optional.of(new SyncRequest(succeeded.get(), Result.REUSED));
            }
            Optional<Long> inserted = runs.insertQueued(run);
            if (inserted.isPresent()) {
                return Optional.of(new SyncRequest(inserted.get(), Result.QUEUED));
            }
            Optional<Long> active = runs.findActive(workKey);
            if (active.isPresent()) {
                return Optional.of(new SyncRequest(active.get(), Result.JOINED));
            }
        }
        throw new IllegalStateException("SYNC 검사를 넣지 못했습니다: " + workKey);
    }

    public record SyncRequest(long checkRunId, Result result) {}

    public enum Result {
        /** 새로 넣었다. */
        QUEUED,
        /** 같은 기준의 SYNC가 대기·실행 중이다. */
        JOINED,
        /** 같은 기준으로 이미 성공한 SYNC가 있다. */
        REUSED
    }
}
