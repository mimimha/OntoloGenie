"""FN-AIP-06 변경 데이터 재분석 (Jira 170).

- 범위: 원천 변경은 CDC로 수집하고 백엔드가 재분석할 표를 고른다(V5). 고른 표는 전체를 다시 분석한다.
- 비교: 이번 후보를 Spring이 보낸 이전 후보(후보 ID + 지문)와 맞춰 본다.
  같은 ID·같은 지문이면 UNCHANGED(유지), ID만 같으면 CHANGED(변경), 없던 ID면 ADDED(추가). 결과는 evidence.changeType.
  지문은 payload(규칙 내용)만으로 만들어 신뢰도·근거 수치만 달라진 것은 유지로 본다.
- 이전에 있었는데 이번에 안 나온 후보는 판단하지 않는다(MVP). 이전 후보에는 이번에 고르지 않은 표의 것도 섞여 있다.
"""


def mark_changes(proposals: list[dict], previous: list[dict]) -> dict:
    """105 형식 후보의 evidence에 changeType을 붙이고 개수를 돌려준다.

    previous: [{candidateId, fingerprint}]. 백엔드가 저장해 둔 이전 후보의 evidence에서 꺼내 보낸다.
    """
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
