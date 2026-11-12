package com.axcore.platform.source.controller;

import com.axcore.platform.account.domain.RoleCode;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.response.ApiResponse;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.source.dto.CdcReconnectResponse;
import com.axcore.platform.source.dto.ConnectionCheckResponse;
import com.axcore.platform.source.dto.ConnectionCheckResultResponse;
import com.axcore.platform.source.dto.SourceColumnResponse;
import com.axcore.platform.source.dto.SourceCreateRequest;
import com.axcore.platform.source.dto.SourceDetailResponse;
import com.axcore.platform.source.dto.SourceResourceResponse;
import com.axcore.platform.source.dto.SourceResourceSearchRequest;
import com.axcore.platform.source.dto.SourceResponse;
import com.axcore.platform.source.dto.SourceSampleAcceptedResponse;
import com.axcore.platform.source.dto.SourceSampleRequest;
import com.axcore.platform.source.dto.SourceSearchRequest;
import com.axcore.platform.source.dto.SourceUpdateRequest;
import com.axcore.platform.source.dto.SyncRunAcceptedResponse;
import com.axcore.platform.source.dto.SyncRunRequest;
import com.axcore.platform.source.dto.SyncScheduleRequest;
import com.axcore.platform.source.dto.SyncScheduleResponse;
import com.axcore.platform.source.service.DataSourceService;
import com.axcore.platform.source.service.SourceCatalogService;
import com.axcore.platform.source.service.SourceConnectionCheckService;
import com.axcore.platform.source.service.SourceSampleRequestService;
import com.axcore.platform.source.service.SyncRunRequestService;
import com.axcore.platform.source.service.SyncScheduleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 원천 정보·연결 검사·자원 목록·표본 조회·수집 주기·수동 다시 읽기 (API 009~016, 071~073, 100, FN-SRC-01·02·03,
 * FN-BAT-01·03). 모두 ADMIN만 쓸 수 있다.
 */
@RestController
@RequestMapping("/api/v1/sources")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class DataSourceController {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final DataSourceService dataSourceService;
    private final SourceConnectionCheckService connectionCheckService;
    private final SourceCatalogService catalogService;
    private final SourceSampleRequestService sampleRequestService;
    private final SyncRunRequestService syncRunRequestService;
    private final SyncScheduleService syncScheduleService;

    @GetMapping
    public ApiResponse<List<SourceResponse>> search(
            @Valid @ModelAttribute SourceSearchRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        return ApiResponse.ofPage(
                dataSourceService.search(
                        request.sourceKind(),
                        request.connectionStatus(),
                        page(request.page(), request.size())));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SourceResponse> create(
            @Valid @RequestBody SourceCreateRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        return ApiResponse.of(dataSourceService.create(request, loginAccount.id()));
    }

    @GetMapping("/{id}")
    public ApiResponse<SourceDetailResponse> get(
            @PathVariable("id") @Min(1) long id,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        return ApiResponse.of(dataSourceService.get(id));
    }

    @PatchMapping("/{id}")
    public ApiResponse<SourceResponse> update(
            @PathVariable("id") @Min(1) long id,
            @Valid @RequestBody SourceUpdateRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        return ApiResponse.of(dataSourceService.update(id, request, loginAccount.id()));
    }

    @PostMapping("/{id}/test-connection")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<ConnectionCheckResponse> requestConnectionCheck(
            @PathVariable("id") @Min(1) long id,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        return ApiResponse.of(connectionCheckService.request(id, loginAccount));
    }

    /** 원천 교체 뒤 커넥터를 새 DB에 맞췄다고 알린다(199). 다시 읽기는 배치 감시 작업이 넣으므로 접수만 한다. */
    @PostMapping("/{id}/cdc/reconnected")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<CdcReconnectResponse> reconnectCdc(
            @PathVariable("id") @Min(1) long id,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        return ApiResponse.of(dataSourceService.reconnectCdc(id));
    }

    @GetMapping("/{id}/sync-schedule")
    public ApiResponse<SyncScheduleResponse> syncSchedule(
            @PathVariable("id") @Min(1) long id,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        return ApiResponse.of(syncScheduleService.get(id));
    }

    /** 권한 검사는 서비스가 한다. 막힌 요청도 감사 기록(BLOCKED)에 남기기 위해서다. */
    @PutMapping("/{id}/sync-schedule")
    public ApiResponse<SyncScheduleResponse> updateSyncSchedule(
            @PathVariable("id") @Min(1) long id,
            @Valid @RequestBody SyncScheduleRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        return ApiResponse.of(syncScheduleService.update(loginAccount, id, request));
    }

    /** 고른 테이블의 전체 다시 읽기를 접수한다. 대기·실행 중인 테이블은 그 실행에 합류한다. */
    @PostMapping("/{id}/sync-runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<SyncRunAcceptedResponse> requestSyncRun(
            @PathVariable("id") @Min(1) long id,
            @Valid @RequestBody(required = false) SyncRunRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        return ApiResponse.of(
                syncRunRequestService.request(
                        id, request == null ? null : request.sourceResourceIds(), loginAccount));
    }

    @GetMapping("/{id}/connection-checks/{checkId}")
    public ApiResponse<ConnectionCheckResultResponse> connectionCheck(
            @PathVariable("id") @Min(1) long id,
            @PathVariable("checkId") @Min(1) long checkId,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        return ApiResponse.of(connectionCheckService.result(id, checkId));
    }

    @GetMapping("/{id}/resources")
    public ApiResponse<List<SourceResourceResponse>> resources(
            @PathVariable("id") @Min(1) long id,
            @Valid @ModelAttribute SourceResourceSearchRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        return ApiResponse.ofPage(
                catalogService.resources(id, request, page(request.page(), request.size())));
    }

    @GetMapping("/{id}/resources/{resourceId}/columns")
    public ApiResponse<List<SourceColumnResponse>> columns(
            @PathVariable("id") @Min(1) long id,
            @PathVariable("resourceId") @Min(1) long resourceId,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        return ApiResponse.of(catalogService.columns(id, resourceId));
    }

    @PostMapping("/{id}/resources/{resourceId}/samples")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<SourceSampleAcceptedResponse> requestSample(
            @PathVariable("id") @Min(1) long id,
            @PathVariable("resourceId") @Min(1) long resourceId,
            @Valid @RequestBody(required = false) SourceSampleRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        int rowLimit =
                request == null || request.rowLimit() == null
                        ? SourceSampleRequest.DEFAULT_ROW_LIMIT
                        : request.rowLimit();
        return ApiResponse.of(sampleRequestService.request(id, resourceId, rowLimit, loginAccount));
    }

    private static PageRequest page(Integer page, Integer size) {
        return PageRequest.of(page == null ? 0 : page, size == null ? DEFAULT_PAGE_SIZE : size);
    }

    private static void requireAdmin(LoginAccount loginAccount) {
        if (!RoleCode.ADMIN.name().equals(loginAccount.roleCode())) {
            throw new BusinessException(
                    CommonErrorCode.FORBIDDEN, Map.of("requiredPermission", RoleCode.ADMIN.name()));
        }
    }
}
