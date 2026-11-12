package com.axcore.platform.audit.repository;

import com.axcore.platform.audit.domain.EntityChange;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EntityChangeRepository extends JpaRepository<EntityChange, Long> {

    /** 실행 기록 하나가 바꾼 행. 남긴 순서대로다. */
    List<EntityChange> findByActionExecutionIdOrderByIdAsc(Long actionExecutionId);
}
