package com.axcore.platform.ontology.service;

import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.dto.ObjectSearchRequest;
import com.axcore.platform.ontology.dto.ObjectSearchResponse;
import java.util.List;
import org.springframework.data.domain.Page;

/** 076 객체 검색을 Explorer와 Agent가 같은 권한 기준으로 호출하는 계약. */
public interface ObjectSearchQuery {

    Page<ObjectSearchResponse> search(LoginAccount actor, ObjectSearchRequest request);

    /**
     * 볼 수 있는 유형의 문자 속성 중 값이 정확히 같은(앞뒤 공백 무시) 활성 객체를 id 순으로 limit건까지 찾는다(372). 076으로 찾지 못한 이름을 Agent
     * 대상 찾기가 다시 찾을 때 쓴다. 권한은 076과 같다. 민감 속성은 민감 값을 볼 수 있을 때만 찾는다.
     */
    List<ObjectSearchResponse> findByPropertyValue(LoginAccount actor, String value, int limit);
}
