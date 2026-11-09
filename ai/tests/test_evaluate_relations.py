"""독립 데이터 관계 채점(app.validators.evaluate_relations)이 찾음·뒤집힘·오답·미끼를 나눠 세는지."""

import json

from app.validators.evaluate_relations import evaluate, main


def _rule(source, target, status):
    return {
        "payload": {"kind": "LINK_RULE"},
        "evidence": [{"locator": source}, {"locator": target}],
        "review_status": status,
    }


ANSWER = {
    "relations": [
        {"from": "mes.lot.item_no", "to": "erp.item.item_code"},
        {"from": "mes.lot.wo_no", "to": "mes.work_order.wo_no"},
        {"from": "mes.lot.a+mes.lot.b", "to": "erp.line.a+erp.line.b"},
    ],
    "traps": [{"column": "mes.lot.box_qty"}],
}

RESULT = {
    "candidates": [
        # 같은 관계가 보류·대기로 둘 다 나오면 대기로 센다
        _rule("mes.lot.item_no", "erp.item.item_code", "HELD"),
        _rule("mes.lot.item_no", "erp.item.item_code", "PENDING"),
        # 복합키 근거 위치는 'sys.table.a+b'로 와도 정답 표기와 맞춘다
        _rule("mes.lot.a+b", "erp.line.a+b", "HELD"),
        _rule("mes.work_order.wo_no", "mes.lot.wo_no", "PENDING"),
        _rule("mes.lot.box_qty", "erp.item.item_code", "HELD"),
        {
            "payload": {"kind": "OBJECT_TYPE"},
            "evidence": [],
            "review_status": "PENDING",
        },
    ],
    "summary": {"review_units": {"LINK_RULE": 5}},
}


def test_counts_found_reversed_wrong_and_traps():
    report = evaluate(RESULT, ANSWER)
    assert report["gold"] == 3
    assert (report["found_pending"], report["found_held"]) == (1, 1)
    assert report["cross_system_found"] == "2/2"
    assert report["missed"] == ["mes.lot.wo_no -> mes.work_order.wo_no"]
    assert report["reversed"] == ["mes.work_order.wo_no -> mes.lot.wo_no"]
    assert report["wrong_pending"] == [] and report["wrong_held"] == 1
    assert report["trap_hits"] == ["mes.lot.box_qty -> erp.item.item_code (HELD)"]


def test_main_prints_report(tmp_path, capsys):
    result, answer = tmp_path / "result.json", tmp_path / "answer.json"
    result.write_text(json.dumps(RESULT), encoding="utf-8")
    answer.write_text(json.dumps(ANSWER), encoding="utf-8")
    assert main([str(result), str(answer)]) == 0
    assert json.loads(capsys.readouterr().out)["gold"] == 3
