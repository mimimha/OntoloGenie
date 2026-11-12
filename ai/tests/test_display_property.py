"""대표 속성 고르기(S15P31C106-370). 대표 속성이 없으면 수집한 객체의 표시 이름이 원천 키 값(P101 등)이 된다."""

from pathlib import Path

from app.proposers.api_format import display_property, fingerprint_payload
from app.proposers.run import api_result, generate

DATA = Path(__file__).resolve().parents[1] / "data" / "data-v1.0"


def prop(code, name, **extra):
    return {"code": code, "name": name, "dataType": "STRING", **extra}


def test_name_column_wins_over_name_like_label():
    props = [
        prop("eq_code", "설비 코드", isIdentifier=True),
        prop("model_nm", "모델명"),
        prop("name", "설비 이름"),
    ]
    assert display_property(props) == "name"


def test_name_suffix_then_korean_label():
    assert (
        display_property([prop("spec", "규격"), prop("item_name", "품명")])
        == "item_name"
    )
    assert display_property([prop("title", "공정명")]) == "title"


def test_column_starting_with_table_name_wins_among_name_columns():
    props = [prop("item_group_name", "품목군명"), prop("item_name", "품명")]
    assert display_property(props, "item") == "item_name"
    assert display_property(props) == "item_group_name"  # 표를 모르면 컬럼 순서
    assert display_property([prop("model_nm", "모델")]) == "model_nm"


def test_words_ending_in_myeong_that_are_not_names_are_skipped():
    for label in ("서명", "공구 수명", "작업 조명", "검사 증명"):
        assert display_property([prop("x", label)]) is None


def test_personal_identifier_non_text_and_description_are_not_chosen():
    props = [
        prop("emp_name", "이름", sensitivity="RESTRICTED"),
        prop("code_name", "코드", isIdentifier=True),
        {"code": "qty_name", "name": "수량", "dataType": "INTEGER"},
        prop("reason", "정지 사유 설명"),
    ]
    assert display_property(props) is None


def test_display_property_does_not_change_fingerprint():
    # 이 칸이 생기기 전에 승인한 유형이 재분석에서 '변경'으로 다시 올라오지 않아야 한다
    payload = {"code": "ITEM", "properties": [prop("item_name", "품명")]}
    assert fingerprint_payload(payload) == fingerprint_payload(
        {**payload, "displayPropertyCode": "item_name"}
    )


def test_demo_master_tables_get_their_name_column():
    proposals = api_result(generate(DATA)[0])["proposals"]
    chosen = {
        p["payload"]["objectMapping"]["resourceKey"]: p["payload"].get(
            "displayPropertyCode"
        )
        for p in proposals
        if p["itemKind"] == "OBJECT_TYPE" and p["payload"].get("objectMapping")
    }
    assert chosen["erp.item"] == "item_name"
    assert chosen["erp.vendor"] == "vendor_name"
    assert chosen["mes.equipment"] == "name"
    assert chosen["mes.routing"] == "op_name"
    # 이름이 개인정보인 표와 이름 칸이 없는 발생 기록은 비워 둔다
    assert chosen["mes.worker"] is None
    assert chosen["erp.employee"] is None
    assert chosen["mes.work_order"] is None
