package com.axcore.platform.source.controller;

import com.axcore.platform.account.domain.RoleCode;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.response.ApiResponse;
import com.axcore.platform.global.response.PageMeta;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.source.dto.SyncRunResponse;
import com.axcore.platform.source.dto.SyncRunSearchRequest;
import com.axcore.platform.source.service.SyncRunQueryService;
import com.axcore.platform.source.service.SyncRunQueryService.SyncRunPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 원천 수집 이력 074, 수집 상태·건수·실패 사유 075 (FN-BAT-04). 모두 ADMIN만 쓸 수 있다. */
@RestController
@RequestMapping("/api/v1")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class SyncRunController {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final SyncRunQueryService syncRunQueryService;

    @GetMapping("/sources/{id}/sync-runs")
    public ApiResponse<List<SyncRunResponse>> search(
            @PathVariable("id") @Min(1) long id,
            @Valid @ModelAttribute SyncRunSearchRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        int page = request.page() == null ? 0 : request.page();
        int size = request.size() == null ? DEFAULT_PAGE_SIZE : request.size();
        SyncRunPage result = syncRunQueryService.search(id, request, PageRequest.of(page, size));
        return new ApiResponse<>(
                result.page().getContent(),
                PageMeta.from(result.page()).withCdcLastAppliedAt(result.cdcLastAppliedAt()));
    }

    @GetMapping("/sync-runs/{id}")
    public ApiResponse<SyncRunResponse> get(
            @PathVariable("id") @Min(1) long id,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        return ApiResponse.of(syncRunQueryService.get(id));
    }

    private static void requireAdmin(LoginAccount loginAccount) {
        if (!RoleCode.ADMIN.name().equals(loginAccount.roleCode())) {
            throw new BusinessException(
                    CommonErrorCode.FORBIDDEN, Map.of("requiredPermission", RoleCode.ADMIN.name()));
        }
    }
}
