package com.axcore.platform.ontology.controller;

import com.axcore.platform.account.domain.RoleCode;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.response.ApiResponse;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.dto.DefinitionChangeResponse;
import com.axcore.platform.ontology.dto.DefinitionChangeSearchRequest;
import com.axcore.platform.ontology.service.DefinitionChangeQueryService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 정의 변경 이력 (API 044, FN-ONT-07·FN-LOG-03). ADMIN만 쓸 수 있다. */
@RestController
@RequestMapping("/api/v1/ontology/changes")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class DefinitionChangeController {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final DefinitionChangeQueryService service;

    @GetMapping
    public ApiResponse<List<DefinitionChangeResponse>> search(
            @Valid @ModelAttribute DefinitionChangeSearchRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        if (!RoleCode.ADMIN.name().equals(loginAccount.roleCode())) {
            throw new BusinessException(
                    CommonErrorCode.FORBIDDEN, Map.of("requiredPermission", RoleCode.ADMIN.name()));
        }
        PageRequest page =
                PageRequest.of(
                        request.page() == null ? 0 : request.page(),
                        request.size() == null ? DEFAULT_PAGE_SIZE : request.size());
        return ApiResponse.ofPage(service.search(request, page));
    }
}
