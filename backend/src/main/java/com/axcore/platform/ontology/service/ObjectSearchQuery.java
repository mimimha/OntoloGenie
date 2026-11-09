package com.axcore.platform.ontology.service;

import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.dto.ObjectSearchRequest;
import com.axcore.platform.ontology.dto.ObjectSearchResponse;
import org.springframework.data.domain.Page;

/** 076 객체 검색을 Explorer와 Agent가 같은 권한 기준으로 호출하는 계약. */
public interface ObjectSearchQuery {

    Page<ObjectSearchResponse> search(LoginAccount actor, ObjectSearchRequest request);
}
