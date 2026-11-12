"""Regrade saved diverse-suite responses without making additional model calls.

Preserves raw outcomes and requires authoritative candidate facts from the same snapshot.
"""

import argparse
import hashlib
import json
import re
from pathlib import Path

import agent_eval


def review(
    run: Path, questions: Path, candidate_gold: Path, revision: str = "diverse-review-2"
) -> list[dict]:
    manifest = json.loads((run / "manifest.json").read_text(encoding="utf-8"))
    original_bytes = (run / "questions-original.json").read_bytes()
    if hashlib.sha256(original_bytes).hexdigest() != manifest["questionsSha256"]:
        raise ValueError("원본 질문 스냅샷의 해시가 실행 기록과 다릅니다.")
    original = json.loads(original_bytes)["turns"]
    updated = json.loads(questions.read_text(encoding="utf-8"))["turns"]
    if [(t["id"], t["question"], t.get("role"), t["scenario"]) for t in original] != [
        (t["id"], t["question"], t.get("role"), t["scenario"]) for t in updated
    ]:
        raise ValueError("문항·순서·역할·시나리오가 달라 재채점할 수 없습니다.")
    gold = json.loads(candidate_gold.read_text(encoding="utf-8"))
    if gold["snapshotSha256"] != manifest["snapshot"]["snapshotSha256"]:
        raise ValueError("후보 정답의 DB 스냅샷이 실행 기록과 다릅니다.")
    cases = {turn["id"]: turn for turn in updated}
    rows = [
        json.loads(line)
        for line in (run / "outcomes.jsonl").read_text(encoding="utf-8").splitlines()
    ]
    reviewed = []
    shown_first = {}
    for source in rows:
        row = dict(source)
        case = cases[row["caseId"]]
        key = (row["model"], row["run"])
        row["originalPassed"] = row["passed"]
        row["gradingRevision"] = revision
        patterns = case["patterns"]
        if row["caseId"] == "ambiguous-name":
            choices = re.findall(r"번호\s*(\d+)", row["answer"])
            if choices:
                shown_first[key] = int(choices[0])
        if row["caseId"] == "pick-first-candidate":
            first = shown_first.get(key)
            row["targetOk"] = first == gold["id"] and (row.get("conditions") or {}).get(
                "referenceObjectIds"
            ) == [first]
            row["toolsOk"] = row["toolsOk"] and any(
                call.get("tool") == "OBJECT_DETAIL"
                and call.get("objectId") == first
                and call.get("succeeded")
                and call.get("category") == "FOUND"
                for call in row.get("toolCalls", [])
            )
            patterns = [re.escape(gold["status"])]
        row["factsOk"] = all(re.search(pattern, row["answer"]) for pattern in patterns)
        row["forbiddenOk"] = not any(
            re.search(pattern, row["answer"])
            for pattern in case.get("forbiddenPatterns", [])
        )
        row["passed"] = row["status"] == case["status"] and all(
            row.get(check, True)
            for check in ("targetOk", "toolsOk", "factsOk", "periodOk", "forbiddenOk")
        )
        reviewed.append(row)
    agent_eval.write_json(
        run / "grading-revision.json",
        {
            "revision": revision,
            "originalQuestionsSha256": manifest["questionsSha256"],
            "reviewQuestionsSha256": hashlib.sha256(questions.read_bytes()).hexdigest(),
            "candidateGold": gold,
            "changes": [
                "첫 번째 후보는 실제 되묻기 표시 순서 및 동일 DB의 상태 값으로 판정",
                "기간 밖 기록을 제외했다는 설명과 기간 밖 기록의 포함을 구분",
                "등록되지 않음·확인되지 않음 등 결측 정보의 동등한 표현 허용",
            ],
        },
    )
    (run / "questions-reviewed.json").write_bytes(questions.read_bytes())
    (run / "outcomes-reviewed.jsonl").write_text(
        "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in reviewed),
        encoding="utf-8",
    )
    lines = [
        "# 확장 합성 DB 모델 비교",
        "",
        f"{len(updated)}개 질문·{len({turn['scenario'] for turn in updated})}개 독립 시나리오·모델별 {manifest['repeat']}회·{len(manifest['models'])}개 모델, 총 {len(reviewed)}개 실행 결과입니다.",
        "",
        "원본 채점과 답변은 보존했습니다. 후보 표시 순서, 기간 제외 설명, 결측 정보 표현에 대한 판정 오류를 동일 기준으로 재검토했으며 추가 모델 호출은 하지 않았습니다.",
        "",
        agent_eval.summarize(reviewed),
        "",
        "## 시나리오별 통과",
        "",
    ]
    models = sorted({row["model"] for row in reviewed})
    lines.extend(
        ["| 시나리오 | " + " | ".join(models) + " |", "|---|" + "---:|" * len(models)]
    )
    for scenario in dict.fromkeys(turn["scenario"] for turn in updated):
        cells = []
        for model in models:
            subset = [
                row
                for row in reviewed
                if row["model"] == model and row["scenario"] == scenario
            ]
            cells.append(f"{sum(row['passed'] for row in subset)}/{len(subset)}")
        lines.append("| " + scenario + " | " + " | ".join(cells) + " |")
    lines.extend(["", "## 남은 기준 미달", ""])
    for row in reviewed:
        if not row["passed"]:
            checks = [
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
            lines.append(
                f"- {row['model']} / {row['caseId']}: {', '.join(checks) or row['status']}"
            )
    lines.extend(
        [
            "",
            f"{len(reviewed)}개 결과는 같은 DB를 읽기 전용으로 사용했습니다. 시간에는 동시 실행 부하가 포함되며 소규모 반복 결과로 일반적인 모델 순위를 확정하지 않습니다.",
            "",
        ]
    )
    (run / "comparison-reviewed.md").write_text("\n".join(lines), encoding="utf-8")
    return reviewed


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--run", type=Path, required=True)
    cli.add_argument("--questions", type=Path, required=True)
    cli.add_argument("--candidate-gold", type=Path, required=True)
    cli.add_argument("--revision", default="diverse-review-2")
    args = cli.parse_args()
    review(args.run, args.questions, args.candidate_gold, args.revision)
    print(args.run / "comparison-reviewed.md")
