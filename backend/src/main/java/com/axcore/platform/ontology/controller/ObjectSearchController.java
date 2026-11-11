package com.axcore.platform.ontology.controller;

import com.axcore.platform.global.response.ApiResponse;
import com.axcore.platform.global.response.PageMeta;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.dto.ObjectDetailResponse;
import com.axcore.platform.ontology.dto.ObjectLinkResponse;
import com.axcore.platform.ontology.dto.ObjectLinkSearchRequest;
import com.axcore.platform.ontology.dto.ObjectSearchRequest;
import com.axcore.platform.ontology.dto.ObjectSearchResponse;
import com.axcore.platform.ontology.dto.ObjectTypeOptionResponse;
import com.axcore.platform.ontology.service.ObjectDetailQuery;
import com.axcore.platform.ontology.service.ObjectSearchQuery;
import com.axcore.platform.ontology.service.ObjectTypeOptionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** API 076 객체 검색, 077 상세, 078 활성 관계 조회, 검색 조건 유형 선택지(347). */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class ObjectSearchController {

    private final ObjectSearchQuery objects;
    private final ObjectDetailQuery details;
    private final ObjectTypeOptionService typeOptions;

    @GetMapping("/api/v1/objects")
    public ApiResponse<List<ObjectSearchResponse>> search(
            @Valid @ModelAttribute ObjectSearchRequest request,
            @AuthenticationPrincipal LoginAccount actor) {
        return paged(objects.search(actor, request));
    }

    /** 검색 조건 드롭다운용. 076과 같은 권한 범위의 유형만, 이름순으로 모두 돌려준다(페이지 없음). */
    @GetMapping("/api/v1/objects/types")
    public ApiResponse<List<ObjectTypeOptionResponse>> types(
            @AuthenticationPrincipal LoginAccount actor) {
        return ApiResponse.of(typeOptions.options(actor));
    }

    @GetMapping("/api/v1/objects/{id}")
    public ApiResponse<ObjectDetailResponse> detail(
            @PathVariable @Positive long id, @AuthenticationPrincipal LoginAccount actor) {
        return new ApiResponse<>(details.detail(actor, id), null);
    }

    @GetMapping("/api/v1/objects/{id}/links")
    public ApiResponse<List<ObjectLinkResponse>> links(
            @PathVariable @Positive long id,
            @Valid @ModelAttribute ObjectLinkSearchRequest request,
            @AuthenticationPrincipal LoginAccount actor) {
        return paged(details.links(actor, id, request));
    }

    private static <T> ApiResponse<List<T>> paged(Page<T> page) {
        // Page.hasNext()는 page가 Integer.MAX_VALUE이면 다음 페이지 번호를 계산할 때 int 범위를 넘는다.
        boolean hasNext = page.getPageable().getOffset() + page.getSize() < page.getTotalElements();
        return new ApiResponse<>(
                page.getContent(),
                new PageMeta(page.getNumber(), page.getSize(), page.getTotalElements(), hasNext));
    }
}
