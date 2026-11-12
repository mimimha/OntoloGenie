"""검토 화면용 한국어 제목·설명과 바뀐 점수."""

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
        # 영어 컬럼 이름은 소리로 어림한다
        ("dept_name", "으로/로", "dept_name으로"),
        ("line", "으로/로", "line으로"),
        ("level", "으로/로", "level로"),
        ("group", "이/가", "group이"),
        ("order_number", "이/가", "order_number가"),
        ("emp_no", "으로/로", "emp_no로"),
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
            "evidence": {
                "candidateId": "LT-DOC-ABOUT_WORK_ORDER",
                "reason": "HANDOVER_NOTE 11건 · WORK_ORDER 기재 13건 · 일치 13건",
            },
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

    assert rule["payload"]["name"] == "작업지시 → 수주 (수주 참조 번호)"
    assert rule["evidence"]["title"] == rule["payload"]["name"]
    # 연결 규칙 이유는 그대로 둔다
    assert rule["evidence"]["reason"] == "값이 이어진다."
    assert rule["payload"]["description"] == (
        "연결 기준: 작업지시 수주 참조 번호 = 수주 수주 번호"
    )
    assert rule["payload"]["linkType"]["name"] == "작업지시 → 수주"
    assert rule["payload"]["linkType"]["description"] == "작업지시에서 수주 참조"
    # 코드는 바꾸지 않는다(백엔드가 코드로 찾는다)
    assert rule["payload"]["code"] == "WORK_ORDER_SO_REF_TO_SALES_ORDER"

    assert link_type["payload"]["name"] == "교대 인수인계 메모의 작업지시 연결"
    assert link_type["payload"]["description"] == (
        "교대 인수인계 메모 본문에 기재된 작업지시와 연결"
    )
    assert link_type["evidence"]["reason"] == (
        "교대 인수인계 메모 11건 · 작업지시 기재 13건 · 일치 13건"
    )

    assert document["payload"]["name"] == "HN-260820-N → 작업지시 JB2608-0129"
    assert document["evidence"]["reason"] == "본문 기재 'JB2608-0129'"


def test_unknown_object_keeps_its_code():
    rule = proposals()[3]
    out = readable([rule])
    assert out[0]["payload"]["name"] == "WORK_ORDER → SALES_ORDER (so_ref)"


def test_object_type_from_source_table_is_not_shown_as_25_percent():
    plain = confidence.build(confidence.object_type(True, False, 0))
    assert plain.total == 0.95
    assert "평가 데이터의 표는 모두 맞음" in plain.items[0].label
    assert confidence.build(confidence.object_type(False, False, 0)).total == 0.85


def test_fingerprint_is_kept_when_only_names_change():
    # 재분석 비교가 이름만 바뀐 것을 '변경'으로 보지 않게 지문은 그대로 둔다
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
        "'2호기'는 설비 2대와 맞아 하나로 정할 수 없다. 작성자(작업자 24) 배치 라인 확인 필요."
    )
    doc["options"] = [{"optionNo": 1}, {"optionNo": 2}]
    out = readable(items)[5]
    assert out["payload"]["name"].endswith("(후보 2개)")
    assert "EQ-01" not in out["payload"]["name"]
    assert out["evidence"]["reason"].startswith("본문 '2호기' 해당")
    assert "작성자(작업자 24) 배치 라인 확인 필요" in out["evidence"]["reason"]
    # 원래 이유의 첫 문장은 빼고 단서만 잇는다
    assert "하나로 정할 수 없다" not in out["evidence"]["reason"]
    assert "후보 2개" in out["evidence"]["reason"]


def test_document_link_shows_alias_and_memo_excerpt():
    items = proposals()
    doc = items[5]
    text = "새벽 2시쯤 1라인 3호 프레스 진동이 평소보다 커서 점검 요청함."
    mention = "1라인 3호 프레스"
    start = text.index(mention)
    doc["payload"].update(
        {
            "targetObjectRef": "mes.equipment:P103",
            "mentionText": mention,
            "spanStart": start,
            "spanEnd": start + len(mention),
        }
    )
    doc["evidence"]["originalText"] = text
    doc["evidence"]["confidenceItems"] = [
        {"label": "글의 설비 이름·별명을 설비 목록과 맞춤", "weight": 0.8}
    ]
    out = readable(items)[5]
    assert out["payload"]["name"] == "HN-260820-N → equipment P103"
    reason = out["evidence"]["reason"]
    assert reason.startswith(
        "본문 '1라인 3호 프레스' → equipment P103 (명칭·별칭 일치)"
    )
    assert " · “새벽 2시쯤 1라인 3호 프레스 진동이" in reason


def test_lot_number_found_through_another_column_says_so():
    items = proposals()
    doc = items[5]
    doc["payload"].update(
        {"targetObjectRef": "erp.shipment:DL2610-0036", "mentionText": "LT261017-1"}
    )
    out = readable(items)[5]
    assert out["evidence"]["reason"] == (
        "본문 'LT261017-1' → shipment DL2610-0036 (다른 컬럼 값 일치)"
    )


def test_long_table_comments_are_shortened_in_titles():
    items = [
        object_type("PICK", "출고 피킹 실적", "wms.pick", ("item_cd", "품목 코드")),
        object_type(
            "ITEM",
            "자재·상품 품목 마스터(원자재, 포장재, OEM 상품)",
            "erp.item",
            ("item_cd", "품목 코드"),
        ),
        {
            "itemKind": "LINK_RULE",
            "payload": {
                "code": "PICK_ITEM_CD_TO_ITEM",
                "linkType": {
                    "code": "X",
                    "sourceTypeCode": "PICK",
                    "targetTypeCode": "ITEM",
                },
                "conditions": [
                    {"sourceProperty": "PICK.item_cd", "targetProperty": "ITEM.item_cd"}
                ],
            },
            "evidence": {"reason": "값이 이어진다."},
        },
    ]
    out = readable(items)
    assert (
        out[2]["payload"]["name"]
        == "출고 피킹 실적 → 자재·상품 품목 마스터 (품목 코드)"
    )
    assert (
        out[1]["payload"]["name"] == "자재·상품 품목 마스터(원자재, 포장재, OEM 상품)"
    )


def test_descriptions_and_reasons_use_object_names_not_table_keys():
    items = proposals()
    items[0]["payload"]["description"] = "원천 mes.work_order의 한 행"
    rule = items[3]
    rule["payload"]["description"] = (
        "비교 방법: so_ref는 숫자만 남김 (예: 'SO-1' → '1')"
    )
    rule["payload"]["linkType"]["description"] = (
        "WORK_ORDER가 SALES_ORDER를 가리킨다 (status = OPEN일 때). "
        "개수 관계는 데이터에서 관찰한 값(1:1). 업무상 한쪽이 여러 건일 수 있어 검토 필요."
    )
    rule["evidence"]["reason"] = "대상 키: erp.sales_order so_no (기본키) · 일치율 100%"
    rule["evidence"]["holdReason"] = "mes.work_order 경유 관계"

    out = readable(items)
    payload, evidence = out[3]["payload"], out[3]["evidence"]

    assert out[0]["payload"]["description"] == (
        "원천 테이블 mes.work_order (1행 = 작업지시 1건)"
    )
    assert payload["description"] == (
        "연결 기준: 작업지시 수주 참조 번호 = 수주 수주 번호 "
        "(비교: so_ref는 숫자만 남김 (예: 'SO-1' → '1'))"
    )
    assert payload["linkType"]["description"] == (
        "작업지시에서 수주 참조 · 적용 조건: status = OPEN · "
        "데이터상 1:1, 업무상 1:N 여부 확인 필요"
    )
    assert evidence["reason"] == "대상 키: 수주 so_no (기본키) · 일치율 100%"
    assert evidence["holdReason"] == "작업지시 경유 관계"


def test_via_table_hold_keeps_its_confidence_cap():
    from app.proposers.confidence import hold_cap

    cap, _ = hold_cap(
        "mes.work_order 경유 관계라 연결 규칙 생성 불가. 관계 유형만 제안"
    )
    assert cap == 0.4


def test_fingerprint_ignores_llm_wording_but_not_content():
    from app.proposers.api_format import fingerprint_payload

    base = {
        "code": "DEPARTMENT",
        "name": "부서",
        "description": "부서 기준 정보",
        "category": "master",
        "properties": [
            {"code": "dept_code", "name": "부서 코드", "description": "부서 식별 코드"}
        ],
    }
    reworded = {
        **base,
        "description": "조직 단위 기준 정보",
        "properties": [
            {
                "code": "dept_code",
                "name": "부서코드",
                "description": "부서를 가리키는 코드",
            }
        ],
    }
    added = {**base, "properties": [*base["properties"], {"code": "manager_emp_no"}]}
    assert fingerprint_payload(base) == fingerprint_payload(reworded)
    assert fingerprint_payload(base) != fingerprint_payload(added)
    assert fingerprint_payload(base) != fingerprint_payload(
        {**base, "category": "history"}
    )


def test_korean_and_upper_case_table_keys_become_object_names():
    from app.proposers.readable import Names, _table_names

    names = Names(
        [
            {
                "itemKind": "OBJECT_TYPE",
                "payload": {
                    "code": "ITEM",
                    "name": "품목",
                    "objectMapping": {"resourceKey": "erp.품목"},
                },
            },
            {
                "itemKind": "OBJECT_TYPE",
                "payload": {
                    "code": "QC",
                    "name": "검사 의뢰",
                    "objectMapping": {"resourceKey": "qms.QC_INSP_HDR"},
                },
            },
        ]
    )
    text = "대상 키: erp.품목 품목코드 · qms.QC_INSP_HDR INSP_NO · erp.품목.품목코드 · erp.품목."
    assert (
        _table_names(text, names)
        == "대상 키: 품목 품목코드 · 검사 의뢰 INSP_NO · erp.품목.품목코드 · 품목."
    )


def test_property_name_prefers_korean_column_over_unit_comment():
    from app.proposers.objects import property_name

    assert property_name("지시수량", "EA") == "지시수량"
    assert property_name("emp_no", "사번") == "사번"
    assert property_name("qty", "수량 (EA)") == "수량"
    assert property_name("receipt_no", None) == "receipt_no"
