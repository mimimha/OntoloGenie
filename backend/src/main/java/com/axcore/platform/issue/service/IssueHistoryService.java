package com.axcore.platform.issue.service;

import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.issue.dto.IssueHistoryResponse;
import com.axcore.platform.issue.repository.IssueHistoryRepository;
import com.axcore.platform.issue.repository.IssueRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** API 069 문제 상태 변경 이력. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IssueHistoryService {

    private final IssueRepository issueRepository;
    private final IssueHistoryRepository issueHistoryRepository;

    public Page<IssueHistoryResponse> search(long issueId, Pageable pageable) {
        // 이력이 없다는 이유만으로 문제가 없다고 보지 않는다.
        if (!issueRepository.existsById(issueId)) {
            throw new BusinessException(
                    CommonErrorCode.RESOURCE_NOT_FOUND, Map.of("resource", "issue"));
        }
        return issueHistoryRepository.findByIssue(issueId, pageable);
    }
}
