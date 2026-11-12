package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.TestcontainersConfiguration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

// 실제 GMS로 질문 해석을 확인한다. AXCORE_AGENT_LLM=openai와 키를 넣은 PC에서만 돌고, 없으면 건너뛴다.
@Slf4j
@SpringBootTest(properties = "test.context=gms-check")
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
@EnabledIfEnvironmentVariable(named = "AXCORE_AGENT_LLM", matches = "openai")
class QuestionInterpreterGmsTests {
    @Autowired QuestionInterpreter interpreter;

    private static final QuestionInterpretation PREVIOUS_DOWNTIME =
            new QuestionInterpretation(
                    "비가동 이력",
                    List.of("P103"),
                    LocalDate.parse("2026-08-01"),
                    LocalDate.parse("2026-08-31"),
                    List.of(new AgentReferenceObject(301L, "P103", "설비")),
                    null);

    @ParameterizedTest
    @ValueSource(strings = {"가이드 마모는 언제 발생했어?", "사유가 가이드 마모인 것만 보여줘", "그중 가이드 마모는 몇 건이야?"})
    void keepsPreviousEquipmentWhenFollowUpMentionsReason(String question) {
        QuestionInterpretation asked = interpretFollowUp(question);
        QuestionInterpretation continued = asked.continueFrom(PREVIOUS_DOWNTIME);

        assertThat(asked.targets()).isEmpty();
        assertThat(asked.kinds()).isEmpty();
        assertThat(continued.targets()).containsExactly("P103");
        assertThat(continued.referenceObjects()).isEqualTo(PREVIOUS_DOWNTIME.referenceObjects());
        assertThat(continued.from()).isEqualTo(PREVIOUS_DOWNTIME.from());
        assertThat(continued.to()).isEqualTo(PREVIOUS_DOWNTIME.to());
        assertThat(continued.purpose()).contains("가이드 마모");
    }

    @Test
    void switchesEquipmentWhenCodeAndReasonAreBothMentioned() {
        QuestionInterpretation asked = interpretFollowUp("P104에서 가이드 마모가 발생한 이력은?");
        QuestionInterpretation continued = asked.continueFrom(PREVIOUS_DOWNTIME);

        assertThat(asked.targets()).containsExactly("P104");
        assertThat(continued.referenceObjects()).isEmpty();
        assertThat(continued.purpose()).contains("가이드 마모");
    }

    private QuestionInterpretation interpretFollowUp(String question) {
        return interpreter.interpret(
                question,
                Map.of(),
                null,
                PREVIOUS_DOWNTIME,
                List.of(new ConversationTurn("P103의 8월 비가동 이력 보여줘", "P103의 비가동 사유에 가이드 마모가 있습니다.")),
                null);
    }

    @Test
    void interpretsQuestionWithRealAi() {
        assertThat(interpreter.isConfigured()).isTrue();

        QuestionInterpretation result =
                interpreter.interpret(
                        "PR-03 이번 달 비가동 이력 보여줘", Map.of(), null, null, List.of(), null);
        log.info("GMS 해석 결과: {}", result);

        assertThat(result.targets()).contains("PR-03");
        assertThat(result.hasPeriod()).isTrue();
    }
}
