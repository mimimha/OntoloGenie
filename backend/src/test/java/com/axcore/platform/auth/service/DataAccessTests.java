package com.axcore.platform.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.auth.repository.DataScopeRepository.ObjectTypeScope;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DataAccessTests {

    /** 유형 코드로도 ID로 확인할 때와 같게 판단한다(148 완료 시각 계산이 쓴다). */
    @Test
    void checksVisibilityByTypeCodeLikeById() {
        DataAccess access =
                new DataAccess(
                        List.of(
                                new ObjectTypeScope(1L, "WorkOrder", true, false, Set.of()),
                                new ObjectTypeScope(2L, "FixedAsset", false, false, Set.of())));

        assertThat(access.canView("WorkOrder")).isTrue().isEqualTo(access.canView(1L));
        assertThat(access.canView("FixedAsset")).isFalse().isEqualTo(access.canView(2L));
        assertThat(access.canView("Unknown")).isFalse();
    }
}
