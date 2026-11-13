"""글 속 원천 키 찾기 (LLM 미사용). 원천 키 값 사전으로 글에서 똑같은 값만 찾는다(대소문자 무시).

키 모양을 정규식으로 짐작하지 않는다(정답 문장을 만든 생성기 패턴과 같아져 순환이 되므로).
"""

import re
from dataclasses import dataclass, field
from pathlib import Path

from app.models.extraction import EntityType
from app.sources.csv_reader import CsvSourceReader

# 추출 대상 개체 유형과 그 값을 담은 원천 컬럼 (온톨로지 객체 ↔ 추출 유형 대응)
KEY_COLUMNS = {
    "mes.equipment.code": EntityType.EQUIPMENT,
    "mes.work_order.wo_no": EntityType.WORK_ORDER,
    "mes.maintenance_order.mo_no": EntityType.MAINTENANCE,
    "erp.shipment.lot_no": EntityType.LOT,
}
CODE_LIKE = re.compile(r"(?=.*[A-Za-z])(?=.*\d)[A-Za-z0-9-]{4,}")


@dataclass
class Hit:
    type: EntityType
    start: int
    end: int
    text: str
    ref: str  # 예: mes.work_order:JB2610-0141
    # 같은 값이 원천 여러 행에 있으면 그 행들 (예: 같은 로트를 실은 출하 여러 건). 하나면 [ref]
    candidates: list[str] = field(default_factory=list)


Dictionary = dict[str, tuple[EntityType, str | list[str]]]


def load_dictionary(root: Path) -> Dictionary:
    """대문자 값 → (유형, 원천 표:키 목록). 같은 값이 여러 행에 있으면 행마다 모은다(덮어쓰지 않는다).

    같은 값이 다른 유형의 컬럼에도 있으면 KEY_COLUMNS에서 앞에 둔 유형을 쓴다.
    """
    out: dict[str, tuple[EntityType, list[str]]] = {}
    for ref, etype in KEY_COLUMNS.items():
        source, table, column = ref.split(".")
        folder = Path(root) / source
        if not (folder / f"{table}.csv").exists():
            continue
        reader = CsvSourceReader(source, folder)
        pk = reader.read_schema(table).primary_key
        if not pk:  # 기본키 없는 표·뷰는 가리킬 객체 키가 없다
            continue
        rows = reader.read_rows(table)
        if column not in rows.columns:  # 분석할 컬럼으로 고르지 않았다
            continue
        rows = rows.dropna(subset=[column])
        for value, key in zip(rows[column].astype(str), rows[pk[0]].astype(str)):
            # 값이 기본키가 아니면 그 값이 든 행의 기본키로 객체를 가리킨다 (예: 로트 → 출하 번호)
            if not CODE_LIKE.fullmatch(value):
                continue
            ref = f"{source}.{table}:{key}"
            found_type, refs = out.setdefault(value.upper(), (etype, []))
            if found_type == etype and ref not in refs:
                refs.append(ref)
    return out


def find(text: str, dictionary: Dictionary) -> list[Hit]:
    hits = []
    for token in re.finditer(r"[A-Za-z0-9][A-Za-z0-9-]*[A-Za-z0-9]", text):
        key = token.group(0).upper()
        if key in dictionary:
            etype, refs = dictionary[key]
            refs = [refs] if isinstance(refs, str) else list(refs)
            hits.append(
                Hit(etype, token.start(), token.end(), token.group(0), refs[0], refs)
            )
    return hits
