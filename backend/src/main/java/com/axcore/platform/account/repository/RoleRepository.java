package com.axcore.platform.account.repository;

import com.axcore.platform.account.domain.Role;
import com.axcore.platform.account.domain.RoleCode;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByCode(RoleCode code);
}
