package com.axcore.platform.issue.repository;

import com.axcore.platform.issue.domain.Issue;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IssueRepository
        extends JpaRepository<Issue, Long>, JpaSpecificationExecutor<Issue> {

    /** 상태를 바꾸는 동안 다른 변경이 끼어들지 않도록 문제 행을 잠그고 읽는다. 기다리던 요청은 앞 변경이 끝난 뒤의 행을 읽는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Issue i where i.id = :id")
    Optional<Issue> findForUpdate(@Param("id") long id);
}
