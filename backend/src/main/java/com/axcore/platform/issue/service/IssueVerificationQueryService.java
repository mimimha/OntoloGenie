package com.axcore.platform.issue.service;

import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.issue.dto.IssueVerificationResponse;
import com.axcore.platform.issue.repository.IssueRepository;
import com.axcore.platform.issue.repository.IssueVerificationRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IssueVerificationQueryService {

    private final IssueRepository issues;
    private final IssueVerificationRepository verifications;

    public Page<IssueVerificationResponse> search(long issueId, Pageable pageable) {
        if (!issues.existsById(issueId)) {
            throw new BusinessException(
                    CommonErrorCode.RESOURCE_NOT_FOUND, Map.of("resource", "issue"));
        }
        return verifications.findByIssue(issueId, pageable);
    }
}
