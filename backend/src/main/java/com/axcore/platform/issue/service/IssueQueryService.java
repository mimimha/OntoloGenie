package com.axcore.platform.issue.service;

import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.issue.domain.IssueKind;
import com.axcore.platform.issue.domain.IssueStatus;
import com.axcore.platform.issue.dto.IssueResponse;
import com.axcore.platform.issue.dto.IssueSearchRequest;
import com.axcore.platform.issue.repository.IssueRepository;
import com.axcore.platform.issue.repository.IssueSpecifications;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** API 065·066 정합성 문제 목록과 근거 조회. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IssueQueryService {

    // 최근에 등록된 문제부터 보여준다. 같은 시각이면 나중에 만든 문제가 먼저다.
    private static final Sort NEWEST_FIRST =
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final IssueRepository issueRepository;

    public Page<IssueResponse> search(IssueSearchRequest request, Pageable pageable) {
        return issueRepository
                .findAll(
                        IssueSpecifications.byFilters(
                                request.status() == null
                                        ? null
                                        : IssueStatus.valueOf(request.status()),
                                request.issueKind() == null
                                        ? null
                                        : IssueKind.valueOf(request.issueKind()),
                                request.linkMappingId(),
                                request.from(),
                                request.to()),
                        PageRequest.of(
                                pageable.getPageNumber(), pageable.getPageSize(), NEWEST_FIRST))
                .map(IssueResponse::from);
    }

    public IssueResponse getIssue(long id) {
        return issueRepository
                .findById(id)
                .map(IssueResponse::from)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        CommonErrorCode.RESOURCE_NOT_FOUND,
                                        Map.of("resource", "issue")));
    }
}
