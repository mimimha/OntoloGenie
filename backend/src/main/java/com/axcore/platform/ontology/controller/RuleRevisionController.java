package com.axcore.platform.ontology.controller;

import com.axcore.platform.account.domain.RoleCode;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.global.response.ApiResponse;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.dto.RuleListRequest;
import com.axcore.platform.ontology.dto.RuleRevisionResponse;
import com.axcore.platform.ontology.dto.RuleRevisionSearchRequest;
import com.axcore.platform.ontology.dto.RuleSummaryResponse;
import com.axcore.platform.ontology.service.RuleRevisionService;
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

/** 연결 규칙 목록·개정 이력·개정본 상세 (API 046·047, FN-RUL-01). 모두 ADMIN만 쓸 수 있다. */
@RestController
@RequestMapping("/api/v1/ontology/rules")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class RuleRevisionController {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final RuleRevisionService ruleRevisionService;

    /** 연결 규칙 목록(186 후속): 규칙 코드마다 최신 개정본. 정의·규칙 이력 화면의 규칙 선택에 쓴다. */
    @GetMapping
    public ApiResponse<List<RuleSummaryResponse>> rules(
            @Valid @ModelAttribute RuleListRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        return ApiResponse.of(ruleRevisionService.rules(request));
    }

    @GetMapping("/{code}/revisions")
    public ApiResponse<List<RuleRevisionResponse>> revisions(
            @PathVariable("code") String code,
            @Valid @ModelAttribute RuleRevisionSearchRequest request,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        int page = request.page() == null ? 0 : request.page();
        int size = request.size() == null ? DEFAULT_PAGE_SIZE : request.size();
        return ApiResponse.ofPage(ruleRevisionService.revisions(code, PageRequest.of(page, size)));
    }

    @GetMapping("/{code}/revisions/{revision}")
    public ApiResponse<RuleRevisionResponse> revision(
            @PathVariable("code") String code,
            @PathVariable("revision") @Min(value = 1, message = "revision은 1 이상이어야 합니다.")
                    long revision,
            @AuthenticationPrincipal LoginAccount loginAccount) {
        requireAdmin(loginAccount);
        return ApiResponse.of(ruleRevisionService.revision(code, revision));
    }

    private static void requireAdmin(LoginAccount loginAccount) {
        if (!RoleCode.ADMIN.name().equals(loginAccount.roleCode())) {
            throw new BusinessException(
                    CommonErrorCode.FORBIDDEN, Map.of("requiredPermission", RoleCode.ADMIN.name()));
        }
    }
}
