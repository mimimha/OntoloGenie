"""검토 화면용 한국어 제목·설명과 바뀐 점수 (S15P31C106-327)."""

import pytest

from app.proposers import confidence
from app.proposers.readable import josa, readable


@pytest.mark.parametrize(
    "word, pair, expected",
    [
        ("작업지시", "이/가", "작업지시가"),
        ("직원", "이/가", "직원이"),
        ("수주", "을/를", "수주를"),
        ("제품별 공정", "을/를", "제품별 공정을"),
        ("사번", "으로/로", "사번으로"),
        ("수주 참조 번호", "으로/로", "수주 참조 번호로"),
        ("작업자 번호·라인", "으로/로", "작업자 번호·라인으로"),
        ("메일", "으로/로", "메일로"),  # ㄹ 받침은 '로'
        ("P201", "을/를", "P201을"),
        ("JB2608-0129", "을/를", "JB2608-0129를"),
        ("주소1", "으로/로", "주소1로"),  # 일·칠·팔은 ㄹ 받침
        ("라인7", "이/가", "라인7이"),
        ("코드3", "으로/로", "코드3으로"),
        ("R", "을/를", "R을"),
    ],
)
def test_josa_follows_final_consonant(word, pair, expected):
    assert josa(word, pair) == expected


def object_type(code, name, table, *props):
    return {
        "itemKind": "OBJECT_TYPE",
        "payload": {
            "code": code,
            "name": name,
            "objectMapping": {"resourceKey": table},
            "properties": [{"code": c, "name": n} for c, n in props],
        },
        "evidence": {"reason": "원천 표의 한 행을 객체 하나로 본다."},
    }


def proposals():
    return [
        object_type(
            "WORK_ORDER", "작업지시", "mes.work_order", ("so_ref", "수주 참조 번호")
        ),
        object_type("SALES_ORDER", "수주", "erp.sales_order", ("so_no", "수주 번호")),
        object_type(
            "HANDOVER_NOTE",
            "교대 인수인계 메모",
            "mes.handover_note",
            ("content", "메모 원문"),
        ),
        {
            "itemKind": "LINK_RULE",
            "payload": {
                "code": "WORK_ORDER_SO_REF_TO_SALES_ORDER",
                "linkType": {
                    "code": "WORK_ORDER_SO_REF_TO_SALES_ORDER",
                    "name": "WORK_ORDER → SALES_ORDER",
                    "description": "WORK_ORDER가 SALES_ORDER를 가리킨다. 개수 관계는 데이터에서 관찰한 값.",
                    "sourceTypeCode": "WORK_ORDER",
                    "targetTypeCode": "SALES_ORDER",
                },
                "conditions": [
                    {
                        "sourceProperty": "WORK_ORDER.so_ref",
                        "targetProperty": "SALES_ORDER.so_no",
                    }
                ],
            },
            "evidence": {
                "title": "연결 규칙 WORK_ORDER_SO_REF_TO_SALES_ORDER",
                "reason": "값이 이어진다.",
            },
        },
        {
            "itemKind": "LINK_TYPE",
            "payload": {
                "code": "ABOUT_WORK_ORDER",
                "name": "HANDOVER_NOTE → WORK_ORDER",
                "description": "HANDOVER_NOTE의 글이 WORK_ORDER를 언급한다. 원천 키가 아니라 글에서 찾은 연결이다.",
                "sourceTypeCode": "HANDOVER_NOTE",
                "targetTypeCode": "WORK_ORDER",
            },
            "evidence": {"reason": "글 11건에서 WORK_ORDER 언급 13개를 찾았다."},
        },
        {
            "itemKind": "DOCUMENT_LINK",
            "payload": {
                "documentId": "HN-260820-N",
                "mentionText": "JB2608-0129",
                "targetObjectRef": "mes.work_order:JB2608-0129",
            },
            "evidence": {
                "reason": "'JB2608-0129'를 원천 키 또는 설비 이름과 맞춰 mes.work_order:JB2608-0129에 잇는다.",
                "items": [{"locator": "mes.handover_note.content"}],
            },
        },
    ]


def test_link_rule_link_type_and_document_link_get_korean_titles():
    out = readable(proposals())
    rule, link_type, document = out[3], out[4], out[5]

    assert rule["payload"]["name"] == "작업지시 → 수주 (수주 참조 번호로 연결)"
    assert rule["evidence"]["title"] == rule["payload"]["name"]
    assert rule["evidence"]["reason"].startswith(
        "작업지시의 수주 참조 번호로 수주를 찾습니다."
    )
    assert rule["payload"]["linkType"]["name"] == "작업지시 → 수주"
    assert rule["payload"]["linkType"]["description"] == (
        "작업지시가 수주를 가리킵니다. 개수 관계는 데이터에서 관찰한 값."
    )
    # 코드는 바꾸지 않는다(백엔드가 코드로 찾는다)
    assert rule["payload"]["code"] == "WORK_ORDER_SO_REF_TO_SALES_ORDER"

    assert link_type["payload"]["name"] == "교대 인수인계 메모 → 작업지시 (글에서 언급)"
    assert (
        link_type["evidence"]["reason"]
        == "글 11건에서 작업지시 언급 13개를 찾았습니다."
    )

    assert (
        document["payload"]["name"]
        == "교대 인수인계 메모 HN-260820-N → 작업지시 JB2608-0129"
    )
    assert document["evidence"]["reason"] == (
        "글에 나온 'JB2608-0129'를 작업지시 JB2608-0129에 잇습니다. 원천 키나 설비 이름과 맞춘 결과입니다."
    )


def test_unknown_object_keeps_its_code():
    # 분석 범위 밖이라 이름을 모르는 객체는 코드를 그대로 쓴다
    rule = proposals()[3]
    out = readable([rule])
    assert out[0]["payload"]["name"] == "WORK_ORDER → SALES_ORDER (so_ref로 연결)"


def test_object_type_from_source_table_is_not_shown_as_25_percent():
    # 평가 데이터의 표는 모두 정답 객체 유형이었다. 전에는 다른 표가 가리키지 않으면 0.25였다
    plain = confidence.build(confidence.object_type(True, False, 0))
    assert plain.total == 0.95
    assert "평가 데이터의 표는 모두 맞음" in plain.items[0].label
    assert confidence.build(confidence.object_type(False, False, 0)).total == 0.85


def test_fingerprint_is_kept_when_only_names_change():
    # 이름은 LLM·분석 범위에 따라 바뀐다. 재분석 비교가 '변경'으로 보지 않게 지문은 그대로 둔다
    items = proposals()
    items[3]["evidence"]["fingerprint"] = "before"
    out = readable(items)
    assert out[3]["evidence"]["fingerprint"] == "before"


def test_held_document_link_keeps_reason_and_does_not_pick_first_option():
    items = proposals()
    doc = items[5]
    doc["payload"]["targetObjectRef"] = "mes.equipment:EQ-01"
    doc["payload"]["mentionText"] = "2호기"
    doc["evidence"]["holdRecommended"] = True
    doc["evidence"]["reason"] = (
        "'2호기'는 설비 2대와 맞아 하나로 정할 수 없다. 작성자의 배치 라인이 단서가 될 수 있다."
    )
    doc["options"] = [{"optionNo": 1}, {"optionNo": 2}]
    out = readable(items)[5]
    assert out["payload"]["name"].endswith("후보 2개")
    assert "EQ-01" not in out["payload"]["name"]
    assert out["evidence"]["reason"].startswith("글에 나온 '2호기'에 맞는")
    assert "작성자의 배치 라인이 단서" in out["evidence"]["reason"]
