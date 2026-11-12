package com.axcore.platform.agent.service;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 완료 시각 계산(FN-AGT-09, S15P31C106-148)이 읽는 온톨로지 객체 유형 코드와 속성 코드. 실제 온톨로지는 AI 분석·관리자 승인으로 만들어지므로 코드에
 * 적지 않고 설정으로 둔다. 유형 코드 기본값은 운영 온톨로지 코드를 임시로 적은 것이다(application.yml 주석 참고). 값이 빠지면 서버가 시작하지 않는다.
 */
@Validated
@ConfigurationProperties("axcore.agent.completion-estimate")
public record CompletionEstimateProperties(
        @NotNull @Valid WorkOrder workOrder,
        @NotNull @Valid Operation operation,
        @NotNull @Valid Routing routing,
        @NotNull @Valid Equipment equipment) {

    /** 작업지시. 공정은 작업지시 번호로, 공정 표준은 제품 코드로 찾는다. */
    public record WorkOrder(
            @NotBlank String type, @NotBlank String number, @NotBlank String product) {}

    /**
     * 작업지시 공정별 배정. finishedStatuses에 든 공정은 계산에서 뺀다. runningStatuses에 든 공정은 이미 준비(금형 교체)를 마쳤다고 보고 준비
     * 시간을 넣지 않는다.
     */
    public record Operation(
            @NotBlank String type,
            @NotBlank String workOrder,
            @NotBlank String sequence,
            @NotBlank String equipment,
            @NotBlank String status,
            @NotBlank String remainingQuantity,
            @NotEmpty List<String> finishedStatuses,
            @NotNull List<String> runningStatuses) {}

    /** 제품별 공정 표준. 공정에 쓰는 설비 종류, 개당 표준 가공 시간(초), 표준 준비 시간(분). */
    public record Routing(
            @NotBlank String type,
            @NotBlank String product,
            @NotBlank String sequence,
            @NotBlank String equipmentKind,
            @NotBlank String cycleSeconds,
            @NotBlank String setupMinutes) {}

    /** 설비. "이 설비에서 하면"을 물으면 설비 종류(kind)가 공정 표준의 설비 종류와 같은 공정만 계산한다. */
    public record Equipment(@NotBlank String type, @NotBlank String code, @NotBlank String kind) {}
}
