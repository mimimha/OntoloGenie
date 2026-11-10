"""검토 화면용 이름·설명 (S15P31C106-327, FN-AIP-05).

화면 목록은 제안 제목으로 payload.name(없으면 code)을 쓴다. 연결 규칙·관계 유형·문서 연결은 코드
(`WORK_ORDER_SO_REF_TO_SALES_ORDER`)나 '문서 연결 제안 #126'으로만 보여 관리자가 하나씩 열어 봐야 했다.
같은 분석의 객체 유형 이름(작업지시)과 속성 이름(수주 참조 번호)으로 한국어 제목·설명을 붙인다.
분석 범위 밖이라 이름을 모르는 객체는 코드를 그대로 쓴다.
"""

import re


def _final_consonant(word: str) -> int | None:
    """마지막 글자의 받침 번호(0은 받침 없음). 한글이 아니면 숫자·영문 읽기로 어림한다."""
    if not word:
        return 0
    ch = word[-1]
    if "가" <= ch <= "힣":
        return (ord(ch) - ord("가")) % 28
    if ch.isdigit():
        # 일·칠·팔은 ㄹ 받침(8), 영·삼·육은 다른 받침(1)
        return 8 if ch in "178" else 1 if ch in "036" else 0
    if not ch.isascii() or not ch.isalpha():
        return 0
    tail = word.split("_")[-1].lower()
    if len(tail) == 1 or not tail.isalpha():
        # 글자 하나는 알파벳 이름으로 읽는다(엘·알 → ㄹ, 엠·엔 → 받침)
        return 8 if ch.lower() in "lr" else 1 if ch.lower() in "mn" else 0
    # 영어 낱말은 소리로 어림한다: name·time(ㅁ), line(ㄴ), level(ㄹ), group(ㅂ), string(ㅇ)
    if tail.endswith("ng"):
        return 1
    if tail.endswith("e") and len(tail) > 2:
        prev = tail[-2]
        return 8 if prev == "l" else 1 if prev in "mn" else 0
    last = tail[-1]
    if last == "l":
        return 8
    return 1 if last in "mnpb" else 0  # number·order(-r)는 받침 없이 읽는다


def josa(word: str, pair: str) -> str:
    """word 뒤에 맞는 조사를 붙인다. pair: '이/가', '을/를', '은/는', '으로/로'."""
    with_batchim, without = pair.split("/")
    final = _final_consonant(word)
    if pair == "으로/로":
        return word + (without if final in (0, 8) else with_batchim)  # ㄹ 받침은 '로'
    return word + (with_batchim if final else without)


def _short(name: str) -> str:
    short = " ".join(re.sub(r"\s*\([^)]*\)", "", name).split())
    return short or name


class Names:
    def __init__(self, proposals: list[dict]):
        self.types: dict[str, str] = {}  # 객체 유형 코드 → 이름
        self.tables: dict[str, str] = {}  # 원천 표(resourceKey) → 객체 유형 이름
        self.props: dict[str, str] = {}  # '유형코드.속성코드' → 속성 이름
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

    def type(self, code: str | None) -> str:
        """제목·설명용 이름. 표 주석의 괄호 설명('품목 마스터(원자재, 포장재)')은 빼서 짧게 쓴다."""
        return _short(self.types.get(code or "", code or ""))

    def table(self, resource_key: str) -> str:
        return _short(self.tables.get(resource_key, resource_key.rsplit(".", 1)[-1]))

    def prop(self, ref: str | None) -> str:
        return self.props.get(ref or "", (ref or "").rsplit(".", 1)[-1])


def _relation_sentence(source: str, target: str) -> str:
    return f"{josa(source, '이/가')} {josa(target, '을/를')} 가리킵니다."


def _rest_of(description: str | None) -> str:
    """'X가 Y를 가리킨다 (...). 개수 관계는 …' 에서 첫 문장 뒤의 설명(괄호·개수 관계)만 남긴다."""
    text = description or ""
    _, sep, tail = text.partition("가리킨다")
    if not sep:
        return ""
    return tail.lstrip(".").strip()


def _name_link_type(link_type: dict, names: Names) -> None:
    source = names.type(link_type.get("sourceTypeCode"))
    target = names.type(link_type.get("targetTypeCode"))
    if not source or not target:
        return
    rest = _rest_of(link_type.get("description"))
    if "글이" in (link_type.get("description") or ""):
        link_type["name"] = f"{source} → {target} (글에서 언급)"
        link_type["description"] = (
            f"{source}의 글이 {josa(target, '을/를')} 언급합니다. "
            "원천 키가 아니라 글에서 찾은 연결입니다."
        )
        return
    link_type["name"] = f"{source} → {target}"
    sentence = _relation_sentence(source, target)
    if rest.startswith("(") and ")" in rest:
        # '(wo_no로 mes.work_order를 찾아 …)'는 문장 안에 넣는다
        paren, _, after = rest.partition(")")
        sentence = f"{sentence[:-1]} {paren})."
        rest = after.lstrip(".").strip()
    link_type["description"] = f"{sentence} {rest}".strip()


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


def _particle(word: str, pair: str) -> str:
    """word 뒤에 붙을 조사만. 따옴표로 감싼 말 뒤에 쓴다."""
    return josa(word, pair)[len(word) :]


def _excerpt(evidence: dict, payload: dict, limit: int = 60) -> str:
    """언급이 든 문장. 검토자가 메모를 열지 않고도 어떤 문맥인지 보게 한다.
    줄·마침표에서 끊고(머리말 '[8/4 주간 → 야간 인수인계] 작성 52' 줄은 빠짐), 길면 언급 앞뒤만 남긴다."""
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
    # 긴 문장은 언급 앞뒤를 띄어쓰기에서 자른다
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
    where = f"{doc} {payload.get('documentId', '')}".strip()
    excerpt = _excerpt(evidence, payload)
    quote = f": “{excerpt}”" if excerpt else "."
    quoted = f"'{mention}'"
    if choices > 1:
        # 보류 권고(후보 설비가 여럿): 첫 후보로 단정하지 않는다
        title = f"{where}의 {target} (후보 {choices}개)"
        lead = f"{quoted}에 맞는 {josa(target, '이/가')} {choices}개입니다{quote}"
        return title, lead
    title = f"{where}의 {target} {key}"
    alias = any(
        "이름·별명" in (c.get("label") or "")
        for c in evidence.get("confidenceItems") or []
    )
    if mention.strip().upper() == key.strip().upper():
        lead = f"글에 {quoted}{_particle(mention, '이/가')} 적혀 있습니다"
    elif alias:
        lead = f"글의 {quoted}{_particle(mention, '을/를')} {target} {key}의 이름·별명으로 봤습니다"
    else:
        # 로트 번호처럼 다른 컬럼 값으로 찾은 경우
        lead = f"글에 적힌 {quoted}{_particle(mention, '으로/로')} {target} {josa(key, '을/를')} 찾았습니다"
    return title, lead + quote


def readable(proposals: list[dict]) -> list[dict]:
    """105 proposals[]에 한국어 제목(payload.name)·설명을 붙인다.

    지문(evidence.fingerprint)은 다시 계산하지 않는다. 이름은 LLM 판단·분석 범위에 따라 바뀌므로
    재분석 비교가 이름만 바뀐 같은 규칙을 '변경'으로 보지 않게 한다."""
    names = Names(proposals)
    for p in proposals:
        kind, payload, evidence = (
            p.get("itemKind"),
            p.get("payload") or {},
            p.get("evidence") or {},
        )
        made, held_doc = None, False
        if kind == "LINK_RULE":
            if payload.get("linkType"):
                _name_link_type(payload["linkType"], names)
            made = _link_rule_name(payload, names)
        elif kind == "LINK_TYPE":
            _name_link_type(payload, names)
            if payload.get("name"):
                evidence["title"] = payload["name"]
            # '글 11건에서 WORK_ORDER 언급 13개를 찾았다.'의 유형 코드를 이름으로
            target = payload.get("targetTypeCode")
            if target and target in evidence.get("reason", ""):
                evidence["reason"] = (
                    evidence["reason"]
                    .replace(target, names.type(target))
                    .replace("찾았다.", "찾았습니다.")
                )
        elif kind == "DOCUMENT_LINK":
            choices = len(p.get("options") or [])
            if evidence.get("holdRecommended"):
                choices = max(choices, 2)
            made = _document_name(payload, evidence, names, choices)
            held_doc = choices > 1
        if made:
            title, lead = made
            payload["name"] = title
            evidence["title"] = title
            # 확정 문서 연결은 예전 문장(코드·조사 오류)을 새 문장으로 바꾸고,
            # 연결 규칙과 보류 문서 연결은 쉬운 요약을 앞에 붙인다(정하지 못한 이유·단서를 남긴다)
            evidence["reason"] = (
                lead
                if kind == "DOCUMENT_LINK" and not held_doc
                else f"{lead} {evidence.get('reason') or ''}".strip()
            )
    return proposals
