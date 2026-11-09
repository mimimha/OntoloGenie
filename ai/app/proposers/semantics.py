"""FN-AIP-04·05 LLM 의미 해석. 규칙 후보에 업무 의미를 더한다(method=rule+llm).

표마다 LLM을 한 번 부른다. 보내는 것: 표·컬럼 이름과 주석, 컬럼 통계, 개인정보를 가린 표본(최대 20행),
코드가 찾은 관계 후보. 받는 것: 객체 이름·뜻·종류, 컬럼 뜻·역할·개인정보 여부, 관계 후보마다 업무상 맞는지.

합치는 규칙 (LLM 판단은 신뢰도 점수에 더하지 않고 llm_judgement에 따로 적는다)
- 객체 종류: LLM 판단을 쓰고 규칙 판단은 llm_judgement에 남긴다.
- 속성: 뜻을 설명에 넣는다. 개인정보는 규칙 OR LLM.
- 관계: LLM이 맞다고 해도 점수·상태는 그대로. 아니라고 하면 보류(HELD)로 바꾸고 상한 0.3(confidence.CAP).
- 형식에 안 맞는 답, 표에 없는 컬럼, 목록에 없는 관계는 버린다.

실제 GMS 호출은 크레딧을 쓰므로 사용자 승인 뒤에만 한다. 테스트는 가짜 응답(MockLLMClient)으로만 한다.
"""

import json
from pathlib import Path
from typing import Literal

import pandas as pd
from pydantic import BaseModel, Field, ValidationError

from app.analyzers.structured import value_pattern
from app.llm.client import LLMClient, LLMError, token_credits
from app.llm.privacy import MASK, mask_text
from app.models.candidate import Candidate, DataType, ItemKind, Sensitivity
from app.proposers.confidence import CAP
from app.sources.csv_reader import CsvSourceReader

MAX_SAMPLE_ROWS = 20  # LLM에 보내는 표본 행 수 상한
Role = Literal[
    "identifier", "reference", "name", "attribute", "measure", "status", "time"
]
ROLE_GUIDE = (
    "identifier=이 표의 행을 구별하는 번호, reference=다른 표의 행을 가리키는 번호, name=사람이 읽는 이름, "
    "attribute=일반 속성, measure=수량·금액·시간 같은 숫자, status=상태·구분 코드, time=날짜·시각"
)
Category = Literal["master", "transaction", "detail", "history", "snapshot"]
CATEGORY_GUIDE = (
    "master=기준 정보(품목·거래처·설비처럼 오래 쓰는 대상), transaction=거래 문서(발주·수주·작업지시·전표처럼 "
    "상태가 바뀌는 업무 문서), detail=문서 상세 줄, history=발생 기록(실적·출하·측정처럼 일어난 일), "
    "snapshot=현재 상태(창고별 재고처럼 조합별 현재 값)"
)


class ColumnView(BaseModel):
    name: str
    meaning: str = Field(min_length=1)
    role: Role
    data_type: DataType
    is_personal: bool = False


class LinkView(BaseModel):
    code: str
    meaningful: bool
    reason: str = Field(min_length=1)


class TableView(BaseModel):
    object_name: str = Field(min_length=1)
    meaning: str = Field(min_length=1)
    category: Category
    category_reason: str = Field(min_length=1)
    columns: list[ColumnView] = Field(default_factory=list)
    links: list[LinkView] = Field(default_factory=list)


def _table_of(candidate: Candidate) -> str | None:
    if candidate.candidate_id.startswith(("OT-", "OM-")):
        return candidate.candidate_id[3:]
    if candidate.candidate_id.startswith(("P-", "PM-")):
        return candidate.candidate_id.split("-", 1)[1].rsplit(".", 1)[0]
    return None


def pick_sample_positions(df: pd.DataFrame, limit: int) -> list[int]:
    """앞에서부터 자르지 않고 값이 고루 보이게 고른다.

    1) 빈 값이 있는 행 (최대 limit의 1/4) 2) 컬럼마다 모양이 다른 값(예: 2610021과 OD-2610-021)
    3) 남는 자리는 표 전체에서 같은 간격으로. 같은 표면 항상 같은 행을 고른다.
    """
    if len(df) <= limit:
        return list(range(len(df)))
    chosen: list[int] = []

    def take(position: int, cap: int = limit) -> None:
        if position not in chosen and len(chosen) < cap:
            chosen.append(position)

    for name in df.columns:
        missing = [i for i, v in enumerate(df[name].isna()) if v]
        if missing:
            take(missing[0], cap=max(1, limit // 4))
    for name in df.columns:
        seen = set()
        for i, value in enumerate(df[name]):
            if pd.isna(value):
                continue
            shape = value_pattern(str(value))
            if shape not in seen:
                seen.add(shape)
                take(i)
            if len(seen) >= 3:
                break
    step = len(df) / limit
    for k in range(limit):
        take(int(k * step))
    return sorted(chosen)


def build_prompt(
    table: str,
    schema,
    rows: list[dict],
    properties: list[Candidate],
    links: list[Candidate],
) -> str:
    column_lines = []
    for p in properties:
        load = p.payload
        snippet = p.evidence[0].snippet
        comment = load.description or "없음"
        column_lines.append(
            f"- {load.code} | 주석 {comment} | 규칙 자료형 {load.data_type.value} | {snippet}"
        )
    link_lines = [f"- {c.payload.code}: {c.reason}" for c in links] or ["- 없음"]
    return "\n".join(
        [
            "너는 제조 공장 ERP·MES 데이터의 업무 의미를 해석한다. 아래 원천 표 하나를 보고 JSON 하나로 답한다.",
            "확실하지 않으면 meaning에 '추정'이라고 쓴다. 표에 없는 컬럼이나 목록에 없는 관계는 만들지 않는다. '***'는 가린 개인정보다.",
            f"category 값: {CATEGORY_GUIDE}",
            f"role 값: {ROLE_GUIDE}",
            f"data_type 값: {', '.join(t.value for t in DataType)}",
            (
                '형식: {"object_name": 객체 유형 이름(한 행이 무엇인지 나타내는 짧은 명사, 예: 작업지시·공정 실적), "meaning": 표 설명, "category", "category_reason", '
                '"columns": [{"name", "meaning", "role", "data_type", "is_personal"}], '
                '"links": [{"code", "meaningful": 업무상 맞는 관계인지, "reason"}]}'
            ),
            (
                "관계 meaningful 규칙: 값이 우연히 같은 것(순번·등급·수량이 다른 표의 번호와 같아짐)이면 false."
                " 일부 행에서만 맞더라도 업무상 그 값을 가리키는 관계면 true로 두고 reason에 그 조건을 쓴다."
            ),
            "",
            f"표: {table} ({schema.description or '설명 없음'})",
            *([f"표 참고: {schema.note}"] if schema.note else []),
            "컬럼:",
            *column_lines,
            "코드가 찾은 관계 후보(이 표에서 출발):",
            *link_lines,
            f"표본 {len(rows)}행:",
            json.dumps(rows, ensure_ascii=False),
        ]
    )


def sample(df: pd.DataFrame, personal: set[str]) -> list[dict]:
    picked = df.iloc[pick_sample_positions(df, MAX_SAMPLE_ROWS)].copy()
    for name in personal:
        if name in picked:
            picked[name] = MASK
    for name in picked.columns:
        if name not in personal:
            picked[name] = picked[name].map(
                lambda v: mask_text(v).text if isinstance(v, str) else v
            )
    return json.loads(picked.to_json(orient="records", force_ascii=False))


def prepare(
    root: Path, candidates: list[Candidate], only: set[str] | None = None
) -> list[tuple[str, list[Candidate], list[Candidate], str]]:
    """표마다 (표, 속성 후보, 출발 관계 후보, 프롬프트). only가 있으면 그 표만."""
    root = Path(root)
    tables = sorted(
        {_table_of(c) for c in candidates if c.candidate_id.startswith("OT-")}
    )
    out = []
    if only is not None:
        tables = [t for t in tables if t in only]
    for table in tables:
        source, name = table.split(".", 1)
        reader = CsvSourceReader(source, root / source)
        props = [
            c
            for c in candidates
            if c.candidate_id.startswith("P-") and _table_of(c) == table
        ]
        links = [
            c
            for c in candidates
            if c.payload.kind == ItemKind.LINK_RULE
            and c.evidence[0].locator.startswith(table + ".")
        ]
        personal = {
            c.payload.code
            for c in props
            if c.payload.sensitivity == Sensitivity.RESTRICTED
        }
        rows = sample(reader.read_rows(name), personal)
        out.append(
            (
                table,
                props,
                links,
                build_prompt(table, reader.read_schema(name), rows, props, links),
            )
        )
    return out


def estimate_credits(
    prompts, output_tokens_per_table: int = 1500, model: str | None = None
) -> float:
    """호출 전 대략 추정. 한국어·JSON이 섞인 글은 글자 2개를 토큰 약 1개로 본다."""
    tokens = sum(len(p[3]) for p in prompts) / 2
    return token_credits(model, tokens, len(prompts) * output_tokens_per_table)


def parse(answer: dict, column_names: set[str], link_codes: set[str]) -> TableView:
    try:
        view = TableView.model_validate(answer)
    except ValidationError as error:
        raise LLMError(f"LLM 답 형식이 맞지 않습니다: {error.errors()[:3]}") from error
    view.columns = [c for c in view.columns if c.name in column_names]
    view.links = [link for link in view.links if link.code in link_codes]
    return view


def _note(candidate: Candidate, judgement: str, **update) -> Candidate:
    """LLM 판단은 신뢰도 점수에 넣지 않고 따로 적는다(LLM 동의만으로 점수가 오르지 않게)."""
    return candidate.model_copy(
        update={"method": "rule+llm", "llm_judgement": judgement, **update}
    )


def rejected_codes(
    candidates: list[Candidate], views: dict[str, TableView]
) -> dict[str, str]:
    """규칙이 이미 보류로 둔 관계를 LLM도 업무상 맞지 않다고 본 것 {관계 코드: LLM 이유}.

    둘 다 확신이 없는 관계라 관리자 검토 목록에 올리지 않는다(관계 유형·연결 규칙 모두).
    규칙이 대기로 둔 관계는 LLM이 반대해도 빼지 않고 보류로만 바꾼다(apply).
    """
    link_view = {link.code: link for v in views.values() for link in v.links}
    out = {}
    for c in candidates:
        if c.payload.kind != ItemKind.LINK_RULE or c.review_status != "HELD":
            continue
        judged = link_view.get(c.payload.code)
        if judged and not judged.meaningful:
            out[c.payload.code] = judged.reason
    return out


def apply(candidates: list[Candidate], views: dict[str, TableView]) -> list[Candidate]:
    link_view = {link.code: link for v in views.values() for link in v.links}
    rejected = rejected_codes(candidates, views)
    out = []
    for c in candidates:
        if (
            c.payload.kind in (ItemKind.LINK_RULE, ItemKind.LINK_TYPE)
            and c.payload.code in rejected
        ):
            continue
        table = _table_of(c)
        view = views.get(table) if table else None
        if c.candidate_id.startswith("OT-") and view:
            rule_category = c.payload.category
            payload = c.payload.model_copy(
                update={
                    "name": view.object_name,
                    "description": view.meaning,
                    "category": view.category,
                }
            )
            same = rule_category == view.category
            c = _note(
                c,
                f"표 종류 {view.category}"
                + ("(규칙과 같음)" if same else f"(규칙은 {rule_category})")
                + f": {view.category_reason}",
                payload=payload,
            )
        elif c.candidate_id.startswith("P-") and view:
            col = next((x for x in view.columns if x.name == c.payload.code), None)
            if col:
                payload = c.payload.model_copy(
                    update={
                        "description": col.meaning,
                        "sensitivity": Sensitivity.RESTRICTED
                        if col.is_personal
                        else c.payload.sensitivity,
                    }
                )
                same = col.data_type == c.payload.data_type
                c = _note(
                    c,
                    f"역할 {col.role}, 자료형 {col.data_type.value}"
                    + ("" if same else f"(규칙은 {c.payload.data_type.value})")
                    + (", 개인정보" if col.is_personal else ""),
                    payload=payload,
                )
        elif c.payload.kind in (ItemKind.LINK_RULE, ItemKind.LINK_TYPE):
            judged = link_view.get(c.payload.code)
            if judged and judged.meaningful:
                c = _note(c, f"업무상 맞는 관계: {judged.reason}")
            elif judged:
                # 부정 판단만 반영한다: 보류로 바꾸고 상한을 둔다
                # 이미 더 낮은 보류 상한이 있으면 그대로 둔다(LLM 부정으로 점수가 오르지 않게)
                cap, cap_reason = CAP, "LLM이 업무상 맞지 않다고 봄"
                if c.confidence.cap is not None and c.confidence.cap < CAP:
                    cap = c.confidence.cap
                    cap_reason = f"{c.confidence.cap_reason} / {cap_reason}"
                conf = c.confidence.model_copy(
                    update={"cap": cap, "cap_reason": cap_reason}
                )
                c = _note(
                    c,
                    f"업무상 맞지 않음: {judged.reason}",
                    confidence=conf,
                    review_status="HELD",
                    hold_reason=f"LLM: {judged.reason}",
                )
        out.append(c)
    return out


def interpret(
    root: Path,
    candidates: list[Candidate],
    llm: LLMClient,
    only: set[str] | None = None,
) -> tuple[list[Candidate], dict]:
    prompts = prepare(root, candidates, only)
    views, failed, used = {}, [], 0.0
    for table, props, links, prompt in prompts:
        try:
            answer = llm.complete_json(prompt)
            views[table] = parse(
                answer,
                {p.payload.code for p in props},
                {link.payload.code for link in links},
            )
        except LLMError as error:
            failed.append({"table": table, "error": str(error)})
            continue
        usage = getattr(llm, "last_usage", None) or {}
        used += token_credits(
            getattr(llm, "model", None),
            usage.get("input_tokens", 0),
            usage.get("output_tokens", 0),
        )
    report = {
        "tables": len(prompts),
        "interpreted": len(views),
        "failed": failed,
        "credits_used": round(used, 2),
        # 규칙 보류 + LLM 반대로 내보내지 않은 관계 {코드: 이유}
        "rejected_links": rejected_codes(candidates, views),
    }
    return apply(candidates, views), report
