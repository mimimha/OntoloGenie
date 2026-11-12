package com.axcore.platform.ontology.controller;

import com.axcore.platform.account.domain.RoleCode;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.response.ApiResponse;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.domain.DefinitionEditAction;
import com.axcore.platform.ontology.dto.PropertyCreateRequest;
import com.axcore.platform.ontology.dto.PropertyResponse;
import com.axcore.platform.ontology.dto.PropertyUpdateRequest;
import com.axcore.platform.ontology.service.DefinitionEditRecorder;
import com.axcore.platform.ontology.service.PropertyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 속성 관리 (API 026~029, FN-ONT-02). 모두 ADMIN만 쓸 수 있다. */
@RestController
@RequestMapping("/api/v1/ontology")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class PropertyController {

    private final PropertyService propertyService;
    private final DefinitionEditRecorder editRecorder;

    @GetMapping("/object-types/{id}/properties")
    public ApiResponse<List<PropertyResponse>> findProperties(
            @PathVariable("id") @Min(1) long objectTypeId,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        return ApiResponse.of(propertyService.findProperties(objectTypeId));
    }

    @PostMapping("/object-types/{id}/properties")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PropertyResponse> create(
            @PathVariable("id") @Min(1) long objectTypeId,
            @Valid @RequestBody PropertyCreateRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        return ApiResponse.of(
                editRecorder.create(
                        loginAccount,
                        DefinitionEditAction.CREATE_PROPERTY,
                        editRecorder.input("objectTypeId", objectTypeId, request),
                        () -> propertyService.create(objectTypeId, request),
                        PropertyResponse::id));
    }

    @PatchMapping("/properties/{id}")
    public ApiResponse<PropertyResponse> update(
            @PathVariable("id") @Min(1) long id,
            @Valid @RequestBody PropertyUpdateRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        return ApiResponse.of(
                editRecorder.update(
                        loginAccount,
                        DefinitionEditAction.UPDATE_PROPERTY,
                        id,
                        editRecorder.input(id, request),
                        () -> propertyService.update(id, request)));
    }

    @DeleteMapping("/properties/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable("id") @Min(1) long id,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        editRecorder.delete(
                loginAccount,
                DefinitionEditAction.DELETE_PROPERTY,
                id,
                editRecorder.input(id, null),
                () -> {
                    propertyService.delete(id);
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
