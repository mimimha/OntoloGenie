"""이미 확정된 모델(043)과 같은 후보 거르기와 재분석 변경 표시.

같은 코드의 객체 유형·관계 유형·ACTIVE 규칙이 속성·변환까지 같으면 빼고, 다르면 보내며 이유에 적는다.
재분석이면 evidence.changeType(ADDED·CHANGED)을 붙인다. 문서 연결은 비교하지 않는다.
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
    """(보낼 후보, 종류별로 뺀 수). 모델 요약이 없거나 비면 그대로 돌려준다."""
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


def _option_fingerprint(option: dict) -> str:
    return (option.get("evidence") or {}).get("fingerprint") or fingerprint_payload(
        option.get("payload") or {}
    )


def _promote_remaining_option(p: dict, by_fingerprint: dict) -> dict | None:
    """거절된 본문 대신 거절되지 않은 첫 대안을 본문으로. 남은 대안이 없으면 None."""
    # 지문에는 대안도 들어 있어 거절된 대표 제안과 같은 대안은 payload 지문으로 가린다
    main = fingerprint_payload(p.get("payload") or {})
    remaining = [
        o
        for o in p.get("options") or []
        if _option_fingerprint(o) != main
        and by_fingerprint.get(_option_fingerprint(o), {}).get("decision") != "REJECT"
    ]
    if not remaining:
        return None
    first = remaining[0]
    payload = {**first["payload"]}
    if p["payload"].get("name"):
        payload["name"] = p["payload"]["name"]
    evidence = {**p["evidence"], "fingerprint": _option_fingerprint(first)}
    out = {**p, "payload": payload, "evidence": evidence, "options": remaining}
    return _note(out, "이전에 거절한 대상은 빼고 남은 후보로 다시 제안합니다.")


def skip_past_decisions(
    proposals: list[dict], decisions: list[dict]
) -> tuple[list[dict], dict[str, int]]:
    """018 pastDecisions 참고.

    같은 내용(지문)이 승인·거절됐으면 빼고(문서 연결은 선택지 중 승인된 것이 있어도 뺀다), 거절된 선택지는 선택지
    목록에서 뺀다. 보류됐거나 같은 후보 ID가 거절·보류된 뒤 내용이 바뀌었으면 내되 이유에 이전 결정을 적는다.
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
        entry = {**d, "decision": _decision(d.get("decision", "")), "fp": fp}
        if fp:
            by_fingerprint[fp] = entry
        if d.get("candidateId"):
            by_candidate[d["candidateId"]] = entry
    kept = []
    for p in proposals:
        evidence = p.get("evidence") or {}
        same = by_fingerprint.get(evidence.get("fingerprint"))
        if same and same["decision"] == "REJECT":
            # 본문(1번 선택지)이 거절됐어도 거절되지 않은 다른 대안이 있으면 그 대안을 본문으로 올려 다시 묻는다
            p = _promote_remaining_option(p, by_fingerprint)
            if p is None:
                skipped["REJECT"] += 1
                continue
            evidence = p["evidence"]
            same = None
        if same and same["decision"] in skipped:
            skipped[same["decision"]] += 1
            continue
        approved_before = by_candidate.get(evidence.get("candidateId"), {}).get(
            "decision"
        )
        if p.get("itemKind") == "DOCUMENT_LINK" and (
            # 같은 언급을 이미 승인했으면 대안이 바뀌어도 다시 묻지 않는다
            approved_before == "APPROVE"
            or any(
                by_fingerprint.get(_option_fingerprint(option), {}).get("decision")
                == "APPROVE"
                for option in p.get("options") or []
            )
        ):
            skipped["APPROVE"] += 1
            continue
        options = p.get("options") or []
        rejected = {
            id(o)
            for o in options
            if by_fingerprint.get(_option_fingerprint(o), {}).get("decision")
            == "REJECT"
        }
        if rejected:
            p = {**p, "options": [o for o in options if id(o) not in rejected]}
            p = _note(p, f"이전에 거절한 대안 {len(rejected)}개는 뺐습니다.")
        if same and same["decision"] == "HOLD":
            p = _note(p, f"같은 내용이 이전에 보류됐습니다{_why(same)}.")
        else:
            before = by_candidate.get(evidence.get("candidateId"))
            # 거절이 대안(선택지)에 대한 것이었으면 본문은 바뀐 것이 아니다
            about_option = before is not None and any(
                _option_fingerprint(o) == before["fp"] for o in options
            )
            if before and before["decision"] in ("REJECT", "HOLD") and not about_option:
                word = "거절" if before["decision"] == "REJECT" else "보류"
                p = _note(
                    p,
                    f"이전에 {word}된 후보{_why(before)}인데 내용이 바뀌어 다시 제안합니다.",
                )
        kept.append(p)
    return kept, skipped
