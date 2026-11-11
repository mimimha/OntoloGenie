package com.axcore.platform.agent.service;

import com.axcore.platform.audit.service.AuditExecutionQueryService;
import com.axcore.platform.auth.service.DataAccessPolicy;
import com.axcore.platform.ontology.service.ModelSummaryService;
import com.axcore.platform.ontology.service.ObjectDetailQuery;
import com.axcore.platform.ontology.service.ObjectEvidenceQuery;
import com.axcore.platform.ontology.service.ObjectSearchQuery;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * 조회 도구와 실행할 수 있는 Action 도구를 주고 AI가 답변 문장을 쓰게 한다(144). 어떤 도구를 부를지는 AI가 고르고, 부를 때마다 서버가 기록한다. 값은 도구
 * 결과에서만 쓰게 하고, 정보별 결과 유형·기준 시점은 서버 기록으로 따로 남긴다(145).
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AgentResponder {
    private static final String SYSTEM_PROMPT =
            """
            너는 제조 설비·작업 데이터 조회 Agent다. 사용자의 질문에 한국어로 짧게 답한다.
            - 값은 도구로 조회한 결과에서만 쓴다. 도구 결과에 없는 값은 지어내지 않는다.
            - 이전 답변의 값은 다시 쓰지 않는다. 같은 정보라도 이번에 도구로 조회한 결과로 답한다.
            - 조회할 도구가 없는 정보는 아직 조회할 수 없는 정보라고 말한다.
            - 답변에 도구, Tool, resultCategory 같은 내부 용어를 쓰지 않는다. 사용자가 쓰는 업무 말로 답한다.
            - resultCategory가 NO_DATA면 해당 데이터가 없다고 말한다.
            - resultCategory가 UNLINKED면 연결된 정보가 없다고 말한다.
            - resultCategory가 DENIED면 그 정보를 볼 권한이 없다고 말한다.
            - restrictedPropertyCodes에 있는 속성은 값이 없는 것이 아니라 볼 권한이 없는 것이다. 값이 없다고 말하지 않고 볼 권한이 없다고 말한다.
            - 도구가 error를 돌려주면 그 정보는 확인하지 못했다고 말하고, 데이터가 없다고 바꿔 말하지 않는다.
            - 객체 ID는 "참고 객체 ID"나 이번 답변의 조회 도구 결과에 나온 값만 쓴다. 객체 ID를 지어내지 않는다.
            - "종류 전체"가 비어 있지 않으면 객체 하나를 조회하는 도구는 부르지 않는다.
            - 질문의 대상은 "참고 객체"로 이미 정해져 있다. 그 밖의 객체를 이름이나 코드로만 알면 객체 검색으로 먼저 찾는다. 후보가 여럿이면 어느 것인지 되묻는다.
            - 모델 요약은 사용자가 객체 유형이나 관계 유형을 이름으로 말해 그 유형으로 좁혀야 할 때만 부른다. 이름·코드로 객체를 찾거나 관계를 모두 볼 때는 부르지 않는다.
              유형 ID는 모델 요약 결과에 나온 값만 쓰고 지어내지 않는다.
            - 질문한 정보를 조회할 도구가 없으면 다른 도구 결과로 대신 답하지 않고, 아직 조회할 수 없는 정보라고 말한다.
              예: 작업지시 목록을 조회할 도구가 없으면 "작업지시가 없다"가 아니라 "아직 조회할 수 없다"고 말한다.
            - 객체에 연결된 정보(예: 설비에 배정된 작업지시)는 관계를 조회해 답한다. 관계 조회로 찾을 수 있으면 "아직 조회할 수 없다"고 말하지 않는다.
            - "그럼 지난달은?"처럼 짧은 후속 질문도 "확인한 조건"의 목적·대상·기간으로 답한다. 조건은 이전 질문에서 이어 온 것이다.
            - "되물은 내용"이 있으면 "사용자의 답"은 되물은 것에 대한 답이다. "두 번째 거"는 되물은 후보 중 두 번째를 고른 말이고,
              이력의 순서나 개수가 아니다. 고른 대상·조건은 "확인한 조건"에 이미 들어 있으니 "확인한 조건"의 목적·대상·기간으로 처음 물은 것에 답한다.
            - Action 도구(조회 도구가 아닌 것)는 사용자가 그 일을 해 달라고 분명히 요청했을 때만 부른다.
            - Action 입력의 기준 시점(dataAsOf)은 계획에 쓴 조회 결과의 기준 시점 중 가장 이른 값을 쓴다. 조회한 기준 시점이 없을 때만 "현재 시각"을 쓴다.
            - 그 밖에 시각이 필요하면 "현재 시각"을 기준으로 정한다. 사용자가 "지금"이라고 하면 현재 시각이다.
            - 재배정 계획을 만들 때는 selectionBasis.reason에 대체 설비를 고른 이유를 한 문장으로 적는다.
            - Action 도구가 CONFIRMATION_REQUIRED를 돌려주면 실행하지 않았다고 말하고 실행할지 묻는다.
            - Action 도구가 BLOCKED나 NOT_RUN을 돌려주면 실행되지 않았다고 말하고 이유를 그대로 전한다.
            """;

    /** "지금" 같은 표현은 사용자 기준 시각으로 계산한다. */
    private static final ZoneId USER_ZONE = ZoneId.of("Asia/Seoul");

    private final ObjectProvider<ChatModel> chatModels;
    private final AuditExecutionQueryService executions;
    private final ObjectSearchQuery objectSearch;
    private final ObjectProvider<ObjectDetailQuery> objectDetails;
    private final ObjectProvider<ObjectEvidenceQuery> objectEvidence;
    private final ModelSummaryService modelSummary;
    private final DataAccessPolicy dataAccess;
    private final AgentActionTools actionTools;
    private final Clock clock;

    /**
     * 도구를 쓰는 이 요청에 보내는 추론 강도(147). GMS chat/completions는 함수 도구와 추론을 함께 쓰는 것을 거절하므로 기본값은
     * "none"이다(gpt-6-luna 배포 확인 2026-11-06, gpt-5.6-sol 등 로컬 확인 2026-11-10). 모델마다 받는 값이 달라 설정으로 두고,
     * 비우면 옵션을 보내지 않는다. 추론을 끌 수 없는 gpt-6-astra·gpt-6.1-sol은 이 방식으로 도구를 쓸 수 없다. 도구가 없는 질문 해석
     * 요청(QuestionInterpreter)에는 넣지 않는다.
     */
    private final String reasoningEffort;

    public AgentResponder(
            ObjectProvider<ChatModel> chatModels,
            AuditExecutionQueryService executions,
            ObjectSearchQuery objectSearch,
            ObjectProvider<ObjectDetailQuery> objectDetails,
            ObjectProvider<ObjectEvidenceQuery> objectEvidence,
            ModelSummaryService modelSummary,
            DataAccessPolicy dataAccess,
            AgentActionTools actionTools,
            Clock clock,
            @Value("${axcore.agent.reasoning-effort:none}") String reasoningEffort) {
        this.chatModels = chatModels;
        this.executions = executions;
        this.objectSearch = objectSearch;
        this.objectDetails = objectDetails;
        this.objectEvidence = objectEvidence;
        this.modelSummary = modelSummary;
        this.dataAccess = dataAccess;
        this.actionTools = actionTools;
        this.clock = clock;
        this.reasoningEffort = reasoningEffort;
    }

    /**
     * AI 호출이 실패하거나 답이 비면 예외를 던진다. 부르는 쪽이 답변을 실패로 끝낸다. askedClarification은 이번 질문이 되묻기에 대한 답일 때 그 되물은
     * 문장이고, 아니면 null이다. 함께 알려 주지 않으면 "두 번째 거"를 이력의 두 번째로 읽는다(147 로컬 확인).
     */
    public String answer(
            String question,
            QuestionInterpretation interpretation,
            AgentQuerySession session,
            String askedClarification) {
        // 설정이 비어 있으면 추론 옵션을 보내지 않고 모델 기본값에 맡긴다.
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder();
        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            options.reasoningEffort(reasoningEffort);
        }
        String content =
                ChatClient.create(chatModels.getObject())
                        .prompt()
                        .system(SYSTEM_PROMPT)
                        .user(userPrompt(question, interpretation, askedClarification))
                        .options(options)
                        .tools(tools(session))
                        .call()
                        .content();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("AI 답변이 비어 있습니다.");
        }
        return content.strip();
    }

    /** 상세·관계·근거 조회는 구현(S15P31C106-137·138)이 배포된 뒤에만 준다. 부를 수 없는 도구를 AI에게 보여 주지 않는다. */
    private Object[] tools(AgentQuerySession session) {
        List<Object> tools = new ArrayList<>();
        tools.add(new AgentQueryTools(session, executions, objectSearch));
        tools.add(new AgentModelSummaryTools(session, modelSummary, dataAccess));
        objectDetails.ifAvailable(query -> tools.add(new AgentObjectDetailTools(session, query)));
        objectEvidence.ifAvailable(
                query -> tools.add(new AgentObjectEvidenceTools(session, query)));
        tools.add(actionTools.forAnswer(session));
        return tools.toArray();
    }

    /** AI는 지금 시각을 모르므로 함께 알려 준다. 알려 주지 않으면 "지금"을 날짜의 0시처럼 지어낸다(160 로컬 시험). */
    private String userPrompt(
            String question, QuestionInterpretation interpretation, String askedClarification) {
        String asked =
                askedClarification == null
                        ? "질문: " + question
                        : "되물은 내용: " + askedClarification + "\n사용자의 답: " + question;
        return """
                현재 시각: %s
                확인한 조건: 목적=%s, 대상=%s, 종류 전체=%s, 기간=%s, 참고 객체 ID=%s, 참고 객체=%s
                %s"""
                .formatted(
                        OffsetDateTime.now(clock.withZone(USER_ZONE))
                                .truncatedTo(ChronoUnit.SECONDS),
                        interpretation.purpose(),
                        interpretation.targets(),
                        interpretation.kinds(),
                        interpretation.hasPeriod()
                                ? interpretation.from() + " ~ " + interpretation.to()
                                : "없음",
                        interpretation.referenceObjectIds(),
                        interpretation.referenceObjects().stream()
                                .map(AgentReferenceObject::describe)
                                .toList(),
                        asked);
    }
}
