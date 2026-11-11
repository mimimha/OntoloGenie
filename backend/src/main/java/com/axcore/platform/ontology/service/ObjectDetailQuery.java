package com.axcore.platform.ontology.service;

import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.dto.ObjectDetailResponse;
import com.axcore.platform.ontology.dto.ObjectLinkGroupResponse;
import com.axcore.platform.ontology.dto.ObjectLinkResponse;
import com.axcore.platform.ontology.dto.ObjectLinkSearchRequest;
import java.util.List;
import org.springframework.data.domain.Page;

/** #137의 077 객체 상세와 078 활성 관계 조회, 관계 유형별 묶음 건수(351) 계약. */
public interface ObjectDetailQuery {

    /** 민감 속성 값만 권한에 맞게 가린다. 객체가 없거나 조회 범위 밖이면 RESOURCE_NOT_FOUND로 처리한다. */
    ObjectDetailResponse detail(LoginAccount actor, long objectId);

    /**
     * 기준 객체가 없거나 조회 범위 밖이면 RESOURCE_NOT_FOUND로 처리한다. 활성 관계 중 상대 객체도 볼 수 있는 관계만 목록과 전체 건수에 포함하며, 보이는
     * 관계가 없으면 빈 페이지를 반환한다.
     */
    Page<ObjectLinkResponse> links(
            LoginAccount actor, long objectId, ObjectLinkSearchRequest request);

    /**
     * links와 같은 범위의 관계를 관계 유형·방향별 건수로 모두 돌려준다. 건수가 많은 묶음부터 놓는다. 기준 객체가 없거나 조회 범위 밖이면
     * RESOURCE_NOT_FOUND로 처리한다.
     */
    List<ObjectLinkGroupResponse> linkGroups(LoginAccount actor, long objectId);
}
