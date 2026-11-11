package com.axcore.platform.source.controller;

import com.axcore.platform.account.domain.RoleCode;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.response.ApiResponse;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.source.dto.SourcePreviewResponse;
import com.axcore.platform.source.service.SourcePreviewQueryService;
import jakarta.validation.constraints.Min;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 원천 미리보기 결과 조회 (API 102). 원천 표본(016)·매핑 미리보기(040)가 함께 쓴다. ADMIN만 쓸 수 있다. */
@RestController
@RequestMapping("/api/v1/ontology/source-previews")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class SourcePreviewController {

    private final SourcePreviewQueryService previewQueryService;

    @GetMapping("/{previewId}")
    public ApiResponse<SourcePreviewResponse> get(
            @PathVariable("previewId") @Min(value = 1, message = "previewId는 1 이상이어야 합니다.")
                    long previewId,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        return ApiResponse.of(previewQueryService.get(previewId));
    }

    private static void requireAdmin(LoginAccount loginAccount) {
        if (!RoleCode.ADMIN.name().equals(loginAccount.roleCode())) {
            throw new BusinessException(
                    CommonErrorCode.FORBIDDEN, Map.of("requiredPermission", RoleCode.ADMIN.name()));
        }
    }
}
