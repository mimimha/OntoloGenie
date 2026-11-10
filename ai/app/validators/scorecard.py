"""평가 데이터 전체 점수표 (규칙만, LLM 없음). 발표·회귀 확인용으로 명령 하나로 다시 만든다.

    .\\.venv\\Scripts\\python.exe -m app.validators.scorecard
    .\\.venv\\Scripts\\python.exe -m app.validators.scorecard --data b8 b9

개발용 A(data/data-v1.0 ↔ gold-v1.0)와 독립 데이터 data/eval/<이름>/(data + answer.json)를 분석해
찾은 관계(대기 + 보류), 시스템 간 연결, 대기 오답, 보류 오답, 점수 구간별 실제 정답 비율을 보인다.
LLM(sol) 결과는 크레딧을 쓰므로 여기서 돌리지 않는다(docs/evaluation.md).
"""

import argparse
import json
import sys
from pathlib import Path

from app.proposers.run import run
from app.validators.evaluate_ontology import evaluate as evaluate_ontology
from app.validators.evaluate_relations import evaluate as evaluate_relations
from app.validators.evaluate_relations import full_locator

AI = Path(__file__).resolve().parents[2]
DATA = AI / "data"
BANDS = (
    (0.9, "0.9 이상"),
    (0.8, "0.8~0.89"),
    (0.6, "0.6~0.79"),
    (0.4, "0.4~0.59"),
    (0.0, "0.39 이하"),
)


def _band(score: float) -> str:
    return next(label for low, label in BANDS if score >= low)


def _rule_pairs(result: dict) -> list[tuple[tuple[str, str], float]]:
    out = []
    for c in result["candidates"]:
        if c["payload"]["kind"] != "LINK_RULE":
            continue
        src, dst = c["evidence"][0]["locator"], c["evidence"][1]["locator"]
        out.append(
            ((full_locator(src), full_locator(dst)), c.get("ai_confidence") or 0.0)
        )
    return out


def score_independent(name: str) -> tuple[dict, list]:
    folder = DATA / "eval" / name
    answer = json.loads((folder / "answer.json").read_text(encoding="utf-8"))
    result = run(folder / "data")
    report = evaluate_relations(result, answer)
    gold = {
        (full_locator(r["from"]), full_locator(r["to"])) for r in answer["relations"]
    }
    row = {
        "data": name.upper(),
        "found": f"{report['found_pending'] + report['found_held']}/{report['gold']}",
        "cross": report["cross_system_found"],
        "wrong_pending": len(report["wrong_pending"]),
        "wrong_held": report["wrong_held"],
    }
    return row, [(pair in gold, s) for pair, s in _rule_pairs(result)]


def score_dev() -> tuple[dict, list]:
    result = run(DATA / "data-v1.0")
    links = evaluate_ontology(result["candidates"], DATA / "gold-v1.0")["links"]
    wrong = links["wrong_candidates"]
    row = {
        "data": "A",
        "found": f"{links['found_pending'] + links['found_held']}/{links['gold']}",
        "cross": "-",
        "wrong_pending": sum(w["status"] == "PENDING" for w in wrong),
        "wrong_held": sum(w["status"] == "HELD" for w in wrong),
    }
    return row, []  # A는 정답 대조 방식이 달라 점수 구간 표에는 넣지 않는다


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="평가 데이터 점수표 (규칙만)")
    parser.add_argument(
        "--data", nargs="*", help="a 또는 data/eval 아래 이름(b, b2, … b9). 없으면 전부"
    )
    args = parser.parse_args(argv)
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    names = args.data or ["a"] + sorted(
        p.name for p in (DATA / "eval").iterdir() if p.is_dir()
    )
    rows, scored = [], []
    for name in names:
        row, pairs = (
            score_dev() if name.lower() == "a" else score_independent(name.lower())
        )
        rows.append(row)
        scored += pairs
        print(
            f"{row['data']:4s} 찾은 관계 {row['found']:7s} 시스템 간 {row['cross']:5s} 대기 오답 {row['wrong_pending']} 보류 오답 {row['wrong_held']}",
            flush=True,
        )
    if scored:
        print("점수 구간별 실제 정답 비율 (독립 데이터 연결 규칙)")
        for _, label in BANDS:
            hits = [ok for ok, s in scored if _band(s) == label]
            if hits:
                print(
                    f"  {label:9s} {sum(hits)}/{len(hits)} = {sum(hits) / len(hits):.0%}"
                )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
