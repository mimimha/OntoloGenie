package com.axcore.platform.check.service;

import com.axcore.platform.check.dto.CheckRunResponse;
import com.axcore.platform.check.dto.ReverificationComparisonResponse.Category;
import com.axcore.platform.check.dto.ReverificationComparisonResponse.Coverage;
import com.axcore.platform.check.dto.ReverificationComparisonResponse.Evidence;
import com.axcore.platform.check.dto.ReverificationComparisonResponse.Row;
import com.axcore.platform.check.repository.ReverificationComparisonRepository;
import com.axcore.platform.check.repository.ReverificationComparisonRepository.StoredRow;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReverificationComparisonService {

    private final CheckRunQueryService runs;
    private final ReverificationComparisonRepository evidence;

    public Comparison get(long id, Pageable page) {
        CheckRunResponse run = runs.get(id);
        Long baseline = run.comparedToRunId();
        if (!"REVERIFY".equals(run.triggerKind()) || baseline == null) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION, "재검증 실행만 비교할 수 있습니다.", Map.of());
        }
        if (!"SUCCEEDED".equals(run.status())) {
            return new Comparison(baseline, Coverage.UNAVAILABLE, false, Page.empty(page));
        }

        Map<RowKey, Evidence> previous = new HashMap<>();
        for (StoredRow row : evidence.findEvidence(baseline)) {
            previous.put(new RowKey(row.ruleCode(), row.objectInstanceId()), row.evidence());
        }

        boolean hasBaselinePassEvidence =
                previous.values().stream().anyMatch(row -> "MATCHED".equals(row.classification()));
        boolean comparable = false;
        List<Row> changes = new ArrayList<>();
        for (StoredRow row : evidence.findEvidence(id)) {
            Evidence before = previous.get(new RowKey(row.ruleCode(), row.objectInstanceId()));
            if (before == null) {
                continue;
            }
            comparable = true;
            Evidence after = row.evidence();
            Category category = category(before, after);
            if (category != null) {
                changes.add(
                        new Row(category, row.ruleCode(), row.objectInstanceId(), before, after));
            }
        }
        int start = (int) Math.min(page.getOffset(), changes.size());
        int end = (int) Math.min((long) start + page.getPageSize(), changes.size());
        Coverage coverage = comparable ? Coverage.PARTIAL : Coverage.UNAVAILABLE;
        return new Comparison(
                baseline,
                coverage,
                hasBaselinePassEvidence,
                new PageImpl<>(changes.subList(start, end), page, changes.size()));
    }

    private static Category category(Evidence before, Evidence after) {
        boolean previouslyMatched = "MATCHED".equals(before.classification());
        boolean currentlyMatched = "MATCHED".equals(after.classification());
        if (previouslyMatched) {
            return currentlyMatched ? null : Category.NEW;
        }
        return currentlyMatched ? Category.RESOLVED : Category.REMAINING;
    }

    private record RowKey(String ruleCode, long objectInstanceId) {}

    public record Comparison(
            long comparedToRunId,
            Coverage coverage,
            boolean hasBaselinePassEvidence,
            Page<Row> rows) {}
}
