package com.axcore.platform.source.service;

import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.source.dto.SyncRunResponse;
import com.axcore.platform.source.dto.SyncRunSearchRequest;
import com.axcore.platform.source.repository.SyncRunQueryRepository;
import com.axcore.platform.source.repository.SyncRunQueryRepository.Filter;
import com.axcore.platform.source.repository.SyncRunQueryRepository.Resource;
import com.axcore.platform.source.repository.SyncRunQueryRepository.Run;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 원천 수집(CDC 묶음과 전체 읽기)의 상태·처리 건수·실패 이유·이력 조회(API 074·075, FN-BAT-04, S15P31C106-219). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SyncRunQueryService {

    private final DataSourceService dataSourceService;
    private final SyncRunQueryRepository repository;

    /** API 074. 접수 시각이 늦은 순서다. */
    public SyncRunPage search(long dataSourceId, SyncRunSearchRequest request, Pageable pageable) {
        dataSourceService.findDataSource(dataSourceId);
        Filter filter =
                new Filter(request.status(), request.triggerKind(), request.from(), request.to());
        List<Run> runs =
                repository.search(
                        dataSourceId, filter, pageable.getOffset(), pageable.getPageSize());
        Map<Long, List<Resource>> resources =
                repository.findResources(runs.stream().map(Run::id).toList()).stream()
                        .collect(Collectors.groupingBy(Resource::syncRunId));
        List<SyncRunResponse> content =
                runs.stream()
                        .map(
                                run ->
                                        SyncRunResponse.of(
                                                run, resources.getOrDefault(run.id(), List.of())))
                        .toList();
        return new SyncRunPage(
                new PageImpl<>(content, pageable, repository.count(dataSourceId, filter)),
                repository.findCdcLastAppliedAt(dataSourceId).orElse(null));
    }

    /** API 075. */
    public SyncRunResponse get(long syncRunId) {
        Run run =
                repository
                        .find(syncRunId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                CommonErrorCode.RESOURCE_NOT_FOUND,
                                                Map.of("resource", "sync_run", "id", syncRunId)));
        return SyncRunResponse.of(run, repository.findResources(List.of(syncRunId)));
    }

    /**
     * @param cdcLastAppliedAt 이 원천에서 CDC로 마지막 반영한 원천 트랜잭션의 원천 커밋 시각. 반영한 트랜잭션이 없으면 null
     */
    public record SyncRunPage(Page<SyncRunResponse> page, Instant cdcLastAppliedAt) {}
}
