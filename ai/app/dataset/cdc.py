"""CDC 변경 재현 시나리오 (DAT-02, S15P31C106-172).

최초 적재 뒤 원천 MES에 추가·수정·삭제·컬럼 추가를 차례로 일으키는 SQL과, 단계마다
CDC가 내보낼 이벤트·다시 분석할 표·AI 후보의 기대 결과를 만든다. 시연 데이터의 기존 행은
건드리지 않고 새 작업지시 하나와 그 공정만 바꾸므로, 마지막 되돌리기 SQL로 처음 상태로 돌아간다.
CDC 수집(S15P31C106-197·198)과 재분석(S15P31C106-170·284) 확인에 같은 파일을 쓴다.
"""

import json
from pathlib import Path

DESCRIPTION = (
    "CDC 변경 재현 시나리오. 최초 적재(data-v1.0)와 CDC 커넥터 등록 뒤 원천 MES DB에 단계별로 "
    "적용한다(분석 입력 아님). 원천 DB 관리자 계정으로 실행한다. 플랫폼 계정"
    "(platform_ro·platform_ai·platform_cdc)은 쓰기 권한이 없다. 적용한 동안에는 수주 참조 연결률(134/136 → 135/137)과 "
    "OD-2610-020의 작업지시 수가 바뀌므로 S1~S5 시연 전에는 cdc_99_restore.sql로 되돌린다."
)
WORK_ORDER_COLUMNS = (
    "wo_no",
    "product_code",
    "die_drawing_code",
    "status",
    "priority",
    "line",
    "planned_qty",
    "produced_qty",
    "defect_qty",
    "planned_start",
    "planned_end",
    "actual_start",
    "actual_end",
    "so_ref",
)
OPERATION_COLUMNS = ("wo_no", "op_seq", "equipment_code", "status", "remaining_qty")
NEW_COLUMN = {"table": "mes.work_order", "column": "due_date", "type": "date"}
NEW_COLUMN_COMMENT = "납기일"
# 작업 시작 시각. 데이터 기준 시각(2026-10-30 14:00)보다 앞이어야 "진행" 상태와 맞는다
STARTED = "2026-10-30 13:00:00"
TOPIC = "MES_MAIN.{table}"  # infra/cdc: topic.prefix = data_source.code


def _literal(value: str) -> str:
    if value == "":
        return "null"
    return "'" + value.replace("'", "''") + "'"


def _insert(table: str, row: dict) -> str:
    columns = ", ".join(row)
    values = ", ".join(_literal(v) for v in row.values())
    return f"insert into {table} ({columns}) values ({values});"


def _where(key: dict) -> str:
    return " and ".join(f"{k} = {_literal(v)}" for k, v in key.items())


def _update(table: str, key: dict, values: dict) -> str:
    sets = ", ".join(f"{k} = {_literal(v)}" for k, v in values.items())
    return f"update {table} set {sets} where {_where(key)};"


def _delete(table: str, key: dict) -> str:
    return f"delete from {table} where {_where(key)};"


def _event(table: str, op: str, key: dict) -> dict:
    return {"topic": TOPIC.format(table=table), "op": op, "key": key}


def build_steps(rows: dict) -> list[dict]:
    """rows: Builder.rows. 마지막 작업지시를 본떠 새 작업지시 하나를 만든다."""
    template = rows["mes.work_order"][-1]
    wo_no = (
        template["wo_no"][:7] + "0901"
    )  # 'JB2610-0143' → 'JB2610-0901' (기존 번호와 겹치지 않음)
    if any(w["wo_no"] == wo_no for w in rows["mes.work_order"]):
        raise ValueError(f"새 작업지시 번호가 이미 있습니다: {wo_no}")
    order = {c: template[c] for c in WORK_ORDER_COLUMNS}
    order.update(
        wo_no=wo_no,
        status="대기",
        produced_qty="0",
        defect_qty="0",
        actual_start="",
        actual_end="",
    )
    operations = [
        {
            **{c: op[c] for c in OPERATION_COLUMNS},
            "wo_no": wo_no,
            "status": "대기",
            "remaining_qty": order["planned_qty"],
        }
        for op in rows["mes.work_order_operation"]
        if op["wo_no"] == template["wo_no"]
    ]
    first, last = operations[0], operations[-1]
    order_key = {"wo_no": wo_no}

    def op_key(op):
        return {"wo_no": wo_no, "op_seq": op["op_seq"]}

    started = STARTED
    column = NEW_COLUMN["column"]
    due = order["planned_end"]
    return [
        {
            "step": 1,
            "file": "cdc_01_insert.sql",
            "title": "작업지시 추가",
            "changes": [
                {"table": "mes.work_order", "insert": order},
                *(
                    {"table": "mes.work_order_operation", "insert": op}
                    for op in operations
                ),
            ],
            "cdc_events": [
                _event("mes.work_order", "c", order_key),
                *(
                    _event("mes.work_order_operation", "c", op_key(op))
                    for op in operations
                ),
            ],
            "reanalyze_tables": ["mes.work_order", "mes.work_order_operation"],
            "expected_proposals": "모두 유지(값만 늘고 표·컬럼 구조는 그대로)",
        },
        {
            "step": 2,
            "file": "cdc_02_update.sql",
            "title": "작업 시작(상태 수정)",
            "changes": [
                {
                    "table": "mes.work_order",
                    "key": order_key,
                    "set": {"status": "진행", "actual_start": started},
                },
                {
                    "table": "mes.work_order_operation",
                    "key": op_key(first),
                    "set": {"status": "진행"},
                },
            ],
            "cdc_events": [
                _event("mes.work_order", "u", order_key),
                _event("mes.work_order_operation", "u", op_key(first)),
            ],
            "reanalyze_tables": ["mes.work_order", "mes.work_order_operation"],
            "expected_proposals": "모두 유지",
        },
        {
            "step": 3,
            "file": "cdc_03_delete.sql",
            "title": "마지막 공정 취소(행 삭제)",
            "changes": [{"table": "mes.work_order_operation", "delete": op_key(last)}],
            "cdc_events": [_event("mes.work_order_operation", "d", op_key(last))],
            "reanalyze_tables": ["mes.work_order_operation"],
            "expected_proposals": "모두 유지",
            "note": "삭제 이벤트의 before에는 기본키 컬럼만 있다(REPLICA IDENTITY 기본값).",
        },
        {
            "step": 4,
            "file": "cdc_04_add_column.sql",
            "title": "납기일 컬럼 추가",
            "changes": [
                {
                    "table": NEW_COLUMN["table"],
                    "add_column": dict(NEW_COLUMN, comment=NEW_COLUMN_COMMENT),
                },
                {"table": "mes.work_order", "key": order_key, "set": {column: due}},
            ],
            "cdc_events": [_event("mes.work_order", "u", order_key)],
            "reanalyze_tables": ["mes.work_order"],
            "expected_proposals": "작업지시(WORK_ORDER) 객체 후보만 변경(속성 due_date 추가), 나머지 유지",
            "expected_changed": [{"itemKind": "OBJECT_TYPE", "code": "WORK_ORDER"}],
            "note": (
                "컬럼 추가 자체는 이벤트가 없고, 다음 변경 이벤트의 schema에 새 컬럼이 실린다. "
                "플랫폼 컬럼 목록(015)에는 원천 연결 검사 뒤 나타난다."
            ),
        },
        {
            "step": 99,
            "file": "cdc_99_restore.sql",
            "title": "처음 상태로 되돌리기",
            "changes": [
                {"table": NEW_COLUMN["table"], "drop_column": column},
                # 앞 단계를 일부만 적용했어도 되돌릴 수 있게 작업지시 번호로 한 번에 지운다
                {"table": "mes.work_order_operation", "delete": order_key},
                {"table": "mes.work_order", "delete": order_key},
            ],
            "cdc_events": [
                *(
                    _event("mes.work_order_operation", "d", op_key(op))
                    for op in operations[:-1]
                ),
                _event("mes.work_order", "d", order_key),
            ],
            "reanalyze_tables": ["mes.work_order", "mes.work_order_operation"],
            "expected_proposals": "최초 적재와 같음",
            "note": (
                "1~4단계를 모두 적용했을 때의 이벤트다. 남아 있는 행만 삭제 이벤트가 나오고, "
                "한 단계 안의 이벤트 순서는 목록과 다를 수 있다."
            ),
        },
    ]


def step_sql(step: dict) -> str:
    lines = [f"-- {step['step']}. {step['title']} (cdc_changes.json 참고)", "begin;"]
    for change in step["changes"]:
        table = change["table"]
        if "insert" in change:
            lines.append(_insert(table, change["insert"]))
        elif "set" in change:
            lines.append(_update(table, change["key"], change["set"]))
        elif "delete" in change:
            lines.append(_delete(table, change["delete"]))
        elif "add_column" in change:
            add = change["add_column"]
            lines.append(
                f"alter table {table} add column {add['column']} {add['type']};"
            )
            lines.append(
                f"comment on column {table}.{add['column']} is {_literal(add['comment'])};"
            )
        elif "drop_column" in change:
            lines.append(
                f"alter table {table} drop column if exists {change['drop_column']};"
            )
        else:
            raise ValueError(f"알 수 없는 변경: {change}")
    lines.append("commit;")
    return "\n".join(lines) + "\n"


def write_cdc_changes(rows: dict, folder: Path) -> None:
    steps = build_steps(rows)
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "cdc_changes.json").write_text(
        json.dumps(
            {"description": DESCRIPTION, "steps": steps}, ensure_ascii=False, indent=2
        )
        + "\n",
        encoding="utf-8",
    )
    for step in steps:
        (folder / step["file"]).write_text(
            f"-- {DESCRIPTION}\n" + step_sql(step), encoding="utf-8"
        )
