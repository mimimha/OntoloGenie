"""처음 보는 원천 모양에서도 관계를 찾는지 (B3·B4 독립 데이터에서 드러난 표기 차이, 2026-11-06)."""

import json

import pandas as pd
import pytest

from app.proposers.links import propose_links
from app.proposers.objects import propose_objects


def write_table(root, system, name, pk, columns):
    """columns: {컬럼: (타입, 값 목록)}"""
    folder = root / system
    folder.mkdir(parents=True, exist_ok=True)
    pd.DataFrame({c: v for c, (_, v) in columns.items()}).to_csv(
        folder / f"{name}.csv", index=False, encoding="utf-8"
    )
    schema_file = folder / "_schema.json"
    schema = (
        json.loads(schema_file.read_text(encoding="utf-8"))
        if schema_file.exists()
        else {"tables": {}}
    )
    schema["tables"][name] = {
        "description": name,
        "primary_key": pk,
        "columns": {c: {"type": t, "comment": ""} for c, (t, _) in columns.items()},
    }
    schema_file.write_text(json.dumps(schema, ensure_ascii=False), encoding="utf-8")


def rules(root):
    return {
        (c.evidence[0].locator, c.evidence[1].locator): c
        for c in propose_links(root)
        if c.payload.kind.value == "LINK_RULE"
    }


def test_case_and_separator_differences_are_linked_but_held(tmp_path):
    # 숫자가 같은 원료가 여럿이라(soy-001, sug-001) 숫자 묶음만으로는 정할 수 없다
    codes = [f"rm-{kind}-{i:03d}" for kind in ("soy", "sug") for i in range(1, 11)]
    write_table(
        tmp_path,
        "scm",
        "material",
        ["materialCd"],
        {
            "materialCd": ("varchar(12)", [c.upper().replace("-", "_") for c in codes]),
            "name": ("varchar(20)", [f"원료{i}" for i in range(len(codes))]),
        },
    )
    write_table(
        tmp_path,
        "wms",
        "RCPT",
        ["RCPT_NO"],
        {
            "RCPT_NO": ("varchar(8)", [f"R{i:04d}" for i in range(40)]),
            "ITEM_CD": ("varchar(12)", codes * 2),
        },
    )

    rel = rules(tmp_path)[("wms.RCPT.ITEM_CD", "scm.material.materialCd")]

    # 구분자 바꾸기는 허용 변환에 없어 대응표로 잇고, 새 값이 안 이어지니 보류로 낸다
    assert rel.review_status == "HELD"
    step = rel.payload.conditions[0].source_transforms[0]
    assert step.op.value == "MAP_VALUE" and step.arg["rm-soy-001"] == "RM_SOY_001"


def test_year_shortened_number_is_linked_from_either_side(tmp_path):
    long_numbers = [f"PO202603{d:02d}-{n:03d}" for d in range(1, 11) for n in (1, 2)]
    write_table(
        tmp_path,
        "scm",
        "purchaseOrder",
        ["poNo"],
        {
            "poNo": ("varchar(14)", long_numbers),
            "supplier": ("varchar(10)", ["공급처"] * len(long_numbers)),
        },
    )
    write_table(
        tmp_path,
        "wms",
        "INBOUND",
        ["IN_NO"],
        {
            "IN_NO": ("varchar(8)", [f"I{i:04d}" for i in range(len(long_numbers))]),
            "PO_NO": ("varchar(9)", [n[4:10] + n[11:] for n in long_numbers]),
        },
    )

    rel = rules(tmp_path)[("wms.INBOUND.PO_NO", "scm.purchaseOrder.poNo")]

    # 긴 쪽(도착)에서 숫자만 남기고 앞의 '20'을 뺀다: PO20260301-001 → 260301001
    steps = [(s.op.value, s.arg) for s in rel.payload.conditions[0].target_transforms]
    assert steps == [("EXTRACT_NUMBER", None), ("STRIP_PREFIX", "20")]
    assert rel.review_status == "HELD"


def test_other_system_numbering_with_different_letters_is_held_not_dropped(tmp_path):
    write_table(
        tmp_path,
        "scm",
        "supplier",
        ["supplierId"],
        {
            "supplierId": ("varchar(10)", [f"SUP-{i:04d}" for i in range(1, 21)]),
            "name": ("varchar(20)", [f"공급처{i}" for i in range(1, 21)]),
        },
    )
    vendors = [f"S{i}" for i in range(1, 21)] + ["SUP-0003", "s7"]
    write_table(
        tmp_path,
        "wms",
        "INBOUND",
        ["IN_NO"],
        {
            "IN_NO": ("varchar(8)", [f"I{i:04d}" for i in range(len(vendors))]),
            "VENDOR_CD": ("varchar(10)", vendors),
        },
    )

    rel = rules(tmp_path)[("wms.INBOUND.VENDOR_CD", "scm.supplier.supplierId")]

    assert rel.review_status == "HELD"
    assert "다른 시스템" in rel.hold_reason


def test_same_system_numbers_with_different_letters_are_not_linked(tmp_path):
    # 같은 시스템 안의 거래처 C001과 설비 EQ-001은 일련번호만 우연히 같은 다른 대상이다
    write_table(
        tmp_path,
        "erp",
        "equipment",
        ["eq_cd"],
        {
            "eq_cd": ("varchar(8)", [f"EQ-{i:03d}" for i in range(1, 21)]),
            "name": ("varchar(10)", [f"설비{i}" for i in range(1, 21)]),
        },
    )
    write_table(
        tmp_path,
        "erp",
        "orders",
        ["order_no"],
        {
            "order_no": ("varchar(8)", [f"O{i:04d}" for i in range(20)]),
            "cust_cd": ("varchar(8)", [f"C{i:03d}" for i in range(1, 21)]),
        },
    )

    found = rules(tmp_path).get(("erp.orders.cust_cd", "erp.equipment.eq_cd"))
    # 숫자만 맞추는 다른 시스템 규칙은 같은 시스템에 쓰지 않는다(접두어 바꾸기 규칙이 잡으면 보류)
    assert found is None or (
        found.review_status == "HELD" and "다른 시스템" not in found.hold_reason
    )


def test_composite_key_with_transform_on_each_column(tmp_path):
    so = [f"SO-2026-{n:04d}" for n in range(1, 11)]
    write_table(
        tmp_path,
        "erp",
        "order_line",
        ["so_no", "line_no"],
        {
            "so_no": ("varchar(12)", [s for s in so for _ in (1, 2)]),
            "line_no": ("int", ["1", "2"] * len(so)),
            "qty": ("int", ["5"] * 2 * len(so)),
        },
    )
    write_table(
        tmp_path,
        "mes",
        "work_order",
        ["wo_id"],
        {
            "wo_id": ("varchar(8)", [f"W{i:04d}" for i in range(2 * len(so))]),
            "order_no": (
                "varchar(10)",
                [s.replace("-", "") for s in so for _ in (1, 2)],
            ),
            "order_seq": ("char(2)", ["01", "02"] * len(so)),
        },
    )

    rel = rules(tmp_path)[
        ("mes.work_order.order_no+order_seq", "erp.order_line.so_no+line_no")
    ]

    first, second = rel.payload.conditions
    assert first.source_transforms  # 주문 번호는 표기를 맞춘다
    assert [s.op.value for s in second.source_transforms] == ["TO_INTEGER"]
    assert rel.review_status == "HELD"


def test_table_without_any_key_uses_two_column_key(tmp_path):
    so = [f"SO{n:04d}" for n in range(1, 11)]
    write_table(
        tmp_path,
        "erp",
        "SUJU_DTL",
        [],
        {
            "수주번호": ("varchar(8)", [s for s in so for _ in (1, 2, 3)]),
            "순번": ("int", ["1", "2", "3"] * len(so)),
            "수량": ("int", ["10"] * 3 * len(so)),
        },
    )

    candidates = propose_objects(tmp_path)
    mapping = next(c for c in candidates if c.candidate_id == "OM-erp.SUJU_DTL")

    assert mapping.payload.key_columns == ["수주번호", "순번"]


def test_quantity_is_not_used_as_second_column_of_a_composite_key(tmp_path):
    so = [f"SO-2026-{n:04d}" for n in range(1, 11)]
    write_table(
        tmp_path,
        "erp",
        "order_line",
        ["so_no", "line_no"],
        {
            "so_no": ("varchar(12)", [s for s in so for _ in (1, 2)]),
            "line_no": ("int", ["1", "2"] * len(so)),
        },
    )
    write_table(
        tmp_path,
        "mes",
        "work_order",
        ["wo_id"],
        {
            "wo_id": ("varchar(8)", [f"W{i:04d}" for i in range(2 * len(so))]),
            "order_no": (
                "varchar(10)",
                [s.replace("-", "") for s in so for _ in (1, 2)],
            ),
            "planQty": ("int", ["1", "2"] * len(so)),  # 순번과 숫자만 같은 수량
        },
    )

    assert not [k for k in rules(tmp_path) if "planQty" in k[0]]


@pytest.mark.parametrize(
    "name, found, wrong_pending, wrong_held",
    [("b4", 14, 0, 4), ("b6", 17, 2, 6), ("b8", 20, 1, 2)],
)
def test_independent_datasets_keep_their_scores(name, found, wrong_pending, wrong_held):
    # 독립 데이터(규칙만, LLM 없음): 고친 코드로 찾는 관계 수가 떨어지거나 틀린 후보가 늘면 알 수 있게 둔다.
    # B4는 한글 컬럼·기본키 없는 표, B6·B8은 나중에 새로 만든 데이터. B3·B5·B7은 채점기로 따로 잰다
    import json
    from pathlib import Path

    from app.proposers.run import run
    from app.validators.evaluate_relations import evaluate

    data = Path(__file__).resolve().parents[1] / "data"
    answer = json.loads((data / "eval" / name / "answer.json").read_text("utf-8"))
    report = evaluate(run(data / "eval" / name / "data"), answer)
    assert report["found_pending"] + report["found_held"] >= found
    # 틀린 후보가 늘어도 알 수 있게 둔다(2026-11-09 값)
    assert len(report["wrong_pending"]) <= wrong_pending
    assert report["wrong_held"] <= wrong_held
