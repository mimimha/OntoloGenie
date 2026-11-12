"""후보를 API 105 proposals[] 형식(camelCase)으로 바꾼다.

105에 칸이 없는 제안 이유·근거·신뢰도 내역·보류 권고는 evidence에 넣고, 후보는 표·관계 단위로 묶는다.
"""

import hashlib
import json
import re
from collections import Counter

from app.models.candidate import Candidate
from app.proposers.readable import readable


def camel(name: str) -> str:
    head, *rest = name.split("_")
    return head + "".join(part[:1].upper() + part[1:] for part in rest)


# 키가 아니라 데이터인 칸. MAP_VALUE의 arg는 원천 값 → 바꿀 값 대응표라 원천 값(in_progress)을 그대로 둔다
DATA_KEYS = {"arg"}


def camel_keys(value):
    if isinstance(value, dict):
        return {
            camel(k): v if k in DATA_KEYS else camel_keys(v) for k, v in value.items()
        }
    if isinstance(value, list):
        return [camel_keys(v) for v in value]
    return value


# 화면에 보이는 이름·설명. LLM이 실행마다 다르게 써서 지문에서 뺀다(같은 원천이면 '유지')
WORDING_KEYS = {"name", "description", "synonyms"}


def _without_wording(value):
    if isinstance(value, dict):
        return {
            k: v if k in DATA_KEYS else _without_wording(v)
            for k, v in value.items()
            if k not in WORDING_KEYS
        }
    if isinstance(value, list):
        return [_without_wording(v) for v in value]
    return value


def fingerprint_payload(api_payload: dict) -> str:
    """연동 형식(camelCase) payload의 지문. 키 순서와 이름·설명 문구와 무관하다. 재분석 비교·과거 결정 대조에 쓴다."""
    text = json.dumps(_without_wording(api_payload), ensure_ascii=False, sort_keys=True)
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]


def api_payload(data: dict) -> dict:
    """후보 payload(model_dump) → 연동 형식. 지문도 이 결과로 계산한다."""
    return camel_keys({k: v for k, v in data.items() if k != "kind"})


def to_api(candidate: Candidate) -> dict:
    data = candidate.model_dump(mode="json")
    payload = api_payload(data["payload"])
    confidence = candidate.confidence
    evidence = {
        "candidateId": candidate.candidate_id,  # 재분석 비교·과거 결정 대조용
        "fingerprint": fingerprint_payload(payload),
        "title": candidate.title,
        "reason": candidate.reason,
        "items": camel_keys(data["evidence"]),
        "confidenceItems": camel_keys(data["confidence"]["items"])
        if confidence
        else [],
        "confidenceCap": camel_keys(
            {"cap": confidence.cap, "reason": confidence.cap_reason}
        )
        if confidence and confidence.cap is not None
        else None,
        "method": candidate.method,
        # 105는 검토 상태를 PENDING으로 저장한다. AI의 보류 의견은 권고로만 남긴다
        "holdRecommended": candidate.review_status == "HELD",
        "holdReason": candidate.hold_reason,
        "llmJudgement": candidate.llm_judgement,
    }
    if candidate.document is not None:
        evidence["originalText"] = candidate.document.original_text
        evidence["observations"] = camel_keys(data["document"]["observations"])
        evidence["times"] = camel_keys(data["document"]["times"])
    options = [
        {
            "optionNo": o["option_no"],
            "payload": api_payload(o["payload"]),
            # 선택지에도 지문을 둔다. 백엔드가 고른 선택지의 결정(거절 등)을 이 지문으로 다음 분석에 돌려준다
            "evidence": {
                "fingerprint": fingerprint_payload(api_payload(o["payload"])),
                "items": camel_keys(o["evidence"]),
            },
            "aiConfidence": opt.confidence.total if opt.confidence else None,
        }
        for o, opt in zip(data["options"], candidate.options, strict=True)
    ]
    if len(options) > 1:
        # 대안이 있으면 지문에 대안도 넣는다. 대표 제안이 그대로여도 대안이 바뀌면 재분석에서 변경으로 본다
        evidence["fingerprint"] = fingerprint_payload(
            {
                "payload": payload,
                "options": sorted(o["evidence"]["fingerprint"] for o in options),
            }
        )
    return {
        "itemKind": candidate.item_kind.value,
        "payload": payload,
        "evidence": evidence,
        "aiConfidence": candidate.ai_confidence,
        "options": options,
    }


def _held(*candidates: Candidate) -> tuple[bool, str | None]:
    """묶음 안에 보류 권고가 하나라도 있으면 묶음도 보류 권고. 이유는 겹치지 않게 잇는다."""
    held = [c for c in candidates if c.review_status == "HELD"]
    if not held:
        return False, None
    reasons = dict.fromkeys(c.hold_reason for c in held if c.hold_reason)
    return True, " / ".join(reasons) or None


def _part_evidence(api: dict) -> dict:
    """묶음 안 구성 후보의 근거. 묶음 단위로 다시 정하는 지문·후보 ID만 빼고 보류 권고·이유·상한은 남긴다."""
    keep = (
        "title",
        "reason",
        "items",
        "confidenceItems",
        "confidenceCap",
        "method",
        "llmJudgement",
        "holdRecommended",
        "holdReason",
    )
    return {k: api["evidence"][k] for k in keep}


def _without(payload: dict, key: str) -> dict:
    return {k: v for k, v in payload.items() if k != key}


def _object_bundle(
    head: Candidate,
    mappings: list[Candidate],
    properties: list[Candidate],
    property_mappings: dict[str, Candidate],
) -> dict:
    """객체 유형 하나에 속성·원천 매핑을 담은 OBJECT_TYPE 후보 (표 하나 = 검토 한 건)."""
    out = to_api(head)
    payload, evidence = out["payload"], out["evidence"]
    if mappings:
        mapping = to_api(mappings[0])
        payload["objectMapping"] = _without(mapping["payload"], "objectTypeCode")
        evidence["objectMapping"] = _part_evidence(mapping)
    payload["properties"], evidence["properties"] = [], []
    for prop in properties:
        api = to_api(prop)
        item = _without(api["payload"], "objectTypeCode")
        part = {"code": item["code"], **_part_evidence(api)}
        source = property_mappings.get(prop.payload.code)
        if source is not None:
            mapped = to_api(source)
            item["sourceColumn"] = mapped["payload"]["sourceColumn"]
            item["transformSteps"] = mapped["payload"]["transformSteps"]
            part["mapping"] = _part_evidence(mapped)
        payload["properties"].append(item)
        evidence["properties"].append(part)
    held, reason = _held(head, *mappings, *properties, *property_mappings.values())
    evidence["holdRecommended"], evidence["holdReason"] = held, reason
    evidence["fingerprint"] = fingerprint_payload(payload)
    return out


def _link_bundle(rule: Candidate, link_type: Candidate) -> dict:
    """관계 유형과 그 연결 규칙을 담은 LINK_RULE 후보 (관계 하나 = 검토 한 건)."""
    out = to_api(rule)
    out["payload"]["linkType"] = to_api(link_type)["payload"]
    held, reason = _held(rule, link_type)
    out["evidence"]["holdRecommended"], out["evidence"]["holdReason"] = held, reason
    out["evidence"]["fingerprint"] = fingerprint_payload(out["payload"])
    return out


def to_api_proposals(candidates: list[Candidate]) -> list[dict]:
    """후보 목록 → 105 proposals[]. 표 하나는 OBJECT_TYPE, 관계 하나는 LINK_RULE 한 건으로 묶는다.

    규칙 없는 관계 유형·문서 연결과 묶을 짝이 없는 구성 후보는 원래 종류 그대로 따로 보낸다.
    """
    groups: dict[str, list[Candidate]] = {}
    for c in candidates:
        groups.setdefault(c.item_kind.value, []).append(c)
    loose: list[Candidate] = []
    # 코드가 겹치는 객체 유형은 어느 쪽에 묶을지 모호하므로 묶지 않고 따로 보낸다(잃지 않는다).
    codes = Counter(c.payload.code for c in groups.get("OBJECT_TYPE", []))
    heads: dict[str, Candidate] = {}
    for c in groups.get("OBJECT_TYPE", []):
        if codes[c.payload.code] == 1:
            heads[c.payload.code] = c
        else:
            loose.append(c)
    mappings: dict[str, list[Candidate]] = {}
    properties: dict[str, dict[str, Candidate]] = {}
    property_mappings: dict[str, dict[str, Candidate]] = {}
    for c in groups.get("OBJECT_MAPPING", []):
        owner = c.payload.object_type_code
        # 객체 매핑은 묶음에 하나만 담는다. 둘째부터는 따로 보낸다.
        if owner in heads and owner not in mappings:
            mappings[owner] = [c]
        else:
            loose.append(c)
    for c in groups.get("PROPERTY", []):
        owner, code = c.payload.object_type_code, c.payload.code
        if owner in heads and code not in properties.get(owner, {}):
            properties.setdefault(owner, {})[code] = c
        else:
            loose.append(c)
    for c in groups.get("PROPERTY_MAPPING", []):
        owner, code = c.payload.object_type_code, c.payload.property_code
        if code in properties.get(owner, {}) and code not in property_mappings.get(
            owner, {}
        ):
            property_mappings.setdefault(owner, {})[code] = c
        else:
            loose.append(c)
    out = [
        _object_bundle(
            head,
            mappings.get(code, []),
            list(properties.get(code, {}).values()),
            property_mappings.get(code, {}),
        )
        for code, head in heads.items()
    ]
    types: dict[str, Candidate] = {}
    for c in groups.get("LINK_TYPE", []):
        if c.payload.code in types:
            loose.append(c)
        else:
            types[c.payload.code] = c
    paired: set[str] = set()
    for rule in groups.get("LINK_RULE", []):
        code = rule.payload.link_type_code
        if code in types:
            # 같은 관계 유형을 쓰는 규칙이 여럿이어도 규칙마다 관계 유형을 담는다
            paired.add(code)
            out.append(_link_bundle(rule, types[code]))
        else:
            out.append(to_api(rule))
    out += [to_api(t) for code, t in types.items() if code not in paired]
    out += [to_api(c) for c in loose + groups.get("DOCUMENT_LINK", [])]
    # 검토 화면 목록 제목·관계 설명을 한국어로 붙인다(코드 대신 객체·속성 이름)
    return readable(out)


SNAKE = re.compile(r"_[a-z]")


def has_snake_keys(value) -> bool:
    """연동 JSON에 snake_case 키가 남았는지 (테스트용)."""
    if isinstance(value, dict):
        return any(SNAKE.search(k) for k in value) or any(
            has_snake_keys(v) for k, v in value.items() if k not in DATA_KEYS
        )
    if isinstance(value, list):
        return any(has_snake_keys(v) for v in value)
    return False
