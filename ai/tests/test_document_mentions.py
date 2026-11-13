"""메모 언급 추출 보완: 후보 ID 고정, 같은 글자 개체 분리, 단어 경계, 시각 해석, 원문 가림, LLM 장애 끊기."""

from datetime import datetime

from app.extractors.equipment import load_equipment
from app.extractors.identifiers import find
from app.extractors.notes import (
    BATCH,
    Document,
    LLMDoc,
    ask_llm,
    build_prompt,
    extract,
    interpret_time,
    to_candidates,
)
from app.llm.client import LLMError, MockLLMClient
from app.models.extraction import EntityType
from app.proposers.api_format import to_api, to_api_proposals
from app.proposers.reanalysis import mark_changes

ROWS = [
    {"code": "P101", "name": "1라인 1호 프레스", "line": "1라인"},
    {"code": "P102", "name": "1라인 2호 프레스", "line": "1라인"},
    {"code": "P112", "name": "1라인 12호 프레스", "line": "1라인"},
]
EQUIPMENT = load_equipment(ROWS)


def _note(text, **extra):
    return Document(doc_id="HN-1", source_kind="note", text=text, **extra)


def _links(doc, entities, relations=()):
    answer = LLMDoc(doc_id=doc.doc_id, entities=entities, relations=list(relations))
    extraction, dropped = extract(doc, {}, EQUIPMENT, answer)
    return extraction, dropped, to_candidates(doc, extraction)


def test_candidate_id_does_not_depend_on_llm_answer_order():
    # 예전 ID는 LLM 개체 순번(e1, e2)이라 재분석에서 답 순서가 바뀌면 1호기·2호기 ID가 서로 바뀌었다
    doc = _note("1호기 소음. 2호기 진동.")
    one = {"type": "EQUIPMENT", "text": "1호기"}
    two = {"type": "EQUIPMENT", "text": "2호기"}

    _, _, first = _links(doc, [one, two])
    _, _, swapped = _links(doc, [two, one])

    ids = {c.payload.mention_text: c.candidate_id for c in first}
    assert ids == {c.payload.mention_text: c.candidate_id for c in swapped}
    assert ids == {"1호기": "DL-HN-1-1호기-1", "2호기": "DL-HN-1-2호기-1"}

    # 재분석 변경 표시도 같은 언급끼리 맞춘다
    before = to_api_proposals(first)
    previous = [
        {k: p["evidence"][k] for k in ("candidateId", "fingerprint")} for p in before
    ]
    counts = mark_changes(to_api_proposals(swapped), previous)
    assert counts == {"ADDED": 0, "CHANGED": 0, "UNCHANGED": 2}


def test_same_text_mentioned_twice_gets_its_own_number():
    doc = _note("2호기 소음. 1호기 점검 뒤 2호기 다시 소음.")
    entity = {"type": "EQUIPMENT", "text": "2호기"}

    extraction, dropped, _ = _links(doc, [entity, entity])

    starts = [e.start for e in extraction.entities if e.text == "2호기"]
    assert starts == [0, doc.text.rindex("2호기")] and dropped == []
    ids = [
        c.candidate_id
        for c in to_candidates(doc, extraction)
        if c.payload.mention_text == "2호기"
    ]
    # 같은 메모·같은 대상이라도 언급마다 번호가 다르다(대표 한 건은 _one_per_target이 고른다)
    assert ids == ["DL-HN-1-2호기-1", "DL-HN-1-2호기-2"]


def test_same_text_with_different_assertion_is_not_merged():
    # 두 '누유'가 하나로 합쳐지면 부정 판단이 사라지고 2호기에 긍정 관찰이 붙었다
    doc = _note("1호기 누유 있음. 2호기 누유 없음.")
    entities = [
        {"type": "EQUIPMENT", "text": "1호기"},
        {"type": "OBSERVATION", "text": "누유", "assertion": "affirmed"},
        {"type": "EQUIPMENT", "text": "2호기"},
        {"type": "OBSERVATION", "text": "누유", "assertion": "negated"},
    ]
    # 관계 순서가 개체 순서와 달라도 가까운 짝으로 잇는다
    relations = [
        {"type": "observed_on", "source_text": "누유", "target_text": "2호기"},
        {"type": "observed_on", "source_text": "누유", "target_text": "1호기"},
    ]

    extraction, dropped, links = _links(doc, entities, relations)

    leaks = [e for e in extraction.entities if e.text == "누유"]
    assert [(e.start, e.assertion.value) for e in leaks] == [
        (4, "affirmed"),
        (doc.text.rindex("누유"), "negated"),
    ]
    assert dropped == []
    # 관찰이 모두 부정인 2호기는 잇지 않는다
    assert [c.payload.target_object_ref for c in links] == ["mes.equipment:P101"]
    assert [o.assertion for o in links[0].document.observations] == ["AFFIRMED"]


def test_equipment_mention_is_not_found_inside_a_longer_number():
    # '12호기' 뒷부분을 '2호기'로 잡으면 2호기 위치가 틀리고 12호기 자리를 빼앗는다
    doc = _note("12호기 정상. 2호기 소음")

    extraction, _, links = _links(doc, [{"type": "EQUIPMENT", "text": "2호기"}])

    assert [(e.text, e.start) for e in extraction.entities] == [
        ("2호기", doc.text.index(" 2호기") + 1)
    ]
    assert [c.payload.target_object_ref for c in links] == ["mes.equipment:P102"]


def test_hours_are_read_within_the_shift():
    night = _note("", shift="야간", start=datetime(2026, 10, 29, 20))
    day = _note("", shift="주간", start=datetime(2026, 10, 29, 8))

    # '1시간 정지'는 시각이 아니다
    assert interpret_time("1시간 정지", day)[0] is None
    assert interpret_time("밤 12시", day)[0] == "2026-10-30T00:00:00"
    assert interpret_time("자정쯤", night)[0] == "2026-10-30T00:00:00"
    assert interpret_time("밤 12시", night)[0] == "2026-10-30T00:00:00"
    assert interpret_time("오전 12시", day)[0] == "2026-10-29T00:00:00"
    assert interpret_time("오후 12시", day)[0] == "2026-10-29T12:00:00"
    # 수식어가 없으면 교대 시간(야간 20시~08시, 주간 08시~20시) 안에 드는 쪽
    assert interpret_time("10시쯤", night)[0] == "2026-10-29T22:00:00"
    assert interpret_time("3시", night)[0] == "2026-10-30T03:00:00"
    assert interpret_time("12시", night)[0] == "2026-10-30T00:00:00"
    assert interpret_time("3시", day)[0] == "2026-10-29T15:00:00"
    assert interpret_time("10시", day)[0] == "2026-10-29T10:00:00"
    assert interpret_time("밤 11시", night)[0] == "2026-10-29T23:00:00"
    assert interpret_time("새벽 3시쯤", night)[0] == "2026-10-30T03:00:00"


def test_stored_text_hides_names_and_phones_with_same_length():
    text = "1호기 누유. 김철수 책임 010-1234-5678로 연락 바람. 홍길동님께 인계."
    doc = _note(text)
    entities = [
        {"type": "EQUIPMENT", "text": "1호기"},
        {"type": "OBSERVATION", "text": "누유"},
    ]
    relations = [{"type": "observed_on", "source_text": "누유", "target_text": "1호기"}]

    _, _, links = _links(doc, entities, relations)
    api = to_api(links[0])

    stored = api["evidence"]["originalText"]
    assert len(stored) == len(text)
    for secret in ("김철수", "010-1234-5678", "홍길동"):
        assert secret not in stored and secret not in str(api)
    assert stored.startswith("1호기 누유. *** 책임 *************로 연락")
    # 위치는 원문 그대로라 언급·관찰 자리가 맞는다
    payload = api["payload"]
    assert stored[payload["spanStart"] : payload["spanEnd"]] == "1호기"
    assert api["evidence"]["observations"][0]["text"] == "누유"
    # 검토 화면 설명(readable의 원문 발췌)도 가린 원문에서 만든다
    shown = str(to_api_proposals(links))
    assert "김철수" not in shown and "010-1234-5678" not in shown


def test_llm_answer_with_masked_name_is_found_in_original_text():
    # LLM은 가린 글만 보므로 '*** 책임에게 인계'처럼 답한다. 원문에서 못 찾으면 조치가 버려졌다
    text = "3호기 점검. 김철수 책임에게 인계함."
    doc = _note(text)
    prompt = build_prompt([doc])
    assert "김철수" not in prompt and "*** 책임에게" in prompt

    for answered in ("*** 책임에게 인계", "** 책임에게 인계", "*책임에게 인계"):
        extraction, dropped, _ = _links(doc, [{"type": "ACTION", "text": answered}])
        actions = [e for e in extraction.entities if e.type == EntityType.ACTION]
        if answered == "*책임에게 인계":
            # 띄어쓰기까지 다르면 원문에 없는 글이다
            assert actions == [] and dropped
            continue
        assert [a.text for a in actions] == ["김철수 책임에게 인계"], answered
        assert dropped == []


def test_repeated_source_key_value_is_ambiguous_not_last_row():
    dictionary = {
        "LT2610-01": (
            EntityType.LOT,
            ["erp.shipment:DL-1", "erp.shipment:DL-2"],
        )
    }
    doc = _note("LT2610-01 출하 전 확인.")

    hits = find(doc.text, dictionary)
    assert hits[0].candidates == ["erp.shipment:DL-1", "erp.shipment:DL-2"]

    extraction, _ = extract(doc, dictionary, EQUIPMENT, None)
    (link,) = to_candidates(doc, extraction)
    assert link.review_status == "HELD"
    assert [o.payload.target_object_ref for o in link.options] == [
        "erp.shipment:DL-1",
        "erp.shipment:DL-2",
    ]
    assert "원천 행 2개" in link.reason and link.hold_reason == "후보 대상이 여럿"


class _Failing(MockLLMClient):
    def __init__(self, error):
        super().__init__()
        self.error = error
        self.last_usage = None

    def complete_json(self, prompt):
        self.prompts.append(prompt)
        raise self.error


def _docs(count):
    return [
        _note(f"{i}호기 소음").model_copy(update={"doc_id": f"HN-{i}"})
        for i in range(count)
    ]


def test_llm_outage_skips_remaining_batches():
    docs = _docs(BATCH * 5)
    llm = _Failing(LLMError("GMS 응답이 없습니다: TimeoutError"))

    answers, failed, calls = ask_llm(docs, llm)

    # 연속 3번 응답이 없으면 남은 2묶음은 부르지 않고 이유를 남긴다
    assert len(llm.prompts) == 3 and len(calls) == 3
    assert len(failed) == 5 and answers == {}
    assert all("연속 3번 응답하지 않아" in f["error"] for f in failed[3:])


def test_unexpected_error_fails_only_that_batch():
    docs = _docs(BATCH * 2)
    answered = {"count": 0}

    def answer(prompt):
        answered["count"] += 1
        if answered["count"] == 1:
            raise TypeError("예상하지 못한 답")
        return {"docs": [{"doc_id": "HN-15", "entities": []}]}

    answers, failed, _ = ask_llm(docs, MockLLMClient(answer))

    assert [f["docs"][0] for f in failed] == ["HN-0"]
    assert list(answers) == ["HN-15"]
