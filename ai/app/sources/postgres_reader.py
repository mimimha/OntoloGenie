"""AI 분석용 PostgreSQL 원천 읽기 (CsvSourceReader와 같은 창구).

읽기 전용 세션으로 SELECT 권한이 있는 표·뷰만 보여 주고, 오류에는 접속 정보를 넣지 않는다.
"""

import json
from collections.abc import Callable
from typing import Any

import pandas as pd

from app.models.source import ColumnSchema, TableSchema
from app.sources.base import UnknownTableError

# 백엔드 연결 검사(SourceCatalogReader)와 같은 기준: 표·파티션 부모·뷰·구체화 뷰 중 파티션 자식은 빼고,
# 스키마 USAGE와 표 또는 컬럼 하나 이상의 SELECT 권한이 있는 것
LIST_RESOURCES = """
SELECT c.relname, c.relkind
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = %s AND c.relkind IN ('r', 'p', 'v', 'm')
  AND NOT c.relispartition
  AND has_schema_privilege(n.oid, 'USAGE')
  AND has_any_column_privilege(c.oid, 'SELECT')
ORDER BY c.relname
"""
TABLE_COMMENT = "SELECT obj_description(format('%%I.%%I', %s::text, %s::text)::regclass, 'pg_class')"
# 제약 조건 정보(information_schema)는 표 주인에게만 보여 조회 전용 계정은 pg_catalog로 읽는다.
# 컬럼 단위로만 권한을 준 표는 SELECT할 수 있는 컬럼만 보인다(백엔드 연결 검사와 같음)
COLUMNS = """
SELECT a.attname, format_type(a.atttypid, a.atttypmod), col_description(a.attrelid, a.attnum)
FROM pg_attribute a
WHERE a.attrelid = format('%%I.%%I', %s::text, %s::text)::regclass
  AND a.attnum > 0 AND NOT a.attisdropped
  AND has_column_privilege(a.attrelid, a.attnum, 'SELECT')
ORDER BY a.attnum
"""
PRIMARY_KEY = """
SELECT a.attname
FROM pg_index i
JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
WHERE i.indrelid = format('%%I.%%I', %s::text, %s::text)::regclass AND i.indisprimary
ORDER BY array_position(i.indkey::int2[], a.attnum)
"""
# CSV 원천(_schema.json)과 같은 자료형 이름으로 맞춰 분석 결과가 원천 종류에 따라 달라지지 않게 한다
TYPE_NAMES = {
    "character varying": "varchar",
    "character": "char",
    "integer": "int",
    "timestamp without time zone": "timestamp",
    "timestamp with time zone": "timestamptz",
    "time without time zone": "time",
}


def type_name(pg_type: str) -> str:
    """format_type 결과 → CSV 원천과 같은 이름. 예: character varying(20) → varchar(20)."""
    base, paren, rest = pg_type.partition("(")
    return TYPE_NAMES.get(base, base) + paren + rest


# 드라이버 메시지에는 호스트·계정이 들어 있어 내보내지 않고, 화면(019)에 보일 문구로 바꾼다
ERROR_MESSAGES = {
    "SOURCE_AUTH_FAILED": "원천 계정 인증에 실패했습니다",
    "SOURCE_PERMISSION_DENIED": "원천을 읽을 권한이 없습니다",
    "SOURCE_UNREACHABLE": "원천에 접속하지 못했습니다",
    "SOURCE_TIMEOUT": "원천 조회가 시간 제한(30초)을 넘었습니다",
    "SOURCE_TABLE_MISSING": "원천에서 고른 테이블을 찾지 못했습니다",
    "SOURCE_DRIVER_MISSING": "PostgreSQL 드라이버가 설치되지 않았습니다",
    "SOURCE_UNSUPPORTED": "이 원천 종류는 아직 읽을 수 없습니다",
}


class SourceError(Exception):
    """접속 정보·원천 값을 담지 않는 원천 오류."""

    def __init__(self, code: str):
        super().__init__(ERROR_MESSAGES[code])
        self.code = code


# 접속 단계 오류에는 sqlstate가 없어 서버 메시지로만 가린다. 메시지는 분류에만 쓰고 밖으로 내보내지 않는다
AUTH_MESSAGES = ("authentication failed", "pg_hba.conf")


def error_code(error: Exception) -> str:
    """PostgreSQL 오류 상태값(sqlstate)으로 원인만 구분한다. 28xxx 인증, 42501 권한, 57014 시간 초과, 42P01 표 없음."""
    if isinstance(error, SourceError):
        return error.code
    state = getattr(error, "sqlstate", None) or ""
    if state.startswith("28") or any(m in str(error) for m in AUTH_MESSAGES):
        return "SOURCE_AUTH_FAILED"
    if state == "42501":
        return "SOURCE_PERMISSION_DENIED"
    if state == "57014":  # statement_timeout으로 취소됨
        return "SOURCE_TIMEOUT"
    if state == "42P01":  # 목록을 읽은 뒤 표가 없어짐
        return "SOURCE_TABLE_MISSING"
    return "SOURCE_UNREACHABLE"


def psycopg_connector(
    connection: dict, timeout_sec: int = 10, statement_timeout_ms: int = 30000
) -> Callable[[], Any]:
    """Spring이 넘긴 접속 정보로 읽기 전용 세션을 여는 함수. 드라이버는 이때만 불러온다."""

    def connect():
        try:
            import psycopg
        except ImportError as error:
            raise SourceError("SOURCE_DRIVER_MISSING") from error
        return psycopg.connect(
            host=connection.get("host"),
            port=connection.get("port", 5432),
            dbname=connection.get("database"),
            user=connection.get("username"),
            password=connection.get("password"),
            # 원천 AI 계정은 SSL만 받는다
            sslmode=connection.get("sslmode") or "prefer",
            connect_timeout=timeout_sec,
            # 쿼리마다 바로 끝내 스냅샷 사이에 트랜잭션을 열어 두지 않는다
            autocommit=True,
            options=(
                "-c default_transaction_read_only=on"
                f" -c statement_timeout={statement_timeout_ms}"
            ),
        )

    return connect


def _text(value: Any) -> str | None:
    """원천 값 → CSV 원천과 같은 글자. bool은 true·false, JSON(dict·list)은 JSON 글자로 둔다."""
    if value is None:
        return None
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, dict | list):
        return json.dumps(value, ensure_ascii=False, default=str)
    return str(value)


def _quote(name: str) -> str:
    return '"' + name.replace('"', '""') + '"'


class PostgresSourceReader:
    """스키마 하나를 원천 하나로 본다. CsvSourceReader와 같은 창구(SourceReader)."""

    def __init__(
        self,
        source_id: str,
        connect: Callable[[], Any],
        schema: str = "public",
    ):
        self.source_id = source_id
        self.schema = schema
        try:
            self._conn = connect()
        except Exception as error:  # noqa: BLE001 - 드라이버별 예외를 코드로만 바꿔 접속 정보가 새지 않게 한다
            raise SourceError(error_code(error)) from None
        self._kinds: dict[str, str] | None = None

    def close(self) -> None:
        """여러 번 불러도 된다(스냅샷 뒤 엔진이 먼저 닫고 runner가 다시 닫는다)."""
        self._conn.close()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    def _query(self, sql: str, params: tuple = ()) -> list[tuple]:
        try:
            cursor = self._conn.cursor()
            cursor.execute(sql, params)
            return list(cursor.fetchall())
        except Exception as error:  # noqa: BLE001 - 위와 같은 이유
            raise SourceError(error_code(error)) from None

    def resource_kinds(self) -> dict[str, str]:
        """읽을 수 있는 표·뷰 → TABLE · VIEW."""
        if self._kinds is None:
            self._kinds = {
                name: "VIEW" if kind in ("v", "m") else "TABLE"
                for name, kind in self._query(LIST_RESOURCES, (self.schema,))
            }
        return self._kinds

    def list_tables(self) -> list[str]:
        return sorted(self.resource_kinds())

    def _check(self, table: str) -> None:
        if table not in self.resource_kinds():
            raise UnknownTableError(
                f"{self.source_id}에서 {table} 표를 읽을 수 없습니다."
            )

    def read_schema(self, table: str) -> TableSchema:
        self._check(table)
        comment = self._query(TABLE_COMMENT, (self.schema, table))
        keys = {r[0] for r in self._query(PRIMARY_KEY, (self.schema, table))}
        columns = [
            ColumnSchema(
                name=name,
                declared_type=type_name(dtype),
                comment=col_comment,
                is_primary_key=name in keys,
            )
            for name, dtype, col_comment in self._query(COLUMNS, (self.schema, table))
        ]
        return TableSchema(
            source_id=self.source_id,
            name=table,
            description=comment[0][0] if comment else None,
            columns=columns,
        )

    def read_rows(
        self, table: str, columns: list[str] | None = None, limit: int | None = None
    ) -> pd.DataFrame:
        """모든 값을 문자열로, 빈 값은 결측으로 돌려준다. 고르지 않은 컬럼은 SELECT에 넣지 않는다.

        limit은 기본키 순서로, 기본키가 없으면 정렬 없이 앞에서 자른다(큰 뷰 전체 정렬 방지).
        """
        self._check(table)
        schema = self.read_schema(table)
        known = [c.name for c in schema.columns]
        names = known if columns is None else [n for n in columns if n in known]
        if not names:
            raise UnknownTableError(f"{self.schema}.{table}: 읽을 컬럼이 없습니다")
        sql = (
            f"SELECT {', '.join(_quote(n) for n in names)}"
            f" FROM {_quote(self.schema)}.{_quote(table)}"
        )
        if limit is not None:
            if schema.primary_key:
                sql += f" ORDER BY {', '.join(_quote(n) for n in schema.primary_key)}"
            sql += f" LIMIT {int(limit)}"
        rows = self._query(sql)
        frame = pd.DataFrame(
            [[_text(v) for v in row] for row in rows],
            columns=names,
            dtype=object,
        )
        return frame.where(frame.notna(), float("nan"))
