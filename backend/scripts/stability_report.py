"""Summarize repeated, fixed-rubric Agent evaluations without additional AI calls."""

import argparse
import hashlib
import json
import statistics
from pathlib import Path


def analyze(rows: list[dict], models: list[str], cases: list[str], repeat: int) -> dict:
    expected = {
        (model, run, case)
        for model in models
        for run in range(1, repeat + 1)
        for case in cases
    }
    indexed = {}
    for row in rows:
        key = (row["model"], row["run"], row["caseId"])
        if key in indexed:
            raise ValueError("중복된 평가 결과가 있습니다.")
        if key not in expected or not isinstance(row["passed"], bool):
            raise ValueError("평가 결과가 실행 계획과 다릅니다.")
        indexed[key] = row
    if set(indexed) != expected:
        raise ValueError("평가 결과가 아직 완성되지 않았거나 일부 문항이 없습니다.")
    summaries = []
    for model in models:
        model_rows = [row for row in rows if row["model"] == model]
        rounds = []
        for run in range(1, repeat + 1):
            subset = [row for row in model_rows if row["run"] == run]
            rounds.append(
                {
                    "run": run,
                    "passed": sum(row["passed"] for row in subset),
                    "total": len(subset),
                    "medianSeconds": round(
                        statistics.median(row["elapsedMs"] for row in subset) / 1000, 2
                    ),
                }
            )
        always_passed, variable, always_failed = [], [], []
        for case in cases:
            attempts = [indexed[(model, run, case)] for run in range(1, repeat + 1)]
            marks = [row["passed"] for row in attempts]
            detail = {
                "caseId": case,
                "passed": marks,
                "statuses": [row["status"] for row in attempts],
                "failedChecks": [
                    [
                        name
                        for name in (
                            "targetOk",
                            "toolsOk",
                            "factsOk",
                            "periodOk",
                            "forbiddenOk",
                        )
                        if not row.get(name, True)
                    ]
                    for row in attempts
                ],
            }
            if all(marks):
                always_passed.append(case)
            elif any(marks):
                variable.append(detail)
            else:
                always_failed.append(detail)
        elapsed = [row["elapsedMs"] for row in model_rows]
        p95 = (
            statistics.quantiles(elapsed, n=20, method="inclusive")[18]
            if len(elapsed) > 1
            else elapsed[0]
        )
        summaries.append(
            {
                "model": model,
                "passed": sum(row["passed"] for row in model_rows),
                "total": len(model_rows),
                "passRate": round(
                    100 * sum(row["passed"] for row in model_rows) / len(model_rows), 2
                ),
                "rounds": rounds,
                "alwaysPassed": always_passed,
                "variable": variable,
                "alwaysFailed": always_failed,
                "medianSeconds": round(statistics.median(elapsed) / 1000, 2),
                "p95Seconds": round(p95 / 1000, 2),
                "errors": sum(row["status"] == "ERROR" for row in model_rows),
                "promptTokens": sum(row.get("promptTokens", 0) for row in model_rows),
                "completionTokens": sum(
                    row.get("completionTokens", 0) for row in model_rows
                ),
            }
        )
    return {
        "repeat": repeat,
        "cases": len(cases),
        "total": len(rows),
        "models": summaries,
    }


def write_report(run: Path, reviewed: bool = False) -> dict:
    manifest = json.loads((run / "manifest.json").read_text(encoding="utf-8"))
    questions = (run / "questions-original.json").read_bytes()
    if hashlib.sha256(questions).hexdigest() != manifest["questionsSha256"]:
        raise ValueError("고정한 질문의 해시가 실행 기록과 다릅니다.")
    verification = json.loads(
        (run / "db-verification.json").read_text(encoding="utf-8")
    )
    if not verification["unchanged"]:
        raise ValueError("평가 중 DB 검사값이 달라졌습니다.")
    spec = json.loads(questions)
    source = "outcomes-reviewed.jsonl" if reviewed else "outcomes.jsonl"
    grading = "original-frozen"
    if reviewed:
        revision = json.loads(
            (run / "grading-revision.json").read_text(encoding="utf-8")
        )
        if revision["originalQuestionsSha256"] != manifest["questionsSha256"]:
            raise ValueError("재검토 근거와 원본 질문의 해시가 다릅니다.")
        if (
            hashlib.sha256((run / "questions-reviewed.json").read_bytes()).hexdigest()
            != revision["reviewQuestionsSha256"]
        ):
            raise ValueError("재검토 채점 기준의 해시가 기록과 다릅니다.")
        grading = revision["revision"]
    rows = [
        json.loads(line)
        for line in (run / source).read_text(encoding="utf-8").splitlines()
    ]
    result = analyze(
        rows,
        manifest["models"],
        [turn["id"] for turn in spec["turns"]][
            : manifest["plannedTurns"] // len(manifest["models"]) // manifest["repeat"]
        ],
        manifest["repeat"],
    )
    private = [row for row in rows if row.get("role") == "VIEWER"]
    guide = [row for row in rows if row["caseId"] == "guide-wear-followup"]
    result.update(
        {
            "gradingRevision": grading,
            "questionsSha256": manifest["questionsSha256"],
            "snapshotSha256": manifest["snapshot"]["snapshotSha256"],
            "parallel": manifest.get("parallel", 1),
            "reasoningEffort": manifest.get("answerReasoningEffort", "none"),
            "dbUnchanged": True,
            "restrictedCases": len(private),
            "forbiddenValueViolations": sum(not row["forbiddenOk"] for row in private),
            "guideTargetMaintained": sum(
                row["status"] == "ANSWERED" and row["targetOk"] for row in guide
            ),
            "guideCases": len(guide),
        }
    )
    name = "stability-reviewed" if reviewed else "stability"
    (run / (name + ".json")).write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    lines = [
        "# 모델 반복 안정성 비교",
        "",
        f"동일 합성 DB·고정 질문 {result['cases']}개·모델별 {result['repeat']}회, 총 {result['total']}개 결과입니다.",
        "",
        "안정성은 답변 문구의 일치가 아닌 동일 채점 기준의 통과/미달 일관성으로 계산했습니다. 항상 통과한 문항과 항상 미달한 문항을 구분합니다.",
        f"채점 기준: {grading}. 원본 자동 판정과 재검토 결과를 구분해 보존합니다.",
        "",
        "| 모델 | 회차별 통과 | 전체 통과 | 매번 통과한 문항 | 판정 변동 문항 | 매번 미달 문항 | 중앙 응답 | p95 |",
        "|---|---|---:|---:|---:|---:|---:|---:|",
    ]
    for model in result["models"]:
        passes = " / ".join(str(item["passed"]) for item in model["rounds"])
        lines.append(
            f"| {model['model']} | {passes} | {model['passed']}/{model['total']} | "
            f"{len(model['alwaysPassed'])}/{result['cases']} | {len(model['variable'])} | {len(model['alwaysFailed'])} | "
            f"{model['medianSeconds']:.2f}초 | {model['p95Seconds']:.2f}초 |"
        )
    lines.extend(["", "## 판정이 달라진 문항", ""])
    for model in result["models"]:
        for case in model["variable"]:
            marks = " → ".join("통과" if mark else "미달" for mark in case["passed"])
            checks = "; ".join(
                ",".join(names) or status
                for names, status in zip(case["failedChecks"], case["statuses"])
            )
            lines.append(f"- {model['model']} / {case['caseId']}: {marks} ({checks})")
    lines.extend(["", "## 매번 미달한 문항", ""])
    for model in result["models"]:
        for case in model["alwaysFailed"]:
            lines.append(
                f"- {model['model']} / {case['caseId']}: {case['failedChecks']}"
            )
    lines.extend(
        [
            "",
            f"가이드 마모 후속질문 대상 유지: {result['guideTargetMaintained']}/{result['guideCases']}.",
            f"권한 제한 {result['restrictedCases']}개 실행 중 금지 값 검사 위반: {result['forbiddenValueViolations']}개.",
            f"DB 검사값은 실행 전후 동일했습니다. 질문 원본·{result['total']}개 원문 답변과 조회 기록·실행 조건은 같은 디렉터리에 보존했습니다.",
            "",
            "한 문항을 3번 실행한 소규모 비교입니다. 4개 모델 동시 실행 부하와 GMS 응답 변동이 시간에 포함됩니다. 자동 검사는 모든 문장의 의미 정확도를 보장하지 않습니다. 토큰은 SDK 보고 값이며 비용으로 환산하지 않았습니다.",
            "",
        ]
    )
    (run / (name + ".md")).write_text("\n".join(lines), encoding="utf-8")
    return result


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--run", type=Path, required=True)
    cli.add_argument("--reviewed", action="store_true")
    args = cli.parse_args()
    write_report(args.run, args.reviewed)
    print(args.run / ("stability-reviewed.md" if args.reviewed else "stability.md"))
