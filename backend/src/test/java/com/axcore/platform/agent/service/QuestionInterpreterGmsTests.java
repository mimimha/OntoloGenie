package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.TestcontainersConfiguration;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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

    @Test
    void interpretsQuestionWithRealAi() {
        assertThat(interpreter.isConfigured()).isTrue();

        QuestionInterpretation result =
                interpreter.interpret("PR-03 이번 달 비가동 이력 보여줘", Map.of(), null);
        log.info("GMS 해석 결과: {}", result);

        assertThat(result.targets()).contains("PR-03");
        assertThat(result.hasPeriod()).isTrue();
    }
}
