package com.axcore.platform.ontology.controller;

import com.axcore.platform.account.domain.RoleCode;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.response.ApiResponse;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.dto.ModelGraphResponse;
import com.axcore.platform.ontology.service.ModelGraphService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 관리자용 객체 유형 관계도 (API 042, FN-ONT-08). */
@RestController
@RequestMapping("/api/v1/ontology/model-graph")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class ModelGraphController {

    private final ModelGraphService modelGraphService;

    @GetMapping
    public ApiResponse<ModelGraphResponse> getGraph(
            @AuthenticationPrincipal LoginAccount loginAccount) {
        String requiredRole = RoleCode.ADMIN.name();
        if (!requiredRole.equals(loginAccount.roleCode())) {
            throw new BusinessException(
                    CommonErrorCode.FORBIDDEN, Map.of("requiredPermission", requiredRole));
        }
        return ApiResponse.of(modelGraphService.getGraph());
    }
}
