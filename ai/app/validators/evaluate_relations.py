"""독립 데이터 관계 채점 (정답 형식: data/eval/b*/answer.json).

    .\\.venv\\Scripts\\python.exe -m app.validators.evaluate_relations results\\proposals-b6.json data\\eval\\b6\\answer.json

정답의 복합키는 'sys.t.a+b'와 'sys.t.a+sys.t.b' 두 표기를 모두 받는다.
"""

import argparse
import json
import sys
from pathlib import Path


def full_locator(locator: str) -> str:
    """'sys.table.a+b' → 'sys.table.a+sys.table.b'"""
    system, table, columns = locator.split(".", 2)
    prefix = f"{system}.{table}."
    return "+".join(
        c if c.startswith(prefix) else prefix + c for c in columns.split("+")
    )


def evaluate(result: dict, answer: dict) -> dict:
    gold = {
        (full_locator(r["from"]), full_locator(r["to"])): r for r in answer["relations"]
    }
    found: dict = {}
    reversed_, wrong = [], []
    for c in result["candidates"]:
        if c["payload"]["kind"] != "LINK_RULE":
            continue
        pair = (
            full_locator(c["evidence"][0]["locator"]),
            full_locator(c["evidence"][1]["locator"]),
        )
        if pair in gold:
            # 같은 관계가 규칙·LLM으로 둘 다 나오면 대기를 우선해 센다
            if found.get(pair) != "PENDING":
                found[pair] = c["review_status"]
        elif (pair[1], pair[0]) in gold:
            reversed_.append(pair)
        else:
            wrong.append((pair, c["review_status"]))
    traps = {t["column"] for t in answer.get("traps", [])}
    cross = {k for k in gold if k[0].split(".")[0] != k[1].split(".")[0]}
    return {
        "gold": len(gold),
        "found_pending": sum(s == "PENDING" for s in found.values()),
        "found_held": sum(s == "HELD" for s in found.values()),
        "cross_system_found": f"{sum(k in found for k in cross)}/{len(cross)}",
        "missed": [f"{a} -> {b}" for a, b in gold if (a, b) not in found],
        "reversed": [f"{a} -> {b}" for a, b in reversed_],
        "wrong_pending": [f"{a} -> {b}" for (a, b), s in wrong if s == "PENDING"],
        "wrong_held": sum(s == "HELD" for _, s in wrong),
        "trap_hits": [f"{a} -> {b} ({s})" for (a, b), s in wrong if a in traps],
        "review_units": result.get("summary", {}).get("review_units"),
    }


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="독립 데이터 관계 채점")
    parser.add_argument("result", type=Path, help="app.proposers.run 결과 JSON")
    parser.add_argument("answer", type=Path, help="data/eval/b*/answer.json")
    args = parser.parse_args(argv)
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    report = evaluate(
        json.loads(args.result.read_text(encoding="utf-8")),
        json.loads(args.answer.read_text(encoding="utf-8")),
    )
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
