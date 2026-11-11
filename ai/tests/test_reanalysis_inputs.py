"""018 선택 필드 pastDecisions(S15P31C106-281)·previousProposals(S15P31C106-170) 받기."""

from app.analysis import engine
from app.analysis.engine import analyze
from app.analysis.runner import AnalysisRunner
from app.dataset.build import generate
from app.models.analysis import AnalysisStartRequest
from app.proposers.api_format import fingerprint_payload
from app.proposers.existing import skip_past_decisions
from app.proposers.reanalysis import mark_changes


def proposal(candidate_id, payload):
    return {
        "itemKind": "LINK_RULE",
        "payload": payload,
        "evidence": {
            "candidateId": candidate_id,
            "fingerprint": fingerprint_payload(payload),
            "reason": "값이 이어진다.",
        },
    }


def test_rejected_or_approved_same_content_is_not_sent_again():
    rejected = proposal("LR-a", {"code": "A"})
    approved = proposal("LR-b", {"code": "B"})
    held = proposal("LR-c", {"code": "C"})
    changed = proposal("LR-d", {"code": "D", "conditions": [1]})
    fresh = proposal("LR-e", {"code": "E"})
    decisions = [
        {"fingerprint": rejected["evidence"]["fingerprint"], "decision": "REJECT"},
        # 지문 없이 payload만 와도 같은 방식으로 계산한다
        {"payload": {"code": "B"}, "decision": "APPROVED"},
        {
            "fingerprint": held["evidence"]["fingerprint"],
            "decision": "HOLD",
            "reason": "담당 확인 필요",
        },
        # 같은 후보 ID가 거절됐는데 내용이 바뀜
        {
            "candidateId": "LR-d",
            "fingerprint": "old",
            "decision": "REJECT",
            "reason": "방향이 반대",
        },
    ]
    kept, skipped = skip_past_decisions(
        [rejected, approved, held, changed, fresh], decisions
    )
    assert [p["evidence"]["candidateId"] for p in kept] == ["LR-c", "LR-d", "LR-e"]
    assert skipped == {"APPROVE": 1, "REJECT": 1}
    assert "이전에 보류됐습니다(사유: 담당 확인 필요)" in kept[0]["evidence"]["reason"]
    assert (
        "이전에 거절된 후보(사유: 방향이 반대)인데 내용이 바뀌어"
        in kept[1]["evidence"]["reason"]
    )
    assert kept[2]["evidence"]["reason"] == "값이 이어진다."


def test_no_past_decisions_keeps_everything():
    items = [proposal("LR-a", {"code": "A"})]
    assert skip_past_decisions(items, []) == (items, {"APPROVE": 0, "REJECT": 0})


def test_previous_proposals_mark_added_changed_unchanged():
    same = proposal("LR-a", {"code": "A"})
    changed = proposal("LR-b", {"code": "B", "conditions": [2]})
    new = proposal("LR-c", {"code": "C"})
    previous = [
        {"candidateId": "LR-a", "fingerprint": same["evidence"]["fingerprint"]},
        {"candidateId": "LR-b", "fingerprint": "old"},
    ]
    counts = mark_changes([same, changed, new], previous)
    assert counts == {"ADDED": 1, "CHANGED": 1, "UNCHANGED": 1}
    assert [p["evidence"]["changeType"] for p in (same, changed, new)] == [
        "UNCHANGED",
        "CHANGED",
        "ADDED",
    ]


def test_existing_model_with_new_property_stays_changed_when_not_in_previous_proposals():
    # 직전 분석에서 이미 승인된 객체라 걸러져 이전 후보에 없지만, 새 컬럼(납기일)이 붙어 다시 나온 경우
    work_order = proposal("OT-mes.work_order", {"code": "WORK_ORDER"})
    work_order["evidence"]["changeType"] = "CHANGED"  # 043 모델 비교 결과
    counts = mark_changes([work_order], [{"candidateId": "LR-x", "fingerprint": "f"}])
    assert counts == {"ADDED": 0, "CHANGED": 1, "UNCHANGED": 0}
    assert work_order["evidence"]["changeType"] == "CHANGED"


def request(kind, **extra):
    return AnalysisStartRequest.model_validate(
        {
            "runId": 1,
            "runKind": kind,
            "targets": [
                {
                    "sourceResourceId": i,
                    "dataSourceCode": "MES_MAIN",
                    "resourceKey": key,
                }
                for i, key in enumerate(["mes.work_order", "mes.production_result"], 1)
            ],
            "sources": [{"dataSourceCode": "MES_MAIN", "sourceKind": "CSV"}],
            **extra,
        }
    )


def test_result_callback_excludes_rejected_candidates_and_reports_changes(
    tmp_path, monkeypatch
):
    monkeypatch.setattr(engine, "get_llm", lambda: None)
    monkeypatch.setattr(engine, "get_model_summary", lambda: (None, "내부 토큰 없음"))
    generate("t7", root=tmp_path)
    mes = {"MES_MAIN": tmp_path / "data-t7" / "mes"}
    first = analyze(request("INITIAL"), mes)
    assert first.summary["pastDecisionSkipped"] == {"APPROVE": 0, "REJECT": 0}

    rule = next(p for p in first.proposals if p["itemKind"] == "LINK_RULE")
    others = [p for p in first.proposals if p is not rule]
    callbacks = []
    runner = AnalysisRunner(lambda _run_id, body: callbacks.append(body))
    runner.run(
        request(
            "REANALYSIS",
            sources=[
                {
                    "dataSourceCode": "MES_MAIN",
                    "sourceKind": "CSV",
                    "path": str(mes["MES_MAIN"]),
                }
            ],
            pastDecisions=[
                {
                    "candidateId": rule["evidence"]["candidateId"],
                    "itemKind": "LINK_RULE",
                    "revision": 1,
                    "fingerprint": rule["evidence"]["fingerprint"],
                    "decision": "REJECT",
                    "reason": "업무상 맞지 않음",
                }
            ],
            previousProposals=[
                {
                    "candidateId": p["evidence"]["candidateId"],
                    "fingerprint": p["evidence"]["fingerprint"],
                }
                for p in others
            ],
        )
    )

    running, succeeded = callbacks
    assert running == {"status": "RUNNING"}
    assert succeeded["status"] == "SUCCEEDED"
    summary = succeeded["resultSummary"]
    assert summary["pastDecisionSkipped"]["REJECT"] == 1
    assert summary["changeBasis"] == "PREVIOUS_PROPOSALS"
    assert summary["changeCounts"] == {
        "ADDED": 0,
        "CHANGED": 0,
        "UNCHANGED": len(others),
    }
    assert not [
        p
        for p in succeeded["proposals"]
        if p["evidence"]["candidateId"] == rule["evidence"]["candidateId"]
    ]


def test_adding_due_date_marks_the_same_work_order_candidate_changed(
    tmp_path, monkeypatch
):
    monkeypatch.setattr(engine, "get_llm", lambda: None)
    (tmp_path / "work_order.csv").write_text(
        "wo_no,due_date\nWO-001,2026-11-01\nWO-002,2026-11-02\nWO-003,2026-11-03\n",
        encoding="utf-8",
    )
    table = {
        "sourceResourceId": 1,
        "dataSourceCode": "MES_MAIN",
        "resourceKey": "mes.work_order",
    }
    sources = {"MES_MAIN": tmp_path}
    first = analyze(
        request("INITIAL", targets=[{**table, "columns": ["wo_no"]}]),
        sources,
        model_summary={},
    )
    original = next(p for p in first.proposals if p["itemKind"] == "OBJECT_TYPE")
    evidence = original["evidence"]

    again = analyze(
        request(
            "REANALYSIS",
            targets=[table],
            previousProposals=[
                {
                    "candidateId": evidence["candidateId"],
                    "fingerprint": evidence["fingerprint"],
                }
            ],
        ),
        sources,
        model_summary={},
    )

    changed = next(p for p in again.proposals if p["itemKind"] == "OBJECT_TYPE")
    assert changed["evidence"]["candidateId"] == evidence["candidateId"]
    assert changed["evidence"]["fingerprint"] != evidence["fingerprint"]
    assert changed["evidence"]["changeType"] == "CHANGED"
    assert any(prop["code"] == "due_date" for prop in changed["payload"]["properties"])
