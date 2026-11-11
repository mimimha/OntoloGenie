"""재분석 비교: 이번 후보를 이전 후보(후보 ID + 지문)와 맞춰 evidence.changeType을 붙인다.

같은 ID·지문이면 UNCHANGED, ID만 같으면 CHANGED, 없던 ID면 ADDED. 지문은 payload만으로 만들어 신뢰도·근거만 바뀌면 유지다.
이번에 안 나온 이전 후보는 고르지 않은 표의 것일 수 있어 판단하지 않는다.
"""


def mark_changes(proposals: list[dict], previous: list[dict]) -> dict:
    """105 형식 후보의 evidence에 changeType을 붙이고 개수를 돌려준다. previous: [{candidateId, fingerprint}]."""
    before = {p["candidateId"]: p.get("fingerprint") for p in previous}
    counts = {"ADDED": 0, "CHANGED": 0, "UNCHANGED": 0}
    for p in proposals:
        evidence = p["evidence"]
        if evidence.get("candidateId") not in before:
            # 직전 분석에서 이미 확정된 모델이라 걸러졌던 후보(새 속성이 붙은 객체 등)는 모델 비교의 CHANGED를 둔다
            kind = "CHANGED" if evidence.get("changeType") == "CHANGED" else "ADDED"
        elif before[evidence["candidateId"]] == evidence["fingerprint"]:
            kind = "UNCHANGED"
        else:
            kind = "CHANGED"
        evidence["changeType"] = kind
        counts[kind] += 1
    return counts
