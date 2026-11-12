"""FN-AIP-04 객체 유형·속성 후보. 코드 규칙으로만 만든다(method=rule, LLM 미사용).

원천 표 한 행 = 객체 하나. 이름과 설명은 표·컬럼 주석을 그대로 쓴다.
표 종류(category)는 데이터 모델링 규칙(classify)으로 정하고, 정답셋을 보고 규칙을 고치지 않는다.
"""

import re
from collections import defaultdict
from pathlib import Path

from app.analyzers.structured import (
    MASK,
    ColumnAnalysis,
    StructuredAnalysis,
    analyze_sources,
)
from app.llm.privacy import SURNAMES, is_person_column, looks_like_name, mask_value
from app.models.candidate import (
    Candidate,
    DataType,
    Evidence,
    ObjectMappingPayload,
    ObjectTypePayload,
    PropertyMappingPayload,
    PropertyPayload,
    Sensitivity,
)
from app.models.source import ColumnRef, TableSchema
from app.proposers import confidence as score
from app.sources.csv_reader import CsvSourceReader

TIME_TYPES = ("date", "timestamp", "datetime", "time")
PAIR_KEY_COLUMNS = 6  # 기본키 없는 표에서 두 컬럼 키를 찾을 때 앞에서부터 볼 컬럼 수


HANGUL = re.compile(r"[가-힣]")


def property_name(column: str, comment: str | None) -> str:
    """검토 화면의 속성 이름. 한글 컬럼 이름은 그대로 업무 이름으로 쓰고, 아니면 주석의 괄호 앞부분을 쓴다.

    주석이 단위·값 목록(EA, kg, 지시/진행/완료)이면 이름이 아니므로 한글 컬럼 이름을 우선한다. 둘 다 한글이 아니면
    LLM 해석에서 받은 짧은 이름으로 바꾼다(semantics).
    """
    if HANGUL.search(column):
        return column
    head = (comment or "").split("(")[0].strip()
    return head or column


def observed_pair_key(df, names: list[str]) -> list[str]:
    """기본키도 값이 모두 다른 한 컬럼도 없는 표에서, 빈 값 없이 조합이 모두 다른 두 컬럼.
    오래된 시스템의 상세 표는 기본키를 선언하지 않은 경우가 많다."""
    names = names[:PAIR_KEY_COLUMNS]
    for i, a in enumerate(names):
        for b in names[i + 1 :]:
            sub = df[[a, b]]
            if len(sub) >= 3 and sub.notna().all().all() and not sub.duplicated().any():
                return [a, b]
    return []


def key_like_columns(schema: TableSchema, columns: list[ColumnAnalysis]) -> list[str]:
    """키로 쓸 만한 컬럼: 문자형·정수형, 개인정보·날짜·소수가 아님."""
    by_name = {c.ref.rsplit(".", 1)[1]: c for c in columns}
    out = []
    for col in schema.columns:
        stats = by_name[col.name]
        declared = (stats.declared_type or "").lower()
        if stats.is_personal or declared.startswith(
            (*TIME_TYPES, "numeric", "decimal", "float", "double")
        ):
            continue
        out.append(col.name)
    return out


INT_TYPES = ("int", "integer", "bigint", "smallint")
PERSON_NAME = re.compile(rf"[{SURNAMES}][가-힣]{{1,3}}")


def to_code(table: str) -> str:
    """객체 유형 코드. 객체·관계 유형은 대문자(WORK_ORDER), 속성은 컬럼 이름 그대로."""
    return table.upper()


def data_type(column: ColumnAnalysis, values: list[str]) -> DataType:
    declared = (column.declared_type or "").lower()
    if declared:
        if declared.startswith(INT_TYPES):
            return DataType.INTEGER
        if declared.startswith(("numeric", "decimal", "float", "double", "real")):
            return DataType.DECIMAL
        if declared == "date":
            return DataType.DATE
        if declared.startswith(("timestamp", "datetime")):
            return DataType.DATETIME
        if declared.startswith("time"):
            return DataType.TIME
        if declared.startswith("bool"):
            return DataType.BOOLEAN
        return DataType.STRING
    sample = [v for v in values if v][:200]
    if sample and all(re.fullmatch(r"-?\d+", v) for v in sample):
        return DataType.INTEGER
    if sample and all(re.fullmatch(r"-?\d+\.\d+", v) for v in sample):
        return DataType.DECIMAL
    if sample and all(re.fullmatch(r"\d{4}-\d{2}-\d{2}", v) for v in sample):
        return DataType.DATE
    if sample and all(
        re.fullmatch(r"\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}(:\d{2})?", v) for v in sample
    ):
        return DataType.DATETIME
    if sample and all(re.fullmatch(r"\d{2}:\d{2}(:\d{2})?", v) for v in sample):
        return DataType.TIME
    return DataType.STRING


def looks_like_person_names(values: list[str]) -> bool:
    """주석이 없어도 값이 한국 사람 이름 모양(성 + 1~3글자)이면 개인정보로 본다."""
    distinct = {v for v in values if v}
    if len(distinct) < 3:
        return False
    # 몇 가지 값이 여러 행에 되풀이되고 세 글자 이름 모양도 드물면 종류 코드다('공구'·'기계장치'·'차량').
    # 작업자 몇 명이 되풀이되는 이름 칸('김민수'·'이서준')은 세 글자라 그대로 이름으로 본다
    three = sum(1 for v in distinct if len(v) == 3) / len(distinct)
    if (
        len(distinct) < 10
        and len([v for v in values if v]) >= 2 * len(distinct)
        and three < 0.8
    ):
        return False
    matched = sum(1 for v in distinct if PERSON_NAME.fullmatch(v))
    return matched / len(distinct) >= 0.8


def is_time(column: ColumnAnalysis, values: list[str]) -> bool:
    declared = (column.declared_type or "").lower()
    if declared:
        return declared.startswith(TIME_TYPES)
    return data_type(column, values) in (
        DataType.DATE,
        DataType.DATETIME,
        DataType.TIME,
    )


def _is_status(name: str, comment: str | None) -> bool:
    return "status" in name.lower() or "상태" in (comment or "")


def classify(
    schema: TableSchema,
    columns: list[ColumnAnalysis],
    values: dict[str, list[str]],
    in_tables: set[str],
    pk_out: set[str],
) -> tuple[str | None, str]:
    """(표 종류, 판단 이유). 종류가 None이면 객체로 내지 않는다."""
    names = {c.ref.rsplit(".", 1)[1]: c for c in columns}
    if "row_key" in names and "operation" in names:
        return None, "변경 대상 행 키와 작업 종류 컬럼이 있는 동기화 기록"
    pk = schema.primary_key
    times = [n for n, c in names.items() if is_time(c, values[n])]
    if pk and any(n in times for n in pk):
        return (
            "history",
            f"기본키에 일자 컬럼 {', '.join(n for n in pk if n in times)} 포함",
        )
    if len(pk) > 1:
        refs = [n for n in pk if n in pk_out]
        seq = [
            n
            for n in pk
            if n not in refs and data_type(names[n], values[n]) == DataType.INTEGER
        ]
        if refs and seq:
            return (
                "detail",
                f"복합 기본키 {_cols(pk)} 중 {_cols(refs)} 상위 참조, {_cols(seq)} 순번",
            )
        return "snapshot", f"복합 기본키 {_cols(pk)} 기준"
    if not in_tables and times:
        return (
            "history",
            f"참조하는 테이블 없음, 일자 컬럼 {', '.join(times)} 보유",
        )
    status = [n for n, c in names.items() if _is_status(n, c.comment)]
    if status and len(times) >= 2:
        return (
            "transaction",
            f"상태 컬럼 {', '.join(status)}, 일자 컬럼 {len(times)}개 보유",
        )
    return (
        "master",
        f"타 테이블 {len(in_tables)}개에서 참조"
        if in_tables
        else "기타 분류 조건 미해당",
    )


def _cols(names) -> str:
    """컬럼 목록을 a, b로 쓴다."""
    return ", ".join(str(n) for n in names)


TYPE_TEXT = {
    "STRING": "문자",
    "INTEGER": "정수",
    "DECIMAL": "소수",
    "DATE": "날짜",
    "TIME": "시각",
    "DATETIME": "일시",
    "BOOLEAN": "예/아니요",
    "JSON": "JSON",
}

CATEGORY_TEXT = {
    "master": "기준 정보",
    "transaction": "거래 문서",
    "detail": "문서 상세 줄",
    "history": "발생 기록",
    "snapshot": "현재 상태",
}


def propose_objects(
    root: Path, analysis: StructuredAnalysis | None = None
) -> list[Candidate]:
    root = Path(root)
    analysis = analysis or analyze_sources(root)
    in_tables, pk_out = defaultdict(set), defaultdict(set)
    for link in analysis.links:
        from_table, from_col = link.from_ref.rsplit(".", 1)
        to_table = link.to_ref.rsplit(".", 1)[0]
        in_tables[to_table].add(from_table)
        pk_out[from_table].add(from_col)
    candidates = []
    for folder in sorted(p for p in root.iterdir() if p.is_dir()):
        reader = CsvSourceReader(folder.name, folder)
        for table in reader.list_tables():
            key = f"{folder.name}.{table}"
            if key not in analysis.tables:
                continue
            candidates += _table_candidates(
                reader, folder.name, table, analysis, in_tables[key], pk_out[key]
            )
    return candidates


def _table_candidates(
    reader, source, table, analysis, in_tables, pk_out
) -> list[Candidate]:
    key = f"{source}.{table}"
    schema = reader.read_schema(table)
    df = reader.read_rows(table)
    columns = analysis.tables[key]
    values = {c.name: df[c.name].dropna().astype(str).tolist() for c in schema.columns}
    category, why = classify(schema, columns, values, in_tables, pk_out)
    if category is None:
        return []
    code = to_code(table)
    keys = (
        schema.primary_key
        or [
            k.columns[0]
            for k in analysis.table_keys.get(key, [])
            if k.source == "observed"
        ][:1]
        or observed_pair_key(df, key_like_columns(schema, columns))
    )
    name = schema.description or table
    table_evidence = Evidence(
        source_id=source,
        locator=key,
        snippet=f"{len(df):,}행 · 기본키 {_cols(keys) or '없음'} · 참조 테이블 {_cols(sorted(in_tables)) or '없음'}",
    )
    out = [
        Candidate(
            candidate_id=f"OT-{key}",
            title=f"객체 유형 {code} ({name})",
            payload=ObjectTypePayload(
                code=code,
                name=name,
                description=f"원천 {key}의 한 행",
                category=category,
            ),
            reason=f"분류: {CATEGORY_TEXT.get(category, category)} ({why})",
            evidence=[table_evidence],
            confidence=score.build(
                score.object_type(bool(schema.primary_key), bool(keys), len(in_tables)),
                hold=None if keys else "식별 키 없음",
            ),
            review_status="PENDING" if keys else "HELD",
            hold_reason=None
            if keys
            else "식별 키를 찾지 못해 같은 객체를 다시 찾을 수 없음",
        )
    ]
    if keys:
        out.append(
            Candidate(
                candidate_id=f"OM-{key}",
                title=f"{code} ← {key}",
                payload=ObjectMappingPayload(
                    object_type_code=code,
                    source_id=source,
                    resource_key=key,
                    key_columns=keys,
                ),
                reason=f"행 식별: 기본키 {_cols(keys)}"
                if schema.primary_key
                else f"행 식별: 중복 없는 컬럼 {_cols(keys)} (선언된 기본키 없음)",
                evidence=[table_evidence],
                confidence=score.build(score.object_mapping(bool(schema.primary_key))),
            )
        )
    by_name = {c.ref.rsplit(".", 1)[1]: c for c in columns}
    for col in schema.columns:
        stats = by_name[col.name]
        col_values = values[col.name]
        # 담당·검사자처럼 사람을 적는 칸에 이름이 섞여 있으면(부서명 사이 '홍보람') 일부만 이름이어도 개인정보로 본다
        personal = (
            stats.is_personal
            or looks_like_person_names(col_values)
            or (
                is_person_column(f"{col.name} {col.comment or ''}")
                and any(isinstance(v, str) and looks_like_name(v) for v in col_values)
            )
        )
        dtype = data_type(stats, col_values)
        # 다른 칸에 섞인 사람 이름 모양 값은 근거·LLM 질문에 그대로 쓰지 않는다
        hint = f"{col.name} {col.comment or ''}"
        examples = [
            mask_value(v, hint) if isinstance(v, str) else v
            for v in stats.sample_values[:3]
        ]
        shown = [MASK] if personal else examples
        ref = f"{key}.{col.name}"
        evidence = Evidence(
            source_id=source,
            locator=ref,
            snippet=f"값 {stats.row_count - stats.null_count}/{stats.row_count}, 서로 다른 값 {stats.distinct_count}, 예 {shown}",
            values=[] if personal else [v for v in examples if v != MASK],
        )
        why_personal = (
            "이름·주석이 개인정보를 가리킴"
            if stats.is_personal
            else "값이 사람 이름 모양"
            if personal
            else ""
        )
        out.append(
            Candidate(
                candidate_id=f"P-{ref}",
                title=f"속성 {code}.{col.name}",
                payload=PropertyPayload(
                    object_type_code=code,
                    code=col.name,
                    name=property_name(col.name, col.comment),
                    data_type=dtype,
                    description=col.comment or "",
                    is_identifier=col.name in keys,
                    is_required=stats.null_count == 0,
                    sensitivity=Sensitivity.RESTRICTED
                    if personal
                    else Sensitivity.NORMAL,
                ),
                reason=f"자료형: {TYPE_TEXT.get(dtype.value, dtype.value)} "
                + (
                    f"({col.declared_type})"
                    if col.declared_type
                    else "(값 모양으로 추정)"
                )
                + (" · 식별 키" if col.name in keys else "")
                + (
                    " · 빈 값 없음"
                    if stats.null_count == 0
                    else f" · 빈 값 {stats.null_count}행"
                )
                + (f" · 개인정보 ({why_personal})" if personal else ""),
                evidence=[evidence],
                confidence=score.build(
                    score.property_(
                        bool(col.declared_type),
                        stats.null_count / stats.row_count if stats.row_count else 1.0,
                    )
                ),
            )
        )
        out.append(
            Candidate(
                candidate_id=f"PM-{ref}",
                title=f"{code}.{col.name} ← {ref}",
                payload=PropertyMappingPayload(
                    object_type_code=code,
                    property_code=col.name,
                    source_column=ColumnRef(
                        source_id=source,
                        table=table,
                        column=col.name,
                        resource_key=key,  # OBJECT_MAPPING resourceKey와 같은 값
                    ),
                ),
                reason="원천 컬럼 값을 그대로 속성에 넣는다",
                evidence=[evidence],
                confidence=score.build(score.property_mapping()),
            )
        )
    return out
