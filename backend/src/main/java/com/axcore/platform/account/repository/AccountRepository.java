package com.axcore.platform.account.repository;

import com.axcore.platform.account.domain.Account;
import com.axcore.platform.account.domain.RoleCode;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, Long> {

    @EntityGraph(attributePaths = "role")
    Optional<Account> findByLoginId(String loginId);

    @EntityGraph(attributePaths = "role")
    Optional<Account> findWithRoleById(Long id);

    boolean existsByRole_Code(RoleCode code);

    /**
     * 실패 횟수를 1 올리고, maxFailures에 닿으면 잠근다. 동시에 틀려도 횟수가 빠지지 않게 DB에서 한 번에 바꾼다. 로그인 기록은 관리자 수정이 아니므로
     * row_version과 수정 시각·계정은 바꾸지 않는다.
     */
    @Modifying
    @Query(
            nativeQuery = true,
            value =
                    """
                    update account
                    set failed_login_count = failed_login_count + 1,
                        status = case when failed_login_count + 1 >= :maxFailures
                                      then 'LOCKED' else status end
                    where id = :id and status = 'ACTIVE'
                    """)
    int recordLoginFailure(@Param("id") Long id, @Param("maxFailures") int maxFailures);

    @Modifying
    @Query(
            nativeQuery = true,
            value =
                    """
                    update account
                    set failed_login_count = 0, last_login_at = :loggedInAt
                    where id = :id
                    """)
    int recordLoginSuccess(@Param("id") Long id, @Param("loggedInAt") Instant loggedInAt);
}
