from app.models.analysis import PastDecision
from app.models.candidate import (
    Candidate,
    CandidateOption,
    DocumentLinkPayload,
    Evidence,
)
from app.proposers.api_format import to_api_proposals
from app.proposers.existing import skip_past_decisions


def _reanalysis_with_second_option_decision(decision):
    first = DocumentLinkPayload(
        link_type_code="NOTE_MENTIONS_EQUIPMENT",
        document_id="note-1",
        span_start=0,
        span_end=6,
        mention_text="unit 3",
        target_object_ref="mes.equipment:A",
    )
    second = first.model_copy(update={"target_object_ref": "mes.equipment:B"})
    evidence = [Evidence(source_id="mes", locator="handover_note", snippet="unit 3")]
    candidate = Candidate(
        candidate_id="DL-note-1",
        title="Equipment mention",
        payload=first,
        reason="Two possible targets",
        evidence=evidence,
        options=[
            CandidateOption(option_no=1, payload=first, evidence=evidence),
            CandidateOption(option_no=2, payload=second, evidence=evidence),
        ],
    )
    proposals = to_api_proposals([candidate])
    history = PastDecision(
        candidate_id="DL-note-1",
        item_kind="DOCUMENT_LINK",
        revision=1,
        payload={
            "linkTypeCode": "NOTE_MENTIONS_EQUIPMENT",
            "documentId": "note-1",
            "spanStart": 0,
            "spanEnd": 6,
            "mentionText": "unit 3",
            "targetObjectRef": "mes.equipment:B",
            "expressionKind": "OBSERVED",
        },
        decision=decision,
    )
    return skip_past_decisions(proposals, [history.model_dump(by_alias=True)])


def test_does_not_repeat_document_link_when_second_option_was_approved():
    kept, skipped = _reanalysis_with_second_option_decision("APPROVE")
    assert kept == []
    assert skipped == {"APPROVE": 1, "REJECT": 0}


def test_rejected_second_option_does_not_suppress_the_document_link():
    kept, skipped = _reanalysis_with_second_option_decision("REJECT")
    assert len(kept) == 1
    assert kept[0]["payload"]["targetObjectRef"] == "mes.equipment:A"
    assert skipped == {"APPROVE": 0, "REJECT": 0}


def _proposal(targets):
    payloads = [
        DocumentLinkPayload(
            link_type_code="NOTE_MENTIONS_EQUIPMENT",
            document_id="note-1",
            span_start=0,
            span_end=6,
            mention_text="unit 3",
            target_object_ref=f"mes.equipment:{t}",
        )
        for t in targets
    ]
    evidence = [Evidence(source_id="mes", locator="handover_note", snippet="unit 3")]
    candidate = Candidate(
        candidate_id="DL-note-1",
        title="Equipment mention",
        payload=payloads[0],
        reason="Two possible targets",
        evidence=evidence,
        options=[
            CandidateOption(option_no=i + 1, payload=pl, evidence=evidence)
            for i, pl in enumerate(payloads)
        ],
    )
    return to_api_proposals([candidate])


def test_changed_options_with_same_main_proposal_are_marked_changed():
    from app.proposers.reanalysis import mark_changes

    before = _proposal(["A", "B"])[0]["evidence"]
    previous = [{"candidateId": "DL-note-1", "fingerprint": before["fingerprint"]}]
    same, changed = _proposal(["A", "B"]), _proposal(["A", "C"])
    assert mark_changes(same, previous)["UNCHANGED"] == 1
    assert mark_changes(changed, previous)["CHANGED"] == 1


def test_document_link_approval_is_matched_by_fingerprint_not_candidate_id():
    # '이미 승인'은 대상이 든 지문으로만 본다. 같은 후보 ID라도 선택지가 바뀌었으면 다시 묻고 이유에 적는다
    before = _proposal(["A", "B"])[0]["evidence"]
    history = {
        "candidateId": "DL-note-1",
        "itemKind": "DOCUMENT_LINK",
        "revision": 1,
        "fingerprint": before["fingerprint"],
        "decision": "APPROVE",
    }
    kept, skipped = skip_past_decisions(_proposal(["A", "B"]), [history])
    assert kept == [] and skipped["APPROVE"] == 1

    kept, skipped = skip_past_decisions(_proposal(["A", "C"]), [history])
    assert len(kept) == 1 and skipped["APPROVE"] == 0
    assert "이전에 승인했지만" in kept[0]["evidence"]["reason"]


def test_approving_one_mention_does_not_hide_another_mention_with_reused_id():
    # 예전 후보 ID(LLM 개체 순번)는 재분석에서 다른 언급에 다시 쓰일 수 있었다. 1호기 승인이 2호기를 숨기면 안 된다
    def single(target, mention, start):
        payload = DocumentLinkPayload(
            link_type_code="MENTIONS_EQUIPMENT",
            document_id="HN-1",
            span_start=start,
            span_end=start + len(mention),
            mention_text=mention,
            target_object_ref=f"mes.equipment:{target}",
        )
        evidence = [Evidence(source_id="mes", locator="handover_note", snippet="s")]
        return to_api_proposals(
            [
                Candidate(
                    candidate_id="DL-HN-1-e1",
                    title="t",
                    payload=payload,
                    reason="r",
                    evidence=evidence,
                )
            ]
        )

    approved = single("P101", "1호기", 0)[0]["evidence"]
    history = {
        "candidateId": "DL-HN-1-e1",
        "itemKind": "DOCUMENT_LINK",
        "revision": 1,
        "fingerprint": approved["fingerprint"],
        "decision": "APPROVE",
    }
    kept, skipped = skip_past_decisions(single("P102", "2호기", 10), [history])
    assert [p["payload"]["targetObjectRef"] for p in kept] == ["mes.equipment:P102"]
    assert skipped["APPROVE"] == 0


def test_rejected_main_proposal_is_not_promoted_back_from_its_own_option():
    before = _proposal(["A", "B"])[0]["evidence"]
    history = {
        "candidateId": "DL-note-1",
        "itemKind": "DOCUMENT_LINK",
        "revision": 1,
        "fingerprint": before["fingerprint"],
        "decision": "REJECT",
    }
    kept, _ = skip_past_decisions(_proposal(["A", "B"]), [history])
    assert [p["payload"]["targetObjectRef"] for p in kept] == ["mes.equipment:B"]
