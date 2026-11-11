"""글 속 원천 키 찾기 (LLM 미사용). 원천 키 값 사전으로 글에서 똑같은 값만 찾는다(대소문자 무시).

키 모양을 정규식으로 짐작하지 않는다(정답 문장을 만든 생성기 패턴과 같아져 순환이 되므로).
"""

import re
from dataclasses import dataclass
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


def load_dictionary(root: Path) -> dict[str, tuple[EntityType, str]]:
    """대문자 값 → (유형, 원천 표:원래 값)."""
    out = {}
    for ref, etype in KEY_COLUMNS.items():
        source, table, column = ref.split(".")
        folder = Path(root) / source
        if not (folder / f"{table}.csv").exists():
            continue
        reader = CsvSourceReader(source, folder)
        pk = reader.read_schema(table).primary_key
        rows = reader.read_rows(table)
        if column not in rows.columns:  # 분석할 컬럼으로 고르지 않았다
            continue
        rows = rows.dropna(subset=[column])
        for value, key in zip(rows[column].astype(str), rows[pk[0]].astype(str)):
            # 값이 기본키가 아니면 그 값이 든 행의 기본키로 객체를 가리킨다 (예: 로트 → 출하 번호)
            if CODE_LIKE.fullmatch(value):
                out[value.upper()] = (etype, f"{source}.{table}:{key}")
    return out


def find(text: str, dictionary: dict[str, tuple[EntityType, str]]) -> list[Hit]:
    hits = []
    for token in re.finditer(r"[A-Za-z0-9][A-Za-z0-9-]*[A-Za-z0-9]", text):
        key = token.group(0).upper()
        if key in dictionary:
            etype, ref = dictionary[key]
            hits.append(Hit(etype, token.start(), token.end(), token.group(0), ref))
    return hits
