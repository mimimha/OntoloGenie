import pytest

from app.dataset.build import generate
from app.models.candidate import Candidate, Evidence
from app.proposers import confidence
from app.proposers.evidence import SourceLookup, verify


@pytest.fixture(scope="module")
def data(tmp_path_factory):
    root = tmp_path_factory.mktemp("data")
    generate("t3", root=root)
    return root / "data-t3"


def test_evidence_must_exist_in_source(data):
    lookup = SourceLookup(data)
    assert lookup.check(
        Evidence(
            source_id="mes", locator="mes.equipment.code", snippet="", values=["P103"]
        )
    )
    assert not lookup.check(
        Evidence(
            source_id="mes", locator="mes.equipment.code", snippet="", values=["P999"]
        )
    )
    assert not lookup.check(
        Evidence(source_id="mes", locator="mes.no_table", snippet="")
    )
    assert not lookup.check(
        Evidence(source_id="erp", locator="mes.equipment", snippet="")
    )


def _candidate(evidence):
    return Candidate.model_validate(
        {
            "candidate_id": "OT-mes.equipment",
            "title": "설비",
            "reason": "기본키가 있는 표",
            "payload": {"kind": "OBJECT_TYPE", "code": "EQUIPMENT", "name": "설비"},
            "evidence": evidence,
        }
    )


def test_candidates_without_verified_evidence_are_dropped(data):
    real = _candidate(
        [{"source_id": "mes", "locator": "mes.equipment.code", "snippet": "설비 코드"}]
    )
    made_up = _candidate(
        [{"source_id": "erp", "locator": "erp.nothing", "snippet": "지어낸 근거"}]
    )

    kept, dropped = verify(data, [real, made_up])

    assert [c.evidence[0].snippet for c in kept] == ["설비 코드"]
    assert (
        kept[0].evidence[0].verified is True
    )  # 원천에서 다시 확인한 근거라고 표시한다
    assert [c.evidence[0].snippet for c in dropped] == ["지어낸 근거"]


def test_link_score_is_the_measured_hit_rate_of_its_basis():
    # 점수 = 같은 근거로 나온 제안이 평가 데이터에서 맞은 비율. 근거와 수치를 화면 문장으로 남긴다
    best = confidence.build(confidence.link(1.0, 1.0, "primary", name=True))
    assert best.total == 0.95
    assert "83개 중 82개가 맞음" in best.items[0].label
    no_name = confidence.build(confidence.link(0.98, 1.0, "primary"))
    assert no_name.total == 0.85 and "값 포함 98%" in no_name.items[0].label
    assert (
        confidence.build(confidence.link(1.0, 1.0, "alternate", name=True)).total
        == 0.75
    )
    # 다른 시스템인데 값이 글자 그대로 같으면(라인 C0001 ↔ 고객 C0001) 낮춘다
    cross = confidence.build(
        confidence.link(1.0, 1.0, "primary", name=True, cross_same_value=True)
    )
    assert cross.total == 0.7 and "다른 시스템" in cross.items[0].label


@pytest.mark.parametrize(
    "reason, cap",
    [
        ("접두어 'C'를 'EQ-'로 바꿔야 같아짐. 일련번호만 우연히 같을 수 있어", 0.2),
        ("숫자뿐인 값에 0 채우기·접두어를 붙여야 같아짐.", 0.3),
        ("도착이 1, 2, 3…처럼 이어지는 순번 키라 숫자가 우연히 겹칠 수 있고", 0.45),
        ("값 종류가 1개뿐이라 우연일 수 있음", 0.5),
        ("대소문자·구분자만 달라 대응표로 이었다(SON = SO-N-N).", 0.6),
        ("WO_SEQ은(는) 행 기준으로만 도착 값에 들어가 확인 필요", 0.5),
        ("LLM이 업무상 맞지 않다고 봄", confidence.CAP),
        ("처음 보는 보류 사유", confidence.HOLD_DEFAULT),
    ],
)
def test_hold_caps_follow_measured_hit_rate_of_each_reason(reason, cap):
    held = confidence.build(
        confidence.link(1.0, 1.0, "primary", name=True), hold=reason
    )
    assert held.total == cap
    assert held.cap_reason.startswith(reason)
    assert held.total <= 0.6  # 보류는 '높음'으로 보이지 않는다


def test_document_link_with_exact_source_key_scores_high():
    assert confidence.build(confidence.document_link(True)).total == 0.95
    assert confidence.build(confidence.document_link(False)).total == 0.8


@pytest.mark.parametrize(
    "score, band", [(None, "미산출"), (0.8, "높음"), (0.5, "보통"), (0.49, "낮음")]
)
def test_score_bands(score, band):
    assert confidence.band(score) == band


def test_document_link_evidence_checks_document_still_exists(data):
    # 문서 연결 근거는 글 컬럼 위치와 함께 문서 번호를 원천에서 다시 찾는다
    lookup = SourceLookup(data)
    note_id = SourceLookup(data)._reader("mes").read_rows("handover_note")["note_id"][0]

    def doc(value):
        return Evidence(
            source_id="mes",
            locator="mes.handover_note.note_id",
            snippet="문서 번호",
            values=[value],
        )

    assert lookup.check(doc(note_id))
    assert not lookup.check(doc("HN-없는-문서"))


def test_unlisted_hold_reasons_have_own_cap_and_no_borrowed_statistic():
    cap, text = confidence.hold_cap("식별 키 없음")
    assert cap == 0.6 and "평가 데이터" not in text
    cap, _ = confidence.hold_cap("수량·개수 컬럼이라 번호와 숫자가 우연히 겹칠 수 있음")
    assert cap == 0.4
    cap, text = confidence.hold_cap("처음 보는 보류 사유")
    assert cap == confidence.HOLD_DEFAULT and text == "처음 보는 보류 사유"
