"""MR 전 코드 리뷰(2026-11-09)에서 찾은 경우들. 고친 뒤 다시 생기지 않는지 본다."""

import pytest
from test_links_general import rules, write_table

from app.llm.client import MockLLMClient
from app.proposers import llm_links
from app.proposers.links import propose_links

CODES = [f"rm-{kind}-{i:03d}" for kind in ("soy", "sug") for i in range(1, 11)]


def _materials(root):
    write_table(
        root,
        "scm",
        "material",
        ["materialCd"],
        {
            "materialCd": ("varchar(12)", [c.upper().replace("-", "_") for c in CODES]),
            "name": ("varchar(20)", [f"원료{i}" for i in range(len(CODES))]),
        },
    )


def test_status_column_with_one_dominant_value_is_not_a_pending_composite(tmp_path):
    # 상태 '10'이 95%라 행 기준으로는 줄 번호에 들어가지만, 맞는 값이 한 종류뿐이다
    so = [f"SO{i:03d}" for i in range(1, 21)]
    write_table(
        tmp_path,
        "erp",
        "sales_order",
        ["so_no"],
        {
            "so_no": ("varchar(5)", so),
            "cust": ("varchar(10)", [f"고객{i}" for i in range(20)]),
        },
    )
    lines = [(o, p) for o in so for p in ("10", "20")]
    write_table(
        tmp_path,
        "erp",
        "so_line",
        ["so_no", "line_no"],
        {
            "so_no": ("varchar(5)", [o for o, _ in lines]),
            "line_no": ("varchar(3)", [p for _, p in lines]),
        },
    )
    write_table(
        tmp_path,
        "erp",
        "delivery",
        ["dlv_no"],
        {
            "dlv_no": ("varchar(6)", [f"D{i:04d}" for i in range(100)]),
            "so_no": ("varchar(5)", [so[i % 20] for i in range(100)]),
            "status": ("varchar(3)", ["10" if i % 20 else "99" for i in range(100)]),
        },
    )
    composites = {k: v for k, v in rules(tmp_path).items() if "status" in k[0]}
    assert not composites


def test_space_separated_codes_are_still_linked(tmp_path):
    # 'rm soy 001' ↔ 'RM_SOY_001'은 글자 전체가 같은 코드다. 숫자 우연 거르기에 걸리면 안 된다
    _materials(tmp_path)
    write_table(
        tmp_path,
        "mes",
        "lot",
        ["lotNo"],
        {
            "lotNo": ("varchar(8)", [f"L{i:04d}" for i in range(40)]),
            "materialRef": (
                "varchar(12)",
                [CODES[i % 20].replace("-", " ") for i in range(40)],
            ),
        },
    )
    rel = rules(tmp_path).get(("mes.lot.materialRef", "scm.material.materialCd"))
    assert rel is not None and rel.review_status == "HELD"


def test_many_codes_mapping_to_one_number_are_not_other_system_links(tmp_path):
    # RM_SOY_001·RM_SUG_001이 둘 다 L0001로 가면 숫자만 겹친 것이다
    _materials(tmp_path)
    write_table(
        tmp_path,
        "mes",
        "lot",
        ["lotNo"],
        {"lotNo": ("varchar(8)", [f"L{i:04d}" for i in range(1, 41)])},
    )
    assert ("scm.material.materialCd", "mes.lot.lotNo") not in rules(tmp_path)


def test_long_numeric_key_does_not_stop_the_analysis(tmp_path):
    ids = [str(98000000000000000000 + i) for i in range(30)]
    write_table(
        tmp_path,
        "mes",
        "tag",
        ["tag_id"],
        {"tag_id": ("varchar(20)", ids), "kind": ("varchar(5)", ["A"] * 30)},
    )
    write_table(
        tmp_path,
        "mes",
        "scan",
        ["scan_no"],
        {
            "scan_no": ("varchar(6)", [f"S{i:04d}" for i in range(60)]),
            "tag_id": ("varchar(20)", [ids[i % 30] for i in range(60)]),
        },
    )
    assert ("mes.scan.tag_id", "mes.tag.tag_id") in rules(tmp_path)


def test_same_system_numbers_with_different_letters_are_not_linked(tmp_path):
    # 입고 GR-2026-00001과 수주 SO-26-00001은 일련번호만 같은 다른 문서다
    write_table(
        tmp_path,
        "erp",
        "sales_order",
        ["so_no"],
        {"so_no": ("varchar(12)", [f"SO-26-{i:05d}" for i in range(1, 31)])},
    )
    write_table(
        tmp_path,
        "erp",
        "receipt_log",
        ["log_no"],
        {
            "log_no": ("varchar(8)", [f"X{i:04d}" for i in range(60)]),
            "receipt_ref": (
                "varchar(16)",
                [f"GR-2026-{i % 30 + 1:05d}" for i in range(60)],
            ),
        },
    )
    assert not [k for k in rules(tmp_path) if k[1] == "erp.sales_order.so_no"]


def _shipments(root):
    write_table(
        root,
        "erp",
        "customer",
        ["customerId"],
        {
            "customerId": ("integer", list(range(1, 51))),
            "name": ("varchar(20)", [f"고객사{i}" for i in range(1, 51)]),
        },
    )
    write_table(
        root,
        "scm",
        "shipment",
        ["shipNo"],
        {
            "shipNo": ("varchar(8)", [f"SH{i:04d}" for i in range(200)]),
            "consignee": ("integer", [i % 50 + 1 for i in range(200)]),
        },
    )


def test_llm_link_with_same_id_as_rule_candidate_is_not_sent_twice(tmp_path):
    _shipments(tmp_path)
    candidates = propose_links(tmp_path)
    _, prompts = llm_links.prepare(tmp_path, candidates)
    target = next(
        t["id"]
        for _, _, targets, _ in prompts
        for t in targets
        if t["table"] == "erp.customer" and t["columns"] == ["customerId"]
    )
    llm = MockLLMClient(
        {
            "links": [
                {
                    "source_table": "scm.shipment",
                    "source_columns": ["consignee"],
                    "target": target,
                    "steps": [[]],
                    "reason": "출하 수취인은 고객이다",
                }
            ]
        }
    )
    cross, _ = llm_links.propose(tmp_path, candidates, llm)
    ids = [c.candidate_id for c in candidates + cross]
    assert len(ids) == len(set(ids))


class FailingLLM(MockLLMClient):
    def complete_json(self, prompt):
        from app.llm.client import LLMError

        raise LLMError("시간 초과")


def test_failed_call_does_not_count_previous_usage(tmp_path):
    _shipments(tmp_path)
    write_table(
        tmp_path,
        "mes",
        "pick",
        ["pickNo"],
        {
            "pickNo": ("varchar(8)", [f"P{i:04d}" for i in range(50)]),
            "custRef": ("varchar(8)", [f"C-{i % 50 + 1}" for i in range(50)]),
        },
    )
    llm = FailingLLM({})
    llm.model = "gpt-6.1-sol"
    llm.last_usage = {"input_tokens": 100000, "output_tokens": 100000}
    _, report = llm_links.propose(tmp_path, propose_links(tmp_path), llm)
    assert report["calls"] >= 1 and len(report["failed"]) == report["calls"]
    assert report["credits_used"] == 0


@pytest.mark.parametrize(
    "pattern, ok",
    [
        (r"(\d|\d)*X", False),
        (r"\d*\d*\d*X", False),
        (r"(a|aa)*b", False),
        (r"(\w+)+$", False),
        (r"^([A-Z]+)-(\d+)$", True),
        (r"^RL-\d{4}-(P\d{6})-\d{4}$", True),
        (r"^(SO|PO)-(\d+)$", True),
    ],
)
def test_slow_regex_from_llm_is_rejected(pattern, ok):
    assert llm_links.safe_pattern(pattern) is ok
