"""같은 메모가 같은 대상을 여러 번 적으면 문서 연결은 한 건 (S15P31C106-327)."""

from app.extractors.notes import _one_per_target
from app.models.candidate import Candidate, DocumentLinkPayload, Evidence


def link(doc, ref, status="PENDING", start=0):
    return Candidate(
        candidate_id=f"DL-{doc}-{ref}-{start}",
        title="t",
        payload=DocumentLinkPayload(
            link_type_code="MENTIONS_EQUIPMENT",
            document_id=doc,
            span_start=start,
            span_end=start + 4,
            mention_text="P103",
            target_object_ref=ref,
        ),
        reason="r",
        evidence=[
            Evidence(source_id="mes", locator="mes.handover_note.content", snippet="s")
        ],
        review_status=status,
    )


def test_same_memo_and_target_is_one_link_but_ambiguous_mentions_stay():
    first = link("HN-1", "mes.equipment:P103", "PENDING", 0)
    again = link("HN-1", "mes.equipment:P103", "PENDING", 20)
    # '3호기'(후보 P103·P104)는 첫 후보가 P103이어도 P104일 수 있어 남긴다
    ambiguous = link("HN-1", "mes.equipment:P103", "HELD", 40)
    other = link("HN-2", "mes.equipment:P103")

    kept = _one_per_target([first, again, ambiguous, other])

    assert kept == [first, ambiguous, other]
