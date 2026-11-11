"""CDC 변경 재현 시나리오: 단계별 기대 결과가 실제 분석 결과와 맞는지 본다."""

import json
import shutil
from pathlib import Path

import pandas as pd
import pytest

from app.analysis import engine
from app.analysis.engine import analyze
from app.dataset.build import generate
from app.models.analysis import AnalysisStartRequest


@pytest.fixture(scope="module")
def generated(tmp_path_factory):
    root = tmp_path_factory.mktemp("cdc")
    generate("t6", root=root)
    scenario = json.loads(
        (root / "scenario-t6" / "cdc_changes.json").read_text(encoding="utf-8")
    )
    return root / "data-t6", root / "scenario-t6", scenario["steps"]


@pytest.fixture(autouse=True)
def no_llm(monkeypatch):
    monkeypatch.setattr(engine, "get_llm", lambda: None)


def _rows(mes: Path, table: str) -> pd.DataFrame:
    return pd.read_csv(mes / f"{table}.csv", dtype=str, keep_default_na=False)


def _apply(mes: Path, step: dict) -> None:
    """cdc_changes.json의 변경을 CSV 원천 복사본에 그대로 적용한다(SQL과 같은 내용)."""
    for change in step["changes"]:
        table = change["table"].split(".", 1)[1]
        rows = _rows(mes, table)
        if "insert" in change:
            rows.loc[len(rows)] = [change["insert"].get(c, "") for c in rows.columns]
        elif "set" in change or "delete" in change:
            key = change.get("key") or change["delete"]
            hit = pd.Series(True, index=rows.index)
            for column, value in key.items():
                hit &= rows[column] == value
            if "set" in change:
                for column, value in change["set"].items():
                    rows.loc[hit, column] = value
            else:
                rows = rows[~hit]
        elif "add_column" in change:
            add = change["add_column"]
            rows[add["column"]] = ""
            schema_file = mes / "_schema.json"
            schema = json.loads(schema_file.read_text(encoding="utf-8"))
            schema["tables"][table]["columns"][add["column"]] = {
                "type": add["type"],
                "comment": add["comment"],
            }
            schema_file.write_text(json.dumps(schema, ensure_ascii=False), "utf-8")
        elif "drop_column" in change:
            rows = rows.drop(columns=[change["drop_column"]], errors="ignore")
            schema_file = mes / "_schema.json"
            schema = json.loads(schema_file.read_text(encoding="utf-8"))
            schema["tables"][table]["columns"].pop(change["drop_column"], None)
            schema_file.write_text(json.dumps(schema, ensure_ascii=False), "utf-8")
        rows.to_csv(mes / f"{table}.csv", index=False, encoding="utf-8")


def _fingerprints(mes: Path) -> dict[str, tuple[str, str]]:
    keys = [f"mes.{p.stem}" for p in sorted(mes.glob("*.csv"))]
    request = AnalysisStartRequest.model_validate(
        {
            "runId": 1,
            "runKind": "INITIAL",
            "targets": [
                {"sourceResourceId": i, "dataSourceCode": "MES_MAIN", "resourceKey": k}
                for i, k in enumerate(keys, 1)
            ],
            "sources": [{"dataSourceCode": "MES_MAIN", "sourceKind": "CSV"}],
        }
    )
    result = analyze(request, {"MES_MAIN": mes})
    return {
        p["evidence"]["fingerprint"]: (p["itemKind"], p["payload"].get("code", ""))
        for p in result.proposals
    }


def test_sql_files_match_steps_and_only_touch_new_rows(generated):
    _, scenario, steps = generated
    assert [s["step"] for s in steps] == [1, 2, 3, 4, 99]
    for step in steps:
        sql = (scenario / step["file"]).read_text(encoding="utf-8")
        assert "begin;" in sql and sql.rstrip().endswith("commit;")
    new_order = steps[0]["changes"][0]["insert"]["wo_no"]
    # 기존 시연 행은 바꾸지 않는다: 수정·삭제는 모두 새 작업지시 번호로만 찾는다
    for step in steps[1:]:
        for change in step["changes"]:
            key = change.get("key") or change.get("delete")
            if key:
                assert key["wo_no"] == new_order


def test_reanalysis_keeps_proposals_until_column_is_added(generated, tmp_path):
    data, _, steps = generated
    mes = tmp_path / "mes"
    shutil.copytree(data / "mes", mes)
    base = _fingerprints(mes)
    by_step = {}
    for step in steps:
        _apply(mes, step)
        by_step[step["step"]] = _fingerprints(mes)

    # 값만 바뀐 추가·수정·삭제는 후보가 모두 그대로(유지)
    for number in (1, 2, 3):
        assert set(by_step[number]) == set(base), number
    # 컬럼 추가는 기대값에 적은 후보만 바뀐다(변경). 새 후보는 없다
    changed = {by_step[4][fp] for fp in set(by_step[4]) - set(base)}
    expected = {(c["itemKind"], c["code"]) for c in steps[3]["expected_changed"]}
    assert changed == expected
    assert {base[fp] for fp in set(base) - set(by_step[4])} == expected
    # 되돌리면 처음과 같다
    assert set(by_step[99]) == set(base)
