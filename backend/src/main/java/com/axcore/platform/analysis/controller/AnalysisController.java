package com.axcore.platform.analysis.controller;

import com.axcore.platform.analysis.dto.AnalysisCreateRequest;
import com.axcore.platform.analysis.dto.AnalysisRunResponse;
import com.axcore.platform.analysis.service.AnalysisCreateService;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.response.ApiResponse;
import com.axcore.platform.global.security.LoginAccount;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class AnalysisController {
    private final AnalysisCreateService service;

    @Operation(summary = "018 선택 범위 AI 분석 실행 접수")
    @PostMapping("/api/v1/analyses")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<AnalysisRunResponse> create(
            @Valid @RequestBody AnalysisCreateRequest request,
            @AuthenticationPrincipal LoginAccount account) {
        requireAdmin(account);
        return ApiResponse.of(service.create(request, account));
    }

    private static void requireAdmin(LoginAccount account) {
        if (account == null) {
            throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
        }
        if (!"ADMIN".equals(account.roleCode())) {
            throw new BusinessException(
                    CommonErrorCode.FORBIDDEN, Map.of("requiredPermission", "ADMIN"));
        }
    }
}
