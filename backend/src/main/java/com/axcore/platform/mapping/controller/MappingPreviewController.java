package com.axcore.platform.mapping.controller;

import com.axcore.platform.account.domain.RoleCode;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.response.ApiResponse;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.mapping.dto.MappingPreviewAccepted;
import com.axcore.platform.mapping.dto.MappingPreviewRequest;
import com.axcore.platform.mapping.service.MappingPreviewService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 객체 매핑 시험 변환 접수 (API 040, FN-MAP-04). ADMIN만 쓸 수 있다. 결과는 102({@code SourcePreviewController})로
 * 받는다.
 */
@RestController
@RequestMapping("/api/v1/ontology/source-resources")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class MappingPreviewController {

    private final MappingPreviewService previewService;

    @PostMapping("/{id}/mapping-previews")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<MappingPreviewAccepted> request(
            @PathVariable("id") @Min(value = 1, message = "id는 1 이상이어야 합니다.") long id,
            @Valid @RequestBody MappingPreviewRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        return ApiResponse.of(previewService.request(id, request, loginAccount));
    }

    private static void requireAdmin(LoginAccount loginAccount) {
        if (!RoleCode.ADMIN.name().equals(loginAccount.roleCode())) {
            throw new BusinessException(
                    CommonErrorCode.FORBIDDEN, Map.of("requiredPermission", RoleCode.ADMIN.name()));
        }
    }
}
