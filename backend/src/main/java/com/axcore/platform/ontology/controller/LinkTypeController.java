package com.axcore.platform.ontology.controller;

import com.axcore.platform.account.domain.RoleCode;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.response.ApiResponse;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.domain.DefinitionEditAction;
import com.axcore.platform.ontology.dto.LinkTypeCreateRequest;
import com.axcore.platform.ontology.dto.LinkTypeResponse;
import com.axcore.platform.ontology.dto.LinkTypeSearchRequest;
import com.axcore.platform.ontology.dto.LinkTypeUpdateRequest;
import com.axcore.platform.ontology.service.DefinitionEditRecorder;
import com.axcore.platform.ontology.service.LinkTypeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 관계 유형 관리 (API 030~033, FN-ONT-03). 모두 ADMIN만 쓸 수 있다. */
@RestController
@RequestMapping("/api/v1/ontology/link-types")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class LinkTypeController {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final LinkTypeService linkTypeService;
    private final DefinitionEditRecorder editRecorder;

    @GetMapping
    public ApiResponse<List<LinkTypeResponse>> search(
            @Valid @ModelAttribute LinkTypeSearchRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        int page = request.page() == null ? 0 : request.page();
        int size = request.size() == null ? DEFAULT_PAGE_SIZE : request.size();
        return ApiResponse.ofPage(linkTypeService.search(request, PageRequest.of(page, size)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<LinkTypeResponse> create(
            @Valid @RequestBody LinkTypeCreateRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        return ApiResponse.of(
                editRecorder.create(
                        loginAccount,
                        DefinitionEditAction.CREATE_LINK_TYPE,
                        editRecorder.input(null, request),
                        () -> linkTypeService.create(request),
                        LinkTypeResponse::id));
    }

    @PatchMapping("/{id}")
    public ApiResponse<LinkTypeResponse> update(
            @PathVariable("id") @Min(1) long id,
            @Valid @RequestBody LinkTypeUpdateRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        return ApiResponse.of(
                editRecorder.update(
                        loginAccount,
                        DefinitionEditAction.UPDATE_LINK_TYPE,
                        id,
                        editRecorder.input(id, request),
                        () -> linkTypeService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable("id") @Min(1) long id,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        editRecorder.delete(
                loginAccount,
                DefinitionEditAction.DELETE_LINK_TYPE,
                id,
                editRecorder.input(id, null),
                () -> {
                    linkTypeService.delete(id);
                    return null;
                });
    }

    private static void requireAdmin(LoginAccount loginAccount) {
        if (!RoleCode.ADMIN.name().equals(loginAccount.roleCode())) {
            throw new BusinessException(
                    CommonErrorCode.FORBIDDEN, Map.of("requiredPermission", RoleCode.ADMIN.name()));
        }
    }
}
