"""규칙이 못 이은 시스템 간 연결을 LLM에게 묻고 값으로 확인한다 (가짜 LLM, 크레딧 없음)."""

from app.llm.client import MockLLMClient
from app.proposers.llm_links import ROUNDS, propose
from app.proposers.run import generate
from tests.test_links_general import write_table


def batch_data(root):
    # 배치 번호의 앞뒤 순서가 시스템마다 다르다: lims '0123-B24' ↔ mes 'B24-0123'
    batches = [f"B{y}-{n:04d}" for y in (24, 25) for n in range(1, 11)]
    write_table(
        root,
        "mes",
        "BATCH",
        ["BATCH_SEQ"],
        {
            "BATCH_SEQ": ("int", [str(i) for i in range(1, len(batches) + 1)]),
            "BATCH_NO": ("varchar(10)", batches),
        },
    )
    lots = [f"{b[4:]}-{b[:3]}" for b in batches] * 2
    write_table(
        root,
        "lims",
        "sample",
        ["sample_id"],
        {
            "sample_id": ("varchar(8)", [f"S{i:04d}" for i in range(len(lots))]),
            "lot_no": ("varchar(10)", lots),
            "box_no": ("int", [str(i % 7 + 1) for i in range(len(lots))]),
        },
    )


def answer_for_column(source_table, source, target_column, steps):
    def answer(prompt):
        import json

        targets = json.loads(prompt.rsplit("도착 키:\n", 1)[1])
        tid = next((t["id"] for t in targets if t["columns"] == [target_column]), None)
        if tid is None:
            return {"links": []}
        return {
            "links": [
                {
                    "source_table": source_table,
                    "source_columns": [source],
                    "target": tid,
                    "steps": [steps],
                    "reason": "같은 배치",
                }
            ]
        }

    return answer


def answer_for(target_column, steps, source="lot_no"):
    def answer(prompt):
        # 프롬프트에 보인 도착 id 중 원하는 컬럼의 id를 찾는다
        import json

        targets = json.loads(prompt.rsplit("도착 키:\n", 1)[1])
        tid = next((t["id"] for t in targets if t["columns"] == [target_column]), None)
        if tid is None:  # 다른 시스템을 물을 때는 이을 것이 없다고 답한다
            return {"links": []}
        return {
            "links": [
                {
                    "source_table": "lims.sample",
                    "source_columns": [source],
                    "target": tid,
                    "steps": [steps],
                    "reason": "같은 배치 번호",
                }
            ]
        }

    return answer


def test_swapped_code_is_linked_after_checking_values(tmp_path):
    batch_data(tmp_path)
    kept, _ = generate(tmp_path)
    llm = MockLLMClient(
        answer_for(
            "BATCH_NO",
            [{"op": "regex", "pattern": r"^(\d{4})-(B\d{2})$", "repl": r"\2-\1"}],
        )
    )

    found, report = propose(tmp_path, kept, llm)

    rule = next(c for c in found if c.payload.kind.value == "LINK_RULE")
    assert rule.evidence[0].locator == "lims.sample.lot_no"
    assert rule.evidence[1].locator == "mes.BATCH.BATCH_NO"
    # LLM이 제안한 연결은 보류로만 내고, 허용 변환에 없는 순서 바꾸기는 대응표로 낸다
    assert rule.review_status == "HELD" and rule.method == "llm"
    step = rule.payload.conditions[0].source_transforms[0]
    assert step.op.value == "MAP_VALUE" and step.arg["0001-B24"] == "B24-0001"
    assert "변환 함수 추가 검토" in rule.hold_reason
    assert report["calls"] == 2 * ROUNDS and report["proposed"] == 1


def test_llm_suggestion_that_values_do_not_support_is_dropped(tmp_path):
    batch_data(tmp_path)
    kept, _ = generate(tmp_path)
    # LLM이 틀린 변환(접두어 'X-' 붙이기)을 주면 실제 값이 이어지지 않아 버린다
    llm = MockLLMClient(answer_for("BATCH_NO", [{"op": "prefix", "value": "X-"}]))

    found, report = propose(tmp_path, kept, llm)

    assert found == [] and report["proposed"] == 0


def test_unknown_target_or_bad_answer_is_ignored(tmp_path):
    batch_data(tmp_path)
    kept, _ = generate(tmp_path)

    found, _ = propose(
        tmp_path,
        kept,
        MockLLMClient(
            {
                "links": [
                    {
                        "source_table": "lims.sample",
                        "source_columns": ["lot_no"],
                        "target": "T999",
                        "steps": [[]],
                    }
                ]
            }
        ),
    )
    assert found == []

    found, report = propose(tmp_path, kept, MockLLMClient({"links": "x"}))
    assert found == [] and report["failed"]


def test_allowed_steps_become_allowed_transforms_and_others_do_not():
    from app.proposers.llm_links import Step, apply, to_allowed

    steps = [Step(op="zfill", width=4), Step(op="prefix", value="C")]
    assert apply(steps, "45") == "C0045"
    assert to_allowed(steps) == [
        {"op": "PAD_ZEROS", "arg": 4},
        {"op": "ADD_PREFIX", "arg": "C"},
    ]
    swap = [Step(op="regex", pattern=r"^(\d{4})-(B\d{2})$", repl=r"\2-\1")]
    assert apply(swap, "0123-B24") == "B24-0123"
    assert to_allowed(swap) is None  # 허용 목록에 없어 대응표로 낸다
    assert apply([Step(op="모르는것")], "x") is None


def test_link_without_steps_becomes_same_value_rule(tmp_path):
    # 규칙 기준에 못 미쳐 못 이은 같은 값 연결을 LLM이 변환 없이 주는 경우
    from app.analyzers.structured import analyze_sources
    from app.proposers.links import Tables, to_candidates
    from app.proposers.llm_links import LinkAnswer, _verify

    batch_data(tmp_path)
    tables = Tables(tmp_path, analyze_sources(tmp_path))
    target = {"table": "mes.BATCH", "columns": ["BATCH_NO"], "key": "alternate"}
    link = LinkAnswer(
        source_table="mes.BATCH",
        source_columns=["BATCH_NO"],
        target="T1",
        steps=[[]],
    )
    # 자기 표로 잇는 답은 버린다
    assert _verify(tables, link, target, {"mes.BATCH.BATCH_NO"}) is None

    write_table(
        tmp_path,
        "lims",
        "retest",
        ["retest_id"],
        {
            "retest_id": ("varchar(6)", [f"RT{i:03d}" for i in range(12)]),
            "batch_no": ("varchar(10)", ["B24-0001", "B24-0002", "B25-0003"] * 4),
        },
    )
    tables = Tables(tmp_path, analyze_sources(tmp_path))
    link = link.model_copy(
        update={"source_table": "lims.retest", "source_columns": ["batch_no"]}
    )
    rel = _verify(tables, link, target, {"lims.retest.batch_no"})

    rule = next(
        c for c in to_candidates([rel], tables) if c.payload.kind.value == "LINK_RULE"
    )
    assert rule.payload.pattern.value == "same_value"
    # 물어보지 않은 컬럼(규칙이 이미 이은 컬럼)의 답은 받지 않는다
    assert _verify(tables, link, target, set()) is None


def test_bad_llm_answers_never_stop_the_analysis(tmp_path):
    # 없는 그룹 이름, 같은 컬럼 두 번, 자기 표로 잇기, 느려질 수 있는 정규식, 같은 답 반복
    batch_data(tmp_path)
    kept, _ = generate(tmp_path)

    def answer(prompt):
        import json

        targets = json.loads(prompt.rsplit("도착 키:\n", 1)[1])
        ids = {tuple(t["columns"]): t["id"] for t in targets}
        batch = ids.get(("BATCH_NO",))
        pair = next((i for c, i in ids.items() if len(c) == 2), None)
        if batch is None:
            return {
                "links": [
                    {
                        "source_table": "mes.BATCH",
                        "source_columns": ["BATCH_NO"],
                        "target": next(iter(ids.values())),
                        "steps": [[]],
                    }
                ]
            }
        good = {
            "source_table": "lims.sample",
            "source_columns": ["lot_no"],
            "target": batch,
            "steps": [
                [{"op": "regex", "pattern": r"^(\d{4})-(B\d{2})$", "repl": r"\2-\1"}]
            ],
        }
        links = [
            {
                **good,
                "steps": [[{"op": "regex", "pattern": "(x)", "repl": r"\g<nope>"}]],
            },
            {**good, "steps": [[{"op": "regex", "pattern": r"^(\d+)+$", "repl": "x"}]]},
            good,
            good,
        ]
        if pair:
            links.append(
                {
                    **good,
                    "source_columns": ["lot_no", "lot_no"],
                    "target": pair,
                    "steps": [[], []],
                }
            )
        return {"links": links}

    found, _ = propose(tmp_path, kept, MockLLMClient(answer))

    rules = [c for c in found if c.payload.kind.value == "LINK_RULE"]
    assert [r.evidence[0].locator for r in rules] == [
        "lims.sample.lot_no"
    ]  # 맞는 답 하나만
    assert len({c.candidate_id for c in found}) == len(found)


def test_nested_repeat_patterns_are_refused():
    from app.proposers.llm_links import safe_pattern

    assert not safe_pattern(r"^(\d+)+$")
    assert not safe_pattern(r"(a*)*")
    assert safe_pattern(r"^(\d{4})-(B\d{2})$")


def test_lowercase_source_values_are_retried_with_upper(tmp_path):
    # LLM이 대문자만 맞춘 정규식을 줘도, 값 절반이 소문자인 원천은 대문자로 바꿔 한 번 더 확인한다
    batches = [f"H24A{n:04d}-03" for n in range(1, 21)]
    write_table(
        tmp_path,
        "l2",
        "coil",
        ["coil_id"],
        {
            "coil_id": ("varchar(12)", batches),
            "grade": ("varchar(4)", ["SS"] * len(batches)),
        },
    )
    charg = [f"03{b[:8]}" for b in batches]
    charg = [c.lower() if i % 2 else c for i, c in enumerate(charg)] * 2
    write_table(
        tmp_path,
        "sap",
        "LIPS",
        ["POS"],
        {
            "POS": ("varchar(6)", [f"{i:06d}" for i in range(len(charg))]),
            "CHARG": ("varchar(10)", charg),
        },
    )
    kept, _ = generate(tmp_path)
    llm = MockLLMClient(
        answer_for_column(
            "sap.LIPS",
            "CHARG",
            "coil_id",
            [
                {
                    "op": "regex",
                    "pattern": r"^(\d{2})(H\d{2}[A-Z]\d{4})$",
                    "repl": r"\2-\1",
                }
            ],
        )
    )

    found, report = propose(tmp_path, kept, llm)

    rule = next(c for c in found if c.payload.kind.value == "LINK_RULE")
    assert "upper" in rule.hold_reason
    assert report["not_verified"] == []


def test_links_from_both_rounds_are_merged(tmp_path, monkeypatch):
    # 첫 번째 답은 놓치고 두 번째 답만 찾아도 후보로 낸다(LLM 답이 실행마다 다른 경우)
    monkeypatch.setattr("app.proposers.llm_links.ROUNDS", 2)
    batch_data(tmp_path)
    kept, _ = generate(tmp_path)
    found_answer = answer_for(
        "BATCH_NO",
        [{"op": "regex", "pattern": r"^(\d{4})-(B\d{2})$", "repl": r"\2-\1"}],
    )
    lims_calls = []

    def answer(prompt):
        if "lims.sample" not in prompt.split("도착 키:")[0]:
            return {"links": []}  # 다른 시스템
        lims_calls.append(1)
        return {"links": []} if len(lims_calls) == 1 else found_answer(prompt)

    found, _ = propose(tmp_path, kept, MockLLMClient(answer))

    rules = [c for c in found if c.payload.kind.value == "LINK_RULE"]
    assert len(lims_calls) == 2
    assert [c.evidence[0].locator for c in rules] == ["lims.sample.lot_no"]
