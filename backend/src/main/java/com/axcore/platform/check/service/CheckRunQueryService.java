package com.axcore.platform.check.service;

import com.axcore.platform.check.dto.CheckRunResponse;
import com.axcore.platform.check.dto.CheckRunSearchRequest;
import com.axcore.platform.check.repository.CheckRunRepository;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** API 059 검사 실행 목록, 061 검사 상태 조회. SYNC·CANDIDATE 실행도 함께 보여 준다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CheckRunQueryService {

    private final CheckRunRepository checkRunRepository;

    public Page<CheckRunResponse> search(CheckRunSearchRequest request, Pageable pageable) {
        return checkRunRepository.search(request, pageable);
    }

    public CheckRunResponse get(long id) {
        return checkRunRepository
                .findById(id)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        CommonErrorCode.RESOURCE_NOT_FOUND,
                                        Map.of("resource", "check_run")));
    }
}
