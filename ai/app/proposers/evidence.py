"""근거 재확인: 원천을 다시 읽어 근거의 위치와 values가 실제로 있는지 본다.

확인된 근거가 없는 후보는 내보내지 않고, 일부만 확인되면 보류 권고(HELD)한다.
"""

from pathlib import Path

import pandas as pd

from app.models.candidate import Candidate, Evidence
from app.proposers import confidence as score
from app.sources.csv_reader import CsvSourceReader, UnknownTableError

PARTLY_MISSING = "근거 일부를 원천에서 다시 찾지 못함"


class SourceLookup:
    def __init__(self, root: Path, frames: dict[str, pd.DataFrame] | None = None):
        self.root = Path(root)
        self._columns: dict[str, set[str] | None] = {}
        self._values: dict[str, set[str]] = {}
        # 같은 실행에서 이미 읽은 원천 표(분석 결과). 없으면 CSV를 읽는다
        self._frames = dict(frames or {})

    def _reader(self, source: str) -> CsvSourceReader | None:
        folder = self.root / source
        return CsvSourceReader(source, folder) if folder.is_dir() else None

    def columns(self, source: str, table: str) -> set[str] | None:
        key = f"{source}.{table}"
        if key not in self._columns:
            reader = self._reader(source)
            try:
                schema = reader.read_schema(table) if reader else None
            except UnknownTableError:
                schema = None
            self._columns[key] = {c.name for c in schema.columns} if schema else None
        return self._columns[key]

    def values(self, source: str, table: str, column: str) -> set[str]:
        key = (source, table, column)
        if key not in self._values:
            # 분석 때 읽어 둔 표가 있으면 그대로 쓰고, 없으면 표 전체를 읽지 않고 그 컬럼만 읽는다
            df = self._frames.get(f"{source}.{table}")
            if df is None:
                df = self._reader(source).read_rows(table, columns=[column])
            self._values[key] = set(df[column].dropna().astype(str))
        return self._values[key]

    def check(self, evidence: Evidence) -> bool:
        # 컬럼 이름에 마침표가 있을 수 있어(spec.size) 원천·표 뒤는 더 쪼개지 않는다
        parts = evidence.locator.split(".", 2)
        if len(parts) < 2 or parts[0] != evidence.source_id:
            return False
        source, table = parts[0], parts[1]
        columns = self.columns(source, table)
        if columns is None:
            return False
        if len(parts) == 2:
            return not evidence.values
        names = [parts[2]] if parts[2] in columns else parts[2].split("+")
        if any(n not in columns for n in names):
            return False
        if not evidence.values:
            return True
        if len(names) != 1:
            return False
        return set(evidence.values) <= self.values(source, table, names[0])


def verify(
    root: Path,
    candidates: list[Candidate],
    frames: dict[str, pd.DataFrame] | None = None,
) -> tuple[list[Candidate], list[Candidate]]:
    """(내보낼 후보, 근거가 없어 뺀 후보). frames: 이미 읽은 원천 표('원천.표' → 표)."""
    lookup = SourceLookup(root, frames)
    kept, dropped = [], []
    for candidate in candidates:
        checked = [
            e.model_copy(update={"verified": lookup.check(e)})
            for e in candidate.evidence
        ]
        candidate = candidate.model_copy(update={"evidence": checked})
        ok = [e for e in checked if e.verified]
        if not ok:
            dropped.append(candidate)
            continue
        if len(ok) < len(checked) and candidate.review_status == "PENDING":
            candidate = candidate.model_copy(
                update={
                    "review_status": "HELD",
                    "hold_reason": PARTLY_MISSING,
                    # 보류는 0.6을 넘기지 않는다(confidence.HOLD_CAPS)
                    "confidence": score.with_hold(candidate.confidence, PARTLY_MISSING),
                }
            )
        kept.append(candidate)
    return kept, dropped
