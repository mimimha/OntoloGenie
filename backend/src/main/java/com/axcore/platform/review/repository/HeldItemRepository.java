package com.axcore.platform.review.repository;

import com.axcore.platform.review.domain.HeldItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface HeldItemRepository
        extends JpaRepository<HeldItem, Long>, JpaSpecificationExecutor<HeldItem> {}
