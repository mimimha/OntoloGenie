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
