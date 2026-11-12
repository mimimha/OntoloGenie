"""검토 화면용 이름·설명 (FN-AIP-05).

같은 분석의 객체 유형·속성 이름으로 한국어 제목·설명을 붙이고, 이름을 모르면 코드를 그대로 쓴다.
"""

import re

from app.proposers.korean import josa


def _short(name: str) -> str:
    # 앞 공백까지 정규식으로 잡으면 되돌아가기가 생겨 띄어쓰기는 split으로 정리한다
    short = " ".join(re.sub(r"\([^()]*\)", " ", name).split())
    return short or name


class Names:
    def __init__(self, proposals: list[dict]):
        self.types: dict[str, str] = {}  # 객체 유형 코드 → 이름
        self.tables: dict[str, str] = {}  # 원천 표(resourceKey) → 객체 유형 이름
        self.props: dict[str, str] = {}  # '유형코드.속성코드' → 속성 이름
        # '유형코드.속성코드' → 원천 컬럼 이름. 속성 코드는 바뀔 수 있어(customerId → customer_id) 글에서는 이것을 찾는다
        self.columns: dict[str, str] = {}
        for p in proposals:
            if p.get("itemKind") != "OBJECT_TYPE":
                continue
            payload = p.get("payload") or {}
            code, name = payload.get("code"), payload.get("name")
            if not code:
                continue
            self.types[code] = name or code
            key = (payload.get("objectMapping") or {}).get("resourceKey")
            if key:
                self.tables[key] = name or code
            for prop in payload.get("properties") or []:
                if prop.get("code"):
                    self.props[f"{code}.{prop['code']}"] = (
                        prop.get("name") or prop["code"]
                    )
                    column = (prop.get("sourceColumn") or {}).get("column")
                    if column:
                        self.columns[f"{code}.{prop['code']}"] = column

    def type(self, code: str | None) -> str:
        """제목·설명용 이름. 표 주석의 괄호 설명은 빼서 짧게 쓴다."""
        return _short(self.types.get(code or "", code or ""))

    def table(self, resource_key: str) -> str:
        return _short(self.tables.get(resource_key, resource_key.rsplit(".", 1)[-1]))

    def prop(self, ref: str | None) -> str:
        return self.props.get(ref or "", (ref or "").rsplit(".", 1)[-1])

    def column(self, ref: str) -> str:
        return self.columns.get(ref, ref.rsplit(".", 1)[-1])


# 한글은 \w에 들어가 'erp.employee의'를 놓치므로 영문·숫자만 경계로 본다
_TABLE_KEY = re.compile(
    r"(?<![A-Za-z0-9_.])([a-z][a-z0-9_]*\.[a-z][a-z0-9_]*)(?![A-Za-z0-9_.])"
)
_CONDITION = re.compile(r"\((\w+) = ([^)]+)일 때\)")
_VIA = re.compile(r"\(\w+로 ([a-z][\w]*\.[a-z][\w]*)를 찾아 그 \w+로 비교\)")


def _table_names(text: str, names: Names) -> str:
    """이유·보류 이유의 원천 표 이름을 객체 유형 이름으로. 모르는 표는 그대로."""
    text = _TABLE_KEY.sub(
        lambda m: names.table(m.group(1)) if m.group(1) in names.tables else m.group(1),
        text,
    )
    # 한글·대문자 표 이름(erp.품목, qms.QC_INSP_HDR)은 위 정규식에 안 걸려 아는 표 이름으로 한 번 더 바꾼다
    for key in sorted(names.tables, key=len, reverse=True):
        if key in text and not _TABLE_KEY.fullmatch(key):
            # 바꿀 이름에 백슬래시가 있어도('규격(mm\inch)') 치환 문법으로 읽지 않게 함수로 넘긴다
            name = names.table(key)
            text = re.sub(
                rf"(?<![\w.]){re.escape(key)}(?!\w|\.\w)",
                lambda _m, name=name: name,
                text,
            )
    return text


def _name_link_type(link_type: dict, names: Names, document: bool = False) -> None:
    source = names.type(link_type.get("sourceTypeCode"))
    target = names.type(link_type.get("targetTypeCode"))
    if not source or not target:
        return
    original = link_type.get("description") or ""
    if document:
        link_type["name"] = f"{source}의 {target} 연결"
        link_type["description"] = (
            f"{source} 본문에 기재된 {josa(target, '과/와')} 연결"
        )
        return
    link_type["name"] = f"{source} → {target}"
    parts = [f"{source}에서 {target} 참조"]
    condition = _CONDITION.search(original)
    if condition:
        parts.append(f"적용 조건: {condition[1]} = {condition[2]}")
    via = _VIA.search(original)
    if via:
        parts.append(f"{names.table(via[1])} 경유")
    if "(1:1)" in original:
        parts.append("데이터상 1:1, 업무상 1:N 여부 확인 필요")
    link_type["description"] = " · ".join(parts)


def _column_names(text: str, payload: dict, names: Names) -> str:
    """연결 규칙 이유·설명의 영문 컬럼 이름(so_ref는, item_code)을 속성 이름으로 바꾼다."""
    refs = [
        ref
        for condition in payload.get("conditions") or []
        for ref in (condition.get("sourceProperty"), condition.get("targetProperty"))
        if ref
    ]
    # "대상 키: 표 컬럼 (기본키)" 부분은 언제나 대상 쪽이라 컬럼 이름이 같아도 대상 속성 이름으로 바꾼다
    head, sep, rest = text.partition(" · ")
    if head.startswith("대상 키:"):
        for condition in payload.get("conditions") or []:
            ref = condition.get("targetProperty")
            if ref and names.prop(ref) != names.column(ref):
                column = re.escape(names.column(ref))
                name = names.prop(ref)
                head = re.sub(
                    rf"(?<![\w.']){column}(?![\w.'])", lambda _m, name=name: name, head
                )
        text = head + sep + rest
    by_column: dict[str, set[str]] = {}
    for ref in refs:
        by_column.setdefault(names.column(ref), set()).add(names.prop(ref))
    for ref in refs:
        column, name = names.column(ref), names.prop(ref)
        # 출발·대상 컬럼 이름이 같은데 속성 이름이 다르면 어느 쪽인지 알 수 없어 그대로 둔다
        if name == column or len(by_column[column]) > 1:
            continue
        text = re.sub(
            rf"(?<![\w.']){re.escape(column)}(은|는)(?!\w)",
            lambda _m, n=name: josa(n, "은/는"),
            text,
        )
        text = re.sub(
            rf"(?<![\w.']){re.escape(column)}(?![\w.'])", lambda _m, n=name: n, text
        )
    return text


def _link_rule_description(payload: dict, names: Names) -> str | None:
    """payload.description('비교 방법: 값을 그대로 비교')을 무엇과 무엇을 맞추는지로 바꾼다."""
    link_type = payload.get("linkType") or {}
    source = names.type(link_type.get("sourceTypeCode"))
    target = names.type(link_type.get("targetTypeCode"))
    conditions = payload.get("conditions") or []
    if not source or not target or not conditions:
        return None
    by = "·".join(names.prop(c.get("sourceProperty")) for c in conditions)
    to = "·".join(names.prop(c.get("targetProperty")) for c in conditions)
    how = _column_names(
        (payload.get("description") or "").removeprefix("비교 방법: "), payload, names
    )
    rule = f"연결 기준: {source} {by} = {target} {to}"
    if how in ("", "값을 그대로 비교"):
        return rule
    return f"{rule} (비교: {how})"


def _link_rule_name(payload: dict, names: Names) -> tuple[str, str] | None:
    link_type = payload.get("linkType") or {}
    source = names.type(link_type.get("sourceTypeCode"))
    target = names.type(link_type.get("targetTypeCode"))
    conditions = payload.get("conditions") or []
    if not source or not target or not conditions:
        return None
    by = "·".join(names.prop(c.get("sourceProperty")) for c in conditions)
    title = f"{source} → {target} ({by})"
    lead = f"{source}의 {josa(by, '으로/로')} {josa(target, '을/를')} 찾습니다."
    return title, lead


def _excerpt(evidence: dict, payload: dict, limit: int = 60) -> str:
    """언급이 든 문장. 줄·마침표에서 끊고, 길면 언급 앞뒤만 남긴다."""
    text = evidence.get("originalText") or ""
    start, end = payload.get("spanStart"), payload.get("spanEnd")
    if not text or not isinstance(start, int) or not isinstance(end, int):
        return ""
    left = max(text.rfind(ch, 0, start) for ch in "\n.!?") + 1
    ends = [i for i in (text.find(ch, end) for ch in "\n.!?") if i != -1]
    right = min(ends) + 1 if ends else len(text)
    sentence = " ".join(text[left:right].split())
    if len(sentence) <= limit:
        return sentence
    at = sentence.find(payload.get("mentionText") or "")
    a = max(0, at - limit // 3)
    a = sentence.find(" ", a) + 1 if a > 0 else 0
    b = (
        sentence.rfind(" ", 0, min(len(sentence), a + limit))
        if a + limit < len(sentence)
        else len(sentence)
    )
    return (
        ("…" if a > 0 else "")
        + sentence[a:b].strip()
        + ("…" if b < len(sentence) else "")
    )


def _document_name(
    payload: dict, evidence: dict, names: Names, choices: int
) -> tuple[str, str] | None:
    ref = payload.get("targetObjectRef") or ""
    if ":" not in ref:
        return None
    table, key = ref.split(":", 1)
    items = evidence.get("items") or []
    locator = items[0].get("locator", "") if items else ""
    doc_table = locator.rsplit(".", 1)[0] if locator.count(".") >= 2 else ""
    doc = names.table(doc_table) if doc_table else "문서"
    target = names.table(table)
    mention = payload.get("mentionText") or key
    # 같은 문서 종류 이름이 줄마다 반복되지 않게 제목은 '문서 번호 → 대상'으로 쓴다
    doc_id = payload.get("documentId") or doc
    excerpt = _excerpt(evidence, payload)
    quote = f" · “{excerpt}”" if excerpt else ""
    quoted = f"'{mention}'"
    if choices > 1:
        title = f"{doc_id} → {target} (후보 {choices}개)"
        lead = f"본문 {quoted} 해당 {target} 후보 {choices}개{quote}"
        # 원래 이유의 첫 문장은 같은 말이라 빼고 단서만 잇는다
        hint = evidence.get("reason") or ""
        hint = hint[hint.find("작성자") :] if "작성자" in hint else ""
        return title, f"{lead} {hint}".strip()
    title = f"{doc_id} → {target} {key}"
    alias = any(
        "이름·별명" in (c.get("label") or "")
        for c in evidence.get("confidenceItems") or []
    )
    if mention.strip().upper() == key.strip().upper():
        lead = f"본문 기재 {quoted}"
    elif alias:
        lead = f"본문 {quoted} → {target} {key} (명칭·별칭 일치)"
    else:
        lead = f"본문 {quoted} → {target} {key} (다른 컬럼 값 일치)"
    return title, lead + quote


def readable(proposals: list[dict]) -> list[dict]:
    """105 proposals[]에 한국어 제목·설명을 붙인다. 이름만 바뀐 것이 '변경'이 되지 않게 지문은 그대로 둔다."""
    names = Names(proposals)
    for p in proposals:
        kind, payload, evidence = (
            p.get("itemKind"),
            p.get("payload") or {},
            p.get("evidence") or {},
        )
        made = None
        if kind == "OBJECT_TYPE":
            # 규칙만 쓴 설명을 문장으로. LLM 설명은 그대로 둔다
            plain = re.fullmatch(
                r"원천 (\S+)의 한 행", payload.get("description") or ""
            )
            if plain and payload.get("name"):
                payload["description"] = (
                    f"원천 테이블 {plain[1]} (1행 = {payload['name']} 1건)"
                )
        elif kind == "LINK_RULE":
            if payload.get("linkType"):
                _name_link_type(payload["linkType"], names)
            description = _link_rule_description(payload, names)
            if description:
                payload["description"] = description
            made = _link_rule_name(payload, names)
        elif kind == "LINK_TYPE":
            document = str(evidence.get("candidateId") or "").startswith("LT-DOC-")
            _name_link_type(payload, names, document)
            if payload.get("name"):
                evidence["title"] = payload["name"]
            if document:
                reason = evidence.get("reason") or ""
                for key in ("sourceTypeCode", "targetTypeCode"):
                    code = payload.get(key)
                    if code:
                        reason = reason.replace(code, names.type(code))
                evidence["reason"] = reason
        elif kind == "DOCUMENT_LINK":
            choices = len(p.get("options") or [])
            if evidence.get("holdRecommended"):
                choices = max(choices, 2)
            made = _document_name(payload, evidence, names, choices)
        if kind in ("LINK_RULE", "LINK_TYPE"):
            for key in ("reason", "holdReason"):
                if evidence.get(key):
                    evidence[key] = _table_names(evidence[key], names)
                    if kind == "LINK_RULE":
                        evidence[key] = _column_names(evidence[key], payload, names)
        if made:
            title, lead = made
            payload["name"] = title
            evidence["title"] = title
            # 연결 규칙은 이유를 그대로 둔다
            if kind == "DOCUMENT_LINK":
                evidence["reason"] = lead
    return proposals
