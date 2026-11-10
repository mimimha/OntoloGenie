"""018 선택 필드 pastDecisions(S15P31C106-281)·previousProposals(S15P31C106-170) 받기."""

from app.analysis import engine
from app.analysis.engine import analyze
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


def test_engine_uses_both_fields_when_backend_sends_them(tmp_path, monkeypatch):
    monkeypatch.setattr(engine, "get_llm", lambda: None)
    monkeypatch.setattr(engine, "get_model_summary", lambda: (None, "내부 토큰 없음"))
    generate("t7", root=tmp_path)
    mes = {"MES_MAIN": tmp_path / "data-t7" / "mes"}
    first = analyze(request("INITIAL"), mes)
    assert first.summary["pastDecisionSkipped"] == {"APPROVE": 0, "REJECT": 0}

    rule = next(p for p in first.proposals if p["itemKind"] == "LINK_RULE")
    others = [p for p in first.proposals if p is not rule]
    again = analyze(
        request(
            "REANALYSIS",
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
        ),
        mes,
    )
    # 거절된 규칙은 다시 나오지 않고, 나머지는 이전 후보와 같아 유지
    assert again.summary["pastDecisionSkipped"]["REJECT"] == 1
    assert again.summary["changeBasis"] == "PREVIOUS_PROPOSALS"
    assert again.summary["changeCounts"] == {
        "ADDED": 0,
        "CHANGED": 0,
        "UNCHANGED": len(others),
    }
    assert not [
        p
        for p in again.proposals
        if p["evidence"]["candidateId"] == rule["evidence"]["candidateId"]
    ]
