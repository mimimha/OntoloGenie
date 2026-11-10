package com.axcore.platform.check.repository;

import com.axcore.platform.check.domain.CheckResult;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CheckResultRepository
        extends JpaRepository<CheckResult, Long>, JpaSpecificationExecutor<CheckResult> {

    /**
     * 이 규칙 개정본의 가장 최근 결과(검사 실행기가 새 결과의 previous_result_id로 쓴다). 결과는 검사가 SUCCEEDED가 되는 트랜잭션에서만 저장되므로
     * 성공한 검사의 결과다.
     */
    Optional<CheckResult> findTopByLinkMappingIdOrderByIdDesc(Long linkMappingId);
}
