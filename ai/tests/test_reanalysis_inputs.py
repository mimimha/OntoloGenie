"""018 선택 필드 pastDecisions·previousProposals 받기."""

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


def test_rejected_option_is_removed_and_main_content_is_not_called_changed():
    # A안(본문)과 B안(선택지) 중 B안을 골라 거절하면, 다음 분석에서 B안만 빠지고 A안은 그대로 낸다
    main = proposal("LR-a", {"code": "A"})
    option_b = {"optionNo": 1, "payload": {"code": "A", "conditions": ["B"]}}
    option_b["evidence"] = {"fingerprint": fingerprint_payload(option_b["payload"])}
    option_c = {"optionNo": 2, "payload": {"code": "A", "conditions": ["C"]}}
    main["options"] = [option_b, option_c]
    decisions = [
        {
            "candidateId": "LR-a",
            "fingerprint": option_b["evidence"]["fingerprint"],
            "decision": "REJECT",
        }
    ]

    kept, skipped = skip_past_decisions([main], decisions)

    assert skipped == {"APPROVE": 0, "REJECT": 0}
    assert [o["optionNo"] for o in kept[0]["options"]] == [2]
    reason = kept[0]["evidence"]["reason"]
    assert "이전에 거절한 대안 1개는 뺐습니다" in reason
    assert "내용이 바뀌어" not in reason


def test_rejecting_first_option_offers_the_remaining_candidate():
    # '1호기' 후보 P101(본문 = 1번)·P301 중 P101을 거절하면 제안을 통째로 빼지 않고 P301로 다시 묻는다
    main = proposal(
        "DL-HN-1",
        {"targetObjectRef": "mes.equipment:P101", "name": "HN-1 → 설비 (후보 2개)"},
    )
    first = {"optionNo": 1, "payload": {"targetObjectRef": "mes.equipment:P101"}}
    second = {"optionNo": 2, "payload": {"targetObjectRef": "mes.equipment:P301"}}
    for o in (first, second):
        o["evidence"] = {"fingerprint": fingerprint_payload(o["payload"])}
    main["evidence"]["fingerprint"] = first["evidence"]["fingerprint"]
    main["options"] = [first, second]
    decisions = [
        {"fingerprint": first["evidence"]["fingerprint"], "decision": "REJECT"}
    ]

    kept, skipped = skip_past_decisions([main], decisions)

    assert skipped == {"APPROVE": 0, "REJECT": 0}
    assert kept[0]["payload"]["targetObjectRef"] == "mes.equipment:P301"
    assert kept[0]["payload"]["name"] == "HN-1 → 설비 (후보 2개)"
    assert kept[0]["evidence"]["fingerprint"] == second["evidence"]["fingerprint"]
    assert [o["optionNo"] for o in kept[0]["options"]] == [2]
    assert "남은 후보로 다시 제안" in kept[0]["evidence"]["reason"]

    # 남은 대안도 모두 거절됐으면 뺀다
    decisions.append(
        {"fingerprint": second["evidence"]["fingerprint"], "decision": "REJECT"}
    )
    main["payload"] = {"targetObjectRef": "mes.equipment:P101"}
    kept, skipped = skip_past_decisions([main], decisions)
    assert kept == [] and skipped["REJECT"] == 1


def test_options_carry_their_own_fingerprint():
    from app.models.candidate import (
        Candidate,
        CandidateOption,
        DocumentLinkPayload,
        Evidence,
    )
    from app.proposers.api_format import to_api

    first = DocumentLinkPayload(
        link_type_code="MENTIONS_EQUIPMENT",
        document_id="HN-1",
        span_start=0,
        span_end=3,
        mention_text="1호기",
        target_object_ref="mes.equipment:P101",
    )
    second = first.model_copy(update={"target_object_ref": "mes.equipment:P301"})
    evidence = [Evidence(source_id="mes", locator="handover_note", snippet="1호기")]
    api = to_api(
        Candidate(
            candidate_id="DL-HN-1",
            title="t",
            payload=first,
            reason="r",
            evidence=evidence,
            options=[
                CandidateOption(option_no=1, payload=first, evidence=evidence),
                CandidateOption(option_no=2, payload=second, evidence=evidence),
            ],
        )
    )
    for option in api["options"]:
        assert option["evidence"]["fingerprint"] == fingerprint_payload(
            option["payload"]
        )


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
