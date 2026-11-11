package com.axcore.platform.agent.service;

import com.axcore.platform.audit.domain.AgentResultCategory;
import com.axcore.platform.audit.domain.ExecutionStatus;
import com.axcore.platform.audit.dto.AuditExecutionResponse;
import com.axcore.platform.audit.dto.ObjectExecutionSearchRequest;
import com.axcore.platform.audit.service.AuditExecutionQueryService;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.ontology.dto.ObjectSearchRequest;
import com.axcore.platform.ontology.dto.ObjectSearchResponse;
import com.axcore.platform.ontology.service.ObjectSearchQuery;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * AI에게 주는 조회 도구(144, 326). 답변 하나마다 새로 만든다. 각 도구는 기존 조회 API의 Service를 질문한 사용자로 불러 권한 규칙을 그대로 지키고,
 * 호출은 {@link AgentQuerySession}이 기록한다. 객체 상세·관계·관계 근거 도구는 구현이 있을 때만 {@link AgentObjectDetailTools}·
 * {@link AgentObjectEvidenceTools}로 더한다.
 */
public class AgentQueryTools {
    static final String OBJECT_EXECUTIONS = "OBJECT_EXECUTIONS";
    static final String OBJECT_SEARCH = "OBJECT_SEARCH";
    static final int MAX_ROWS = 20;
    static final String OBJECT_INSTANCE = "object_instance";

    /** 질문의 날짜는 사용자 기준 날짜다. */
    private static final ZoneId USER_ZONE = ZoneId.of("Asia/Seoul");

    private final AgentQuerySession session;
    private final AuditExecutionQueryService executions;
    private final ObjectSearchQuery objects;

    public AgentQueryTools(
            AgentQuerySession session,
            AuditExecutionQueryService executions,
            ObjectSearchQuery objects) {
        this.session = session;
        this.executions = executions;
        this.objects = objects;
    }

    /** 실행 이력 한 줄. 입력·결과 원문은 AI에게 넘기지 않는다. */
    public record ExecutionSummary(
            Long id, String actionName, ExecutionStatus status, Instant requestedAt) {

        static ExecutionSummary from(AuditExecutionResponse response) {
            return new ExecutionSummary(
                    response.id(),
                    response.actionName(),
                    response.status(),
                    response.requestedAt());
        }
    }

    /**
     * 검색 결과 한 건. 속성은 076이 권한에 따라 가린 값이다. 가린 속성은 키를 남기고 값만 null이라, restrictedPropertyCodes로 "값 없음"과
     * "볼 권한 없음"을 구분한다(076과 같은 정보이며 질문한 사용자에게 이미 보이는 값이다). dataAsOf는 객체 속성의 업무 기준일이고, 답변 결과의 기준
     * 시점(테이블 확인 시각)과 다르다(정책 문서 4.1).
     */
    public record ObjectSummary(
            long id,
            long objectTypeId,
            String objectTypeName,
            String displayName,
            Map<String, Object> properties,
            List<String> restrictedPropertyCodes,
            ObjectSearchRequest.Status status,
            Instant dataAsOf) {

        static ObjectSummary from(ObjectSearchResponse response) {
            return new ObjectSummary(
                    response.id(),
                    response.objectTypeId(),
                    response.objectTypeName(),
                    response.displayName(),
                    response.properties(),
                    response.restrictedPropertyCodes() == null
                            ? List.of()
                            : response.restrictedPropertyCodes(),
                    response.status(),
                    response.dataAsOf());
        }
    }

    /**
     * API 080. 플랫폼이 직접 남긴 실행 기록이라 원천 수집 시각이 없어 기준 시점(dataAsOf)은 null이다. ADMIN이 아니면 본인이 실행한 기록만 보인다.
     * 기간은 사용자 날짜로 받아 두 날짜를 모두 포함하게 바꾼다(080은 to를 포함하지 않는다).
     */
    @Tool(
            name = OBJECT_EXECUTIONS,
            description = "객체에 대해 실행된 Action·작업 이력을 최근 순으로 최대 20건 조회한다. 기간을 주면 그 사이에 요청된 이력만 조회한다.")
    public Map<String, Object> objectExecutions(
            @ToolParam(description = "객체 ID") Long objectId,
            @ToolParam(description = "기간 시작일(yyyy-MM-dd, 이 날 포함). 기간이 없으면 비운다", required = false)
                    String from,
            @ToolParam(description = "기간 종료일(yyyy-MM-dd, 이 날 포함). 기간이 없으면 비운다", required = false)
                    String to) {
        Map<String, Object> input = new LinkedHashMap<>();
        putIfPresent(input, "objectId", objectId);
        putIfPresent(input, "from", from);
        putIfPresent(input, "to", to);
        return session.run(
                OBJECT_EXECUTIONS,
                "실행 이력",
                input,
                targetTypeOf(objectId),
                objectId,
                () ->
                        noDataIfMissing(
                                () -> {
                                    long id = required(objectId, "objectId");
                                    List<ExecutionSummary> rows =
                                            executions
                                                    .objectExecutions(
                                                            session.asker(),
                                                            id,
                                                            new ObjectExecutionSearchRequest(
                                                                    startOf(from),
                                                                    endOf(to),
                                                                    null,
                                                                    null,
                                                                    null),
                                                            PageRequest.of(0, MAX_ROWS))
                                                    .map(ExecutionSummary::from)
                                                    .getContent();
                                    return new AgentQuerySession.Outcome(
                                            rows,
                                            rows.isEmpty()
                                                    ? AgentResultCategory.NO_DATA
                                                    : AgentResultCategory.FOUND,
                                            List.of(Map.of("objectId", id)),
                                            null);
                                }));
    }

    /**
     * API 076. 볼 수 있는 객체 유형만 찾고 민감 속성은 가린다(205). 원천에서 삭제된 객체는 관리자만 찾을 수 있고, 그 밖의 사용자는 DENIED다. 기준
     * 시점(테이블 확인 시각)은 S15P31C106-145에서 채운다.
     */
    @Tool(
            name = OBJECT_SEARCH,
            description = "볼 수 있는 객체를 이름·코드로 검색해 최대 20건을 돌려준다. 결과의 id를 다른 조회 도구의 객체 ID로 쓴다.")
    public Map<String, Object> objectSearch(
            @ToolParam(description = "검색어. 객체 이름이나 코드의 일부(예: PR-03)", required = false) String q,
            @ToolParam(description = "객체 유형 ID. 모르면 비운다", required = false) Long objectTypeId,
            @ToolParam(
                            description = "ACTIVE(기본, 사용 중인 객체) 또는 DELETED_AT_SOURCE(원천에서 삭제된 객체)",
                            required = false)
                    String status) {
        Map<String, Object> input = new LinkedHashMap<>();
        putIfPresent(input, "q", q);
        putIfPresent(input, "objectTypeId", objectTypeId);
        putIfPresent(input, "status", status);
        return session.run(
                OBJECT_SEARCH,
                "객체 검색",
                input,
                null,
                null,
                () -> {
                    try {
                        Page<ObjectSearchResponse> page =
                                objects.search(
                                        session.asker(),
                                        new ObjectSearchRequest(
                                                q,
                                                objectTypeId,
                                                enumOf(ObjectSearchRequest.Status.class, status),
                                                0,
                                                MAX_ROWS));
                        List<ObjectSummary> rows =
                                page.getContent().stream().map(ObjectSummary::from).toList();
                        Map<String, Object> data = new LinkedHashMap<>();
                        data.put("total", page.getTotalElements());
                        data.put("objects", rows);
                        return new AgentQuerySession.Outcome(
                                data,
                                rows.isEmpty()
                                        ? AgentResultCategory.NO_DATA
                                        : AgentResultCategory.FOUND,
                                rows.stream()
                                        .map(row -> Map.<String, Object>of("objectId", row.id()))
                                        .toList(),
                                null);
                    } catch (BusinessException ex) {
                        if (ex.getErrorCode() != CommonErrorCode.FORBIDDEN) {
                            throw ex;
                        }
                        return new AgentQuerySession.Outcome(
                                List.of(), AgentResultCategory.DENIED, List.of(), null);
                    }
                });
    }

    /** 대상 객체가 없거나 볼 수 없으면 조회 서비스가 RESOURCE_NOT_FOUND를 던진다. 실패가 아니라 데이터 없음으로 답한다. */
    static AgentQuerySession.Outcome noDataIfMissing(Supplier<AgentQuerySession.Outcome> query) {
        try {
            return query.get();
        } catch (BusinessException ex) {
            if (ex.getErrorCode() != CommonErrorCode.RESOURCE_NOT_FOUND) {
                throw ex;
            }
            return new AgentQuerySession.Outcome(
                    List.of(), AgentResultCategory.NO_DATA, List.of(), null);
        }
    }

    static void putIfPresent(Map<String, Object> input, String key, Object value) {
        if (value != null) {
            input.put(key, value);
        }
    }

    /*
     * Spring AI는 도구 인자를 메서드 밖에서 바꾼다. enum·primitive 인자로 받으면 AI가 잘못 보낸 값에서 메서드가 불리지 않아 조회 기록이 빠진다.
     * 그래서 문자열·Long으로 받고 session.run 안에서 바꾼다. 바꾸지 못하면 실패로 기록된다.
     */

    static long required(Long value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + "가 없습니다.");
        }
        return value;
    }

    static <E extends Enum<E>> E enumOf(Class<E> type, String value) {
        return value == null || value.isBlank()
                ? null
                : Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
    }

    /** 기록의 대상은 객체 ID가 있을 때만 남긴다(action_execution은 대상 종류와 ID를 함께 채운다). */
    static String targetTypeOf(Long objectId) {
        return objectId == null ? null : OBJECT_INSTANCE;
    }

    private static Instant startOf(String date) {
        return date == null || date.isBlank()
                ? null
                : LocalDate.parse(date.strip()).atStartOfDay(USER_ZONE).toInstant();
    }

    private static Instant endOf(String date) {
        return date == null || date.isBlank()
                ? null
                : LocalDate.parse(date.strip()).plusDays(1).atStartOfDay(USER_ZONE).toInstant();
    }
}
