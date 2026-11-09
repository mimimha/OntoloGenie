"""FN-AIP-04 이미 확정된 모델과 같은 후보 거르기 (S15P31C106-281).

분석을 시작할 때 백엔드의 모델 요약(API 043, 객체 유형·속성·관계 유형·ACTIVE 규칙)을 받아,
검토 단위(105 proposals[])마다 이미 있는 것과 같으면 보내지 않는다. 다시 분석할 때마다 같은
후보가 검토 목록에 쌓이지 않게 하려는 것이다.

- 객체 유형 묶음: 같은 코드의 객체 유형이 있고 묶음의 속성이 모두 이미 있으면 뺀다. 새 속성이 있으면
  (예: CDC로 추가된 납기일 컬럼) 그대로 보내고 제안 이유에 이미 있는 유형과 새 속성을 적는다.
- 연결 규칙 묶음: 같은 코드의 ACTIVE 규칙이 같은 속성끼리 비교하면 뺀다. 관계 유형만 같으면 보내고 이유에 적는다.
- 따로 보내는 관계 유형: 같은 코드가 있으면 뺀다.
- 문서 연결 등 나머지는 그대로 보낸다.

과거 승인·거절 결정(같은 내용이 거절된 후보 거르기)은 백엔드가 주는 API가 아직 없어 하지 않는다.
"""

from dataclasses import dataclass, field


@dataclass
class Existing:
    # 객체 유형 코드 → 속성 코드들
    types: dict[str, set[str]] = field(default_factory=dict)
    link_types: set[str] = field(default_factory=set)
    # 규칙 코드 → 비교하는 (출발 속성, 도착 속성) 쌍들. 속성은 '객체유형코드.속성코드'
    rules: dict[str, frozenset] = field(default_factory=dict)


def existing_index(summary: dict) -> Existing:
    type_codes = {t.get("id"): t.get("code") for t in summary.get("objectTypes") or []}
    index = Existing(types={code: set() for code in type_codes.values() if code})
    prop_refs = {}
    for p in summary.get("properties") or []:
        owner = type_codes.get(p.get("objectTypeId"))
        if owner and p.get("code"):
            index.types.setdefault(owner, set()).add(p["code"])
            prop_refs[p.get("id")] = f"{owner}.{p['code']}"
    index.link_types = {
        link.get("code") for link in summary.get("linkTypes") or [] if link.get("code")
    }
    for rule in summary.get("activeRules") or []:
        pairs = frozenset(
            (
                prop_refs.get(c.get("sourcePropertyId")),
                prop_refs.get(c.get("targetPropertyId")),
            )
            for c in rule.get("conditions") or []
        )
        if rule.get("code"):
            index.rules[rule["code"]] = pairs
    return index


def _note(proposal: dict, text: str) -> dict:
    evidence = dict(proposal["evidence"])
    evidence["reason"] = f"{evidence.get('reason', '')} {text}".strip()
    return {**proposal, "evidence": evidence}


def filter_existing(
    proposals: list[dict], summary: dict | None
) -> tuple[list[dict], dict[str, int]]:
    """(보낼 후보, 종류별로 뺀 수). 모델 요약이 없거나 비면 그대로 돌려준다."""
    skipped = {"objectTypes": 0, "linkRules": 0, "linkTypes": 0}
    if not summary:
        return proposals, skipped
    index = existing_index(summary)
    kept = []
    for p in proposals:
        kind, payload = p.get("itemKind"), p.get("payload") or {}
        code = payload.get("code")
        if kind == "OBJECT_TYPE" and code in index.types:
            props = {q.get("code") for q in payload.get("properties") or []}
            new = sorted(props - index.types[code])
            if not new:
                skipped["objectTypes"] += 1
                continue
            p = _note(
                p,
                f"이미 등록된 객체 유형({code})입니다. 새로 제안하는 속성: {', '.join(new)}.",
            )
        elif kind == "LINK_RULE":
            pairs = frozenset(
                (c.get("sourceProperty"), c.get("targetProperty"))
                for c in payload.get("conditions") or []
            )
            if index.rules.get(code) == pairs:
                skipped["linkRules"] += 1
                continue
            link_code = (payload.get("linkType") or {}).get("code")
            if code in index.rules:
                p = _note(
                    p, f"같은 코드의 쓰고 있는 규칙({code})과 비교하는 속성이 다릅니다."
                )
            elif link_code in index.link_types:
                p = _note(
                    p, f"이미 등록된 관계 유형({link_code})에 규칙을 새로 제안합니다."
                )
        elif kind == "LINK_TYPE" and code in index.link_types:
            skipped["linkTypes"] += 1
            continue
        kept.append(p)
    return kept, skipped
