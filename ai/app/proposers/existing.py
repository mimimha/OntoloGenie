"""FN-AIP-04 이미 확정된 모델과 같은 후보 거르기 (S15P31C106-281).

분석을 시작할 때 백엔드의 모델 요약(API 043, 객체 유형·속성·관계 유형·ACTIVE 규칙)을 받아,
검토 단위(105 proposals[])마다 이미 있는 것과 같으면 보내지 않는다. 다시 분석할 때마다 같은
후보가 검토 목록에 쌓이지 않게 하려는 것이다.

- 객체 유형 묶음: 같은 코드의 객체 유형이 있고 묶음의 속성이 모두 이미 있으면 뺀다. 새 속성이 있으면
  (예: CDC로 추가된 납기일 컬럼) 그대로 보내고 제안 이유에 이미 있는 유형과 새 속성을 적는다.
- 연결 규칙 묶음: 같은 코드의 ACTIVE 규칙이 같은 속성끼리 같은 변환(출발·도착 변환 단계, 비교 연산)으로 비교하면 뺀다.
  속성이나 변환이 다르면(예: 0 채우기 자릿수가 바뀜) 보내고 이유에 적는다. 관계 유형만 같아도 보내고 이유에 적는다.
- 따로 보내는 관계 유형: 같은 코드가 있으면 뺀다.
- 문서 연결 등 나머지는 그대로 보낸다.

과거 승인·거절 결정(같은 내용이 거절된 후보 거르기)은 백엔드가 주는 API가 아직 없어 하지 않는다.

재분석 비교(S15P31C106-170, FN-AIP-06): 재분석(REANALYSIS)이면 같은 비교 결과로 evidence.changeType을 붙인다.
백엔드가 이전 후보를 주는 API가 없어 비교 기준은 '이전 후보'가 아니라 '이미 확정된 모델(043)'이다.
- ADDED: 모델에 없는 객체 유형·관계 유형·규칙 코드(이미 있는 관계 유형에 새 규칙도 ADDED)
- CHANGED: 같은 코드가 있는데 새 속성이 생겼거나 규칙의 속성·변환이 다름
- UNCHANGED: 모델과 같아서 보내지 않음(개수만 결과 요약에)
- 문서 연결(DOCUMENT_LINK)은 모델 정의가 아니라 비교하지 않는다.
이전 후보를 받게 되면 app/proposers/reanalysis.py 방식(후보 ID·지문 비교)으로 바꾼다.
"""

import json
from dataclasses import dataclass, field

from app.proposers.api_format import fingerprint_payload


@dataclass
class Existing:
    # 객체 유형 코드 → 속성 코드들
    types: dict[str, set[str]] = field(default_factory=dict)
    link_types: set[str] = field(default_factory=set)
    # 규칙 코드 → 조건들 (출발 속성, 도착 속성, 연산, 출발 변환, 도착 변환). 속성은 '객체유형코드.속성코드'
    rules: dict[str, frozenset] = field(default_factory=dict)


def _steps(steps) -> tuple:
    """변환 단계 목록을 비교할 수 있는 값으로. 값 순서와 무관하게 op·arg만 본다."""
    return tuple(
        (s.get("op"), json.dumps(s.get("arg"), ensure_ascii=False, sort_keys=True))
        for s in steps or []
        if isinstance(s, dict)
    )


def _condition(source, target, condition: dict) -> tuple:
    return (
        source,
        target,
        condition.get("operator") or "EQ",
        _steps(condition.get("sourceTransforms")),
        _steps(condition.get("targetTransforms")),
    )


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
            _condition(
                prop_refs.get(c.get("sourcePropertyId")),
                prop_refs.get(c.get("targetPropertyId")),
                c,
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


def _mark(proposal: dict, change: str) -> dict:
    return {**proposal, "evidence": {**proposal["evidence"], "changeType": change}}


COMPARED = ("OBJECT_TYPE", "LINK_RULE", "LINK_TYPE")


def change_counts(proposals: list[dict], skipped: dict[str, int]) -> dict[str, int]:
    """재분석 결과 요약용 ADDED·CHANGED·UNCHANGED 수. UNCHANGED는 보내지 않은 수다."""
    counts = {"ADDED": 0, "CHANGED": 0, "UNCHANGED": sum(skipped.values())}
    for p in proposals:
        change = (p.get("evidence") or {}).get("changeType")
        if change in counts:
            counts[change] += 1
    return counts


def filter_existing(
    proposals: list[dict], summary: dict | None, mark_changes: bool = False
) -> tuple[list[dict], dict[str, int]]:
    """(보낼 후보, 종류별로 뺀 수). 모델 요약이 없거나 비면 그대로 돌려준다.

    mark_changes(재분석)이면 비교한 후보에 evidence.changeType(ADDED·CHANGED)을 붙인다."""
    skipped = {"objectTypes": 0, "linkRules": 0, "linkTypes": 0}
    if not summary:
        return proposals, skipped
    index = existing_index(summary)
    kept = []
    for p in proposals:
        kind, payload = p.get("itemKind"), p.get("payload") or {}
        code = payload.get("code")
        change = "ADDED"
        if kind == "OBJECT_TYPE" and code in index.types:
            props = {q.get("code") for q in payload.get("properties") or []}
            new = sorted(props - index.types[code])
            if not new:
                skipped["objectTypes"] += 1
                continue
            change = "CHANGED"
            p = _note(
                p,
                f"이미 등록된 객체 유형({code})입니다. 새로 제안하는 속성: {', '.join(new)}.",
            )
        elif kind == "LINK_RULE":
            pairs = frozenset(
                _condition(c.get("sourceProperty"), c.get("targetProperty"), c)
                for c in payload.get("conditions") or []
            )
            if index.rules.get(code) == pairs:
                skipped["linkRules"] += 1
                continue
            link_code = (payload.get("linkType") or {}).get("code")
            if code in index.rules:
                change = "CHANGED"
                p = _note(
                    p,
                    f"같은 코드의 쓰고 있는 규칙({code})과 비교하는 속성이나 변환이 다릅니다.",
                )
            elif link_code in index.link_types:
                p = _note(
                    p, f"이미 등록된 관계 유형({link_code})에 규칙을 새로 제안합니다."
                )
        elif kind == "LINK_TYPE" and code in index.link_types:
            skipped["linkTypes"] += 1
            continue
        if mark_changes and kind in COMPARED:
            p = _mark(p, change)
        kept.append(p)
    return kept, skipped


def _decision(value: str) -> str:
    """APPROVE·REJECT·HOLD로 맞춘다(APPROVED·REJECTED·HELD도 받는다)."""
    head = (value or "").upper()
    return {"APPROVED": "APPROVE", "REJECTED": "REJECT", "HELD": "HOLD"}.get(head, head)


def _why(decision: dict) -> str:
    return f"(사유: {decision['reason']})" if decision.get("reason") else ""


def skip_past_decisions(
    proposals: list[dict], decisions: list[dict]
) -> tuple[list[dict], dict[str, int]]:
    """과거 승인·거절 결과 참고 (FN-AIP-04, S15P31C106-281).

    decisions: 018 pastDecisions `[{candidateId, itemKind, revision, fingerprint 또는 payload, decision, reason}]`.
    - 같은 내용(지문)이 승인·거절됐으면 다시 내지 않는다.
    - 같은 내용이 보류됐으면 내되 이유에 이전 보류를 적는다.
    - 같은 후보 ID가 거절·보류됐는데 내용이 바뀌었으면 내되 이유에 이전 결정을 적는다.
    """
    skipped = {"APPROVE": 0, "REJECT": 0}
    if not decisions:
        return proposals, skipped
    by_fingerprint: dict[str, dict] = {}
    by_candidate: dict[str, dict] = {}
    for d in decisions:
        fp = d.get("fingerprint") or (
            fingerprint_payload(d["payload"]) if d.get("payload") else None
        )
        entry = {**d, "decision": _decision(d.get("decision", ""))}
        if fp:
            by_fingerprint[fp] = entry
        if d.get("candidateId"):
            by_candidate[d["candidateId"]] = entry
    kept = []
    for p in proposals:
        evidence = p.get("evidence") or {}
        same = by_fingerprint.get(evidence.get("fingerprint"))
        if same and same["decision"] in skipped:
            skipped[same["decision"]] += 1
            continue
        if same and same["decision"] == "HOLD":
            p = _note(p, f"같은 내용이 이전에 보류됐습니다{_why(same)}.")
        else:
            before = by_candidate.get(evidence.get("candidateId"))
            if before and before["decision"] in ("REJECT", "HOLD"):
                word = "거절" if before["decision"] == "REJECT" else "보류"
                p = _note(
                    p,
                    f"이전에 {word}된 후보{_why(before)}인데 내용이 바뀌어 다시 제안합니다.",
                )
        kept.append(p)
    return kept, skipped
