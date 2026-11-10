package com.axcore.platform.ontology.controller;

import com.axcore.platform.global.response.ApiResponse;
import com.axcore.platform.global.response.PageMeta;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.dto.ObjectSearchRequest;
import com.axcore.platform.ontology.dto.ObjectSearchResponse;
import com.axcore.platform.ontology.service.ObjectSearchQuery;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestController;

/** API 076 권한 범위 객체 검색. */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequiredArgsConstructor
public class ObjectSearchController {

    private final ObjectSearchQuery objects;

    @GetMapping("/api/v1/objects")
    public ApiResponse<List<ObjectSearchResponse>> search(
            @Valid @ModelAttribute ObjectSearchRequest request,
            @AuthenticationPrincipal LoginAccount actor) {
        Page<ObjectSearchResponse> page = objects.search(actor, request);
        // Page.hasNext()는 page가 Integer.MAX_VALUE이면 다음 페이지 번호를 계산할 때 int 범위를 넘는다.
        boolean hasNext = page.getPageable().getOffset() + page.getSize() < page.getTotalElements();
        return new ApiResponse<>(
                page.getContent(),
                new PageMeta(page.getNumber(), page.getSize(), page.getTotalElements(), hasNext));
    }
}
