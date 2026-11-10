package com.axcore.platform.ontology.service;

import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.dto.ObjectDetailResponse;
import com.axcore.platform.ontology.dto.ObjectLinkResponse;
import com.axcore.platform.ontology.dto.ObjectLinkSearchRequest;
import org.springframework.data.domain.Page;

/** #137의 077 객체 상세와 078 활성 관계 조회 계약. */
public interface ObjectDetailQuery {

    /** 민감 속성 값만 권한에 맞게 가린다. 객체가 없거나 조회 범위 밖이면 RESOURCE_NOT_FOUND로 처리한다. */
    ObjectDetailResponse detail(LoginAccount actor, long objectId);

    /**
     * 기준 객체가 없거나 조회 범위 밖이면 RESOURCE_NOT_FOUND로 처리한다. 활성 관계 중 상대 객체도 볼 수 있는 관계만 목록과 전체 건수에 포함하며, 보이는
     * 관계가 없으면 빈 페이지를 반환한다.
     */
    Page<ObjectLinkResponse> links(
            LoginAccount actor, long objectId, ObjectLinkSearchRequest request);
}
