package com.axcore.platform.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.agent.domain.AgentMessage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

/**
 * 334 대화 문맥 유지 범위 비교 실험. 같은 질문 세트로 최근 대화 원문의 턴 수(2·4·8)와 토큰 상한(1000·2000·4000)을 바꿔 질문 해석 AI를 실제로
 * 부르고, 조합별 정답률과 입력 토큰을 표로 남긴다. 원문을 넘기지 않은 경우(0턴)도 비교 기준으로 함께 부른다.
 *
 * <p>GMS 크레딧을 쓰므로 AXCORE_AGENT_LLM=openai와 키에 더해 AXCORE_AGENT_EVAL=context를 켰을 때만 돈다. 평소 테스트와 MR
 * 검사에서는 건너뛴다. 결과는 build/agent-eval/에 Markdown 표와 호출별 CSV로 쓴다. AXCORE_AGENT_EVAL_SCENARIOS(앞에서부터 쓸
 * 질문 수)와 AXCORE_AGENT_EVAL_REPEAT(반복 횟수)로 작게 돌려 볼 수 있다. AXCORE_AGENT_EVAL_COMBOS(예:
 * 0:0,2:2000,3:2000)로 부를 조합만 고를 수도 있다(턴 수:토큰 상한, 0:0은 원문 없음).
 */
@Slf4j
@SpringBootTest(properties = "test.context=gms-check")
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
@EnabledIfEnvironmentVariable(named = "AXCORE_AGENT_LLM", matches = "openai")
@EnabledIfEnvironmentVariable(named = "AXCORE_AGENT_EVAL", matches = "context")
class ContextWindowExperimentGmsTests {
    private static final String SCENARIOS = "/agent-eval/context-window-scenarios.json";

    /** 질문 세트의 "오늘"(2026-11-11 KST). 상대 기간을 같은 날짜로 계산하게 고정한다. */
    private static final Clock TODAY =
            Clock.fixed(Instant.parse("2026-11-11T03:00:00Z"), ZoneOffset.UTC);

    private static final List<Combo> DEFAULT_COMBOS =
            List.of(
                    new Combo(0, 0),
                    new Combo(2, 1000),
                    new Combo(2, 2000),
                    new Combo(2, 4000),
                    new Combo(4, 1000),
                    new Combo(4, 2000),
                    new Combo(4, 4000),
                    new Combo(8, 1000),
                    new Combo(8, 2000),
                    new Combo(8, 4000));

    private static final List<Integer> DISTANCES = List.of(1, 2, 3, 4, 5, 7, 0);

    /** 동시에 부를 요청 수. GMS 요청 한도를 넘지 않게 작게 둔다. */
    private static final int PARALLEL = 4;

    @Autowired ChatModel chatModel;

    @Test
    void comparesContextWindows() throws Exception {
        List<Scenario> scenarios = scenarios();
        int repeat = envInt("AXCORE_AGENT_EVAL_REPEAT", 3);
        List<Combo> combos = combos();
        List<Call> calls = new ArrayList<>();
        for (Combo combo : combos) {
            for (Scenario scenario : scenarios) {
                for (int run = 1; run <= repeat; run++) {
                    calls.add(new Call(combo, scenario, run));
                }
            }
        }
        log.info(
                "334 실험: 조합 {}개 × 질문 {}개 × {}번 = {}회 호출",
                combos.size(),
                scenarios.size(),
                repeat,
                calls.size());

        List<Outcome> outcomes = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(PARALLEL)) {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Call call : calls) {
                futures.add(pool.submit(() -> run(call)));
            }
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get());
            }
        }

        Path dir = Path.of("build", "agent-eval");
        Files.createDirectories(dir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path table = dir.resolve("context-window-" + stamp + ".md");
        Files.writeString(table, summary(outcomes, combos, scenarios.size(), repeat));
        Files.writeString(dir.resolve("context-window-" + stamp + ".csv"), csv(outcomes));
        log.info("334 실험 결과: {}\n{}", table.toAbsolutePath(), Files.readString(table));

        assertThat(outcomes).hasSize(calls.size());
    }

    private Outcome run(Call call) {
        Scenario scenario = call.scenario();
        List<ConversationTurn> turns =
                call.combo().turns() == 0
                        ? List.of()
                        : new RecentTurns(call.combo().turns(), call.combo().tokens())
                                .pick(messages(scenario));
        UsageRecordingChatModel model = new UsageRecordingChatModel(chatModel);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("chatModel", model);
        QuestionInterpreter interpreter =
                new QuestionInterpreter(beans.getBeanProvider(ChatModel.class), TODAY);
        QuestionInterpretation previous = scenario.previous().toInterpretation();
        try {
            QuestionInterpretation asked =
                    interpreter.interpret(scenario.followUp(), Map.of(), null, previous, turns);
            // 답변 처리기처럼 직전 답변의 조건에 이어 붙인 최종 조건으로 채점한다.
            QuestionInterpretation used = asked.continueFrom(previous);
            return Outcome.of(call, turns.size(), model.promptTokens, asked, used, null);
        } catch (RuntimeException ex) {
            log.warn("334 실험 호출 실패 {} {}", call.combo(), scenario.id(), ex);
            return Outcome.of(call, turns.size(), model.promptTokens, null, null, ex.toString());
        }
    }

    /** 질문 세트의 이전 대화를 저장된 메시지처럼 만든다. 모두 끝까지 답한 턴이다. */
    private static List<AgentMessage> messages(Scenario scenario) {
        Instant at = Instant.parse("2026-11-11T00:00:00Z");
        List<AgentMessage> messages = new ArrayList<>();
        for (Turn turn : scenario.turns()) {
            messages.add(AgentMessage.question(1L, turn.question(), Map.of(), at));
            AgentMessage answer = AgentMessage.runningAnswer(1L, at);
            answer.succeed(turn.answer(), Map.of());
            messages.add(answer);
        }
        return messages;
    }

    private static List<Scenario> scenarios() throws IOException {
        try (InputStream in =
                ContextWindowExperimentGmsTests.class.getResourceAsStream(SCENARIOS)) {
            List<Scenario> all =
                    JsonMapper.builder().build().readValue(in, ScenarioSet.class).scenarios();
            int limit = envInt("AXCORE_AGENT_EVAL_SCENARIOS", all.size());
            return all.subList(0, Math.min(limit, all.size()));
        }
    }

    /** AXCORE_AGENT_EVAL_COMBOS가 있으면 그 조합만 부른다. 예: 0:0,2:2000,3:2000 */
    private static List<Combo> combos() {
        String value = System.getenv("AXCORE_AGENT_EVAL_COMBOS");
        if (value == null || value.isBlank()) {
            return DEFAULT_COMBOS;
        }
        return Arrays.stream(value.split(","))
                .map(pair -> pair.strip().split(":"))
                .map(pair -> new Combo(Integer.parseInt(pair[0]), Integer.parseInt(pair[1])))
                .toList();
    }

    private static int envInt(String name, int fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value.strip());
    }

    private static String summary(
            List<Outcome> outcomes, List<Combo> combos, int scenarioCount, int repeat) {
        StringBuilder text = new StringBuilder();
        text.append("# 334 대화 문맥 유지 범위 비교 결과\n\n")
                .append("- 질문 ")
                .append(scenarioCount)
                .append("개 × 반복 ")
                .append(repeat)
                .append("번, 조합마다 ")
                .append(scenarioCount * repeat)
                .append("회 호출\n")
                .append("- 정답: 최종 대상(직전 조건에 이어 붙인 뒤)이 정답과 같고, 정답에 기간이 있으면 기간도 같음\n")
                .append("- 입력 토큰: 모델 응답의 usage.promptTokens\n\n")
                .append("| 조합 | 정답률 | ")
                .append(
                        DISTANCES.stream()
                                .map(distance -> distance == 0 ? "관계없는 질문" : distance + "턴 전")
                                .collect(Collectors.joining(" | ")))
                .append(" | 평균 입력 토큰 | 최대 입력 토큰 | 평균 포함 턴 | 숫자 옮김 | 호출 실패 |\n")
                .append("|---".repeat(DISTANCES.size() + 7))
                .append("|\n");
        for (Combo combo : combos) {
            List<Outcome> rows =
                    outcomes.stream().filter(row -> row.combo().equals(combo)).toList();
            if (rows.isEmpty()) {
                continue;
            }
            IntSummaryStatistics tokens =
                    rows.stream()
                            .map(Outcome::promptTokens)
                            .filter(Objects::nonNull)
                            .mapToInt(Integer::intValue)
                            .summaryStatistics();
            text.append("| ").append(combo.label());
            text.append(" | ").append(rate(rows, row -> true));
            for (int distance : DISTANCES) {
                text.append(" | ").append(rate(rows, row -> row.distance() == distance));
            }
            text.append(" | ")
                    .append(tokens.getCount() == 0 ? "-" : Math.round(tokens.getAverage()))
                    .append(" | ")
                    .append(tokens.getCount() == 0 ? "-" : tokens.getMax())
                    .append(" | ")
                    .append(
                            String.format(
                                    Locale.ROOT,
                                    "%.1f",
                                    rows.stream()
                                            .mapToInt(Outcome::turnsIncluded)
                                            .average()
                                            .orElse(0)))
                    .append(" | ")
                    .append(rows.stream().filter(Outcome::numberInPurpose).count())
                    .append(" | ")
                    .append(rows.stream().filter(row -> row.error() != null).count())
                    .append(" |\n");
        }
        text.append("\n## 틀린 질문 (조합별)\n\n");
        for (Combo combo : combos) {
            Map<String, Long> wrong =
                    outcomes.stream()
                            .filter(row -> row.combo().equals(combo) && !row.correct())
                            .collect(
                                    Collectors.groupingBy(
                                            Outcome::scenarioId,
                                            TreeMap::new,
                                            Collectors.counting()));
            if (!wrong.isEmpty()) {
                text.append("- ").append(combo.label()).append(": ").append(wrong).append('\n');
            }
        }
        return text.toString();
    }

    private static String rate(List<Outcome> rows, Predicate<Outcome> filter) {
        List<Outcome> picked = rows.stream().filter(filter).toList();
        if (picked.isEmpty()) {
            return "-";
        }
        long correct = picked.stream().filter(Outcome::correct).count();
        return String.format(
                Locale.ROOT,
                "%.0f%% (%d/%d)",
                100.0 * correct / picked.size(),
                correct,
                picked.size());
    }

    private static String csv(List<Outcome> outcomes) {
        StringBuilder text =
                new StringBuilder(
                        "combo,scenario,distance,run,correct,targetOk,periodOk,turnsIncluded,promptTokens,targets,kinds,from,to,purpose,error\n");
        for (Outcome row : outcomes) {
            text.append(
                    String.join(
                            ",",
                            row.combo().label(),
                            row.scenarioId(),
                            String.valueOf(row.distance()),
                            String.valueOf(row.run()),
                            String.valueOf(row.correct()),
                            String.valueOf(row.targetOk()),
                            String.valueOf(row.periodOk()),
                            String.valueOf(row.turnsIncluded()),
                            String.valueOf(row.promptTokens()),
                            quote(String.join("|", row.targets())),
                            quote(String.join("|", row.kinds())),
                            String.valueOf(row.from()),
                            String.valueOf(row.to()),
                            quote(row.purpose()),
                            quote(row.error())));
            text.append('\n');
        }
        return text.toString();
    }

    private static String quote(String value) {
        return value == null ? "" : '"' + value.replace("\"", "\"\"").replace('\n', ' ') + '"';
    }

    record Combo(int turns, int tokens) {
        String label() {
            return turns == 0 ? "원문 없음" : turns + "턴·" + tokens;
        }
    }

    record Call(Combo combo, Scenario scenario, int run) {}

    record ScenarioSet(String description, String today, List<Scenario> scenarios) {}

    record Scenario(
            String id,
            int distance,
            List<Turn> turns,
            Previous previous,
            String followUp,
            Expected expected) {}

    record Turn(String question, String answer) {}

    record Previous(
            String purpose,
            List<String> targets,
            String from,
            String to,
            List<AgentReferenceObject> referenceObjects) {
        QuestionInterpretation toInterpretation() {
            return new QuestionInterpretation(
                    purpose,
                    targets,
                    from == null ? null : LocalDate.parse(from),
                    to == null ? null : LocalDate.parse(to),
                    referenceObjects,
                    null);
        }
    }

    /** toAlternatives는 to와 함께 정답으로 보는 끝 날짜다. 예: "이번 달"은 오늘까지와 월말까지 둘 다 맞다. */
    record Expected(
            List<String> targets,
            String from,
            String to,
            List<String> toAlternatives,
            boolean wholeKind) {

        boolean periodMatches(LocalDate usedFrom, LocalDate usedTo) {
            if (from == null) {
                return true;
            }
            if (!LocalDate.parse(from).equals(usedFrom) || usedTo == null) {
                return false;
            }
            return usedTo.equals(LocalDate.parse(to))
                    || (toAlternatives != null
                            && toAlternatives.stream()
                                    .map(LocalDate::parse)
                                    .anyMatch(usedTo::equals));
        }
    }

    record Outcome(
            Combo combo,
            String scenarioId,
            int distance,
            int run,
            int turnsIncluded,
            Integer promptTokens,
            boolean targetOk,
            boolean periodOk,
            List<String> targets,
            List<String> kinds,
            LocalDate from,
            LocalDate to,
            String purpose,
            boolean numberInPurpose,
            String error) {

        boolean correct() {
            return error == null && targetOk && periodOk;
        }

        static Outcome of(
                Call call,
                int turnsIncluded,
                Integer promptTokens,
                QuestionInterpretation asked,
                QuestionInterpretation used,
                String error) {
            Scenario scenario = call.scenario();
            if (used == null) {
                return new Outcome(
                        call.combo(),
                        scenario.id(),
                        scenario.distance(),
                        call.run(),
                        turnsIncluded,
                        promptTokens,
                        false,
                        false,
                        List.of(),
                        List.of(),
                        null,
                        null,
                        null,
                        false,
                        error);
            }
            Expected expected = scenario.expected();
            boolean targetOk =
                    expected.wholeKind()
                            ? used.targets().isEmpty() && !used.kinds().isEmpty()
                            : names(used.targets()).equals(names(expected.targets()));
            boolean periodOk = expected.periodMatches(used.from(), used.to());
            return new Outcome(
                    call.combo(),
                    scenario.id(),
                    scenario.distance(),
                    call.run(),
                    turnsIncluded,
                    promptTokens,
                    targetOk,
                    periodOk,
                    used.targets(),
                    used.kinds(),
                    used.from(),
                    used.to(),
                    used.purpose(),
                    numberInPurpose(asked.purpose(), scenario.followUp()),
                    null);
        }

        /** 띄어쓰기와 대소문자만 다른 이름은 같은 대상으로 본다. */
        private static Set<String> names(List<String> values) {
            return values.stream()
                    .map(value -> value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());
        }

        /** 질문에 없던 숫자가 목적에 들어갔는가. 이전 답변의 조회 결과 숫자를 옮겨 쓴 경우다. */
        private static boolean numberInPurpose(String purpose, String question) {
            if (purpose == null) {
                return false;
            }
            return purpose.chars()
                    .filter(Character::isDigit)
                    .anyMatch(digit -> question.indexOf(digit) < 0);
        }
    }

    /** 실제 모델을 부르고 응답에 실린 입력 토큰 수를 남긴다. 호출마다 새로 만든다. */
    private static final class UsageRecordingChatModel implements ChatModel {
        private final ChatModel delegate;
        private volatile Integer promptTokens;

        UsageRecordingChatModel(ChatModel delegate) {
            this.delegate = delegate;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            ChatResponse response = delegate.call(prompt);
            Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
            promptTokens = usage == null ? null : usage.getPromptTokens();
            return response;
        }

        @Override
        public ChatOptions getOptions() {
            return delegate.getOptions();
        }
    }
}
