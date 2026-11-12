import json
import sys
import types

import pandas as pd
import pytest

from app.sources import postgres_reader as pg
from app.sources.postgres_reader import (
    PostgresSourceReader,
    SourceError,
    psycopg_connector,
)
from app.sources.snapshot import write_snapshot
from tests.fake_postgres import FakeConn, failing_connect


def test_reader_reads_structure_and_rows_as_text():
    reader = PostgresSourceReader("mes", FakeConn)
    assert reader.list_tables() == ["machine", "mv_daily", "v_open", "work_order"]
    schema = reader.read_schema("work_order")
    assert schema.primary_key == ["wo_no"]
    assert schema.column("wo_no").declared_type == "varchar(20)"  # CSV 원천과 같은 이름
    assert schema.column("qty").declared_type == "int"
    rows = reader.read_rows("work_order")
    qty = rows.set_index("wo_no")["qty"]
    assert qty["WO-1"] == "10" and pd.isna(qty["WO-2"])
    with pytest.raises(KeyError):
        reader.read_rows("secret_table")  # 읽기 권한 목록에 없는 표


@pytest.mark.parametrize(
    "message, code",
    [
        (
            'FATAL:  password authentication failed for user "reader" host "db.internal"',
            "SOURCE_AUTH_FAILED",
        ),
        (  # SSL 없이 붙으면 원천 서버가 거절한다
            'pg_hba.conf rejects connection for host "db.internal", user "reader", no encryption',
            "SOURCE_AUTH_FAILED",
        ),
        ('connection to server at "db.internal" failed: timeout', "SOURCE_UNREACHABLE"),
    ],
)
def test_errors_carry_codes_not_connection_details(message, code):
    with pytest.raises(SourceError) as caught:
        PostgresSourceReader("mes", failing_connect(message))
    assert caught.value.code == code
    assert "db.internal" not in str(caught.value)
    assert "reader" not in str(caught.value)


def test_connection_fields_from_spring_reach_driver(monkeypatch):
    seen = {}
    monkeypatch.setitem(
        sys.modules,
        "psycopg",
        types.SimpleNamespace(connect=lambda **kw: seen.update(kw)),
    )
    connection = {
        "host": "source-erp",
        "port": 5432,
        "database": "erp",
        "username": "platform_ai",
        "password": "pw",
        "sslmode": "require",
    }
    psycopg_connector(connection)()
    assert seen["dbname"] == "erp" and seen["user"] == "platform_ai"
    assert seen["sslmode"] == "require"
    assert "default_transaction_read_only=on" in seen["options"]
    assert seen["autocommit"] is True  # 쿼리 사이에 트랜잭션을 열어 두지 않는다
    psycopg_connector({"host": "localhost"})()
    assert seen["sslmode"] == "prefer"  # 값이 없으면 SSL을 먼저 시도한다


def test_resource_list_uses_backend_catalog_rule():
    # 백엔드 연결 검사(SourceCatalogReader)와 같은 기준이어야 화면에서 고른 표를 AI도 읽을 수 있다
    sql = " ".join(pg.LIST_RESOURCES.split())
    assert "c.relkind IN ('r', 'p', 'v', 'm')" in sql
    assert "NOT c.relispartition" in sql
    assert "has_schema_privilege(n.oid, 'USAGE')" in sql
    assert "has_any_column_privilege(c.oid, 'SELECT')" in sql
    assert "has_column_privilege(a.attrelid, a.attnum, 'SELECT')" in pg.COLUMNS

    kinds = PostgresSourceReader("mes", FakeConn).resource_kinds()
    assert kinds == {
        "machine": "TABLE",
        "mv_daily": "VIEW",  # 구체화 뷰도 읽는다
        "v_open": "VIEW",
        "work_order": "TABLE",
    }


def test_table_with_column_privileges_reads_only_readable_columns(tmp_path):
    reader = PostgresSourceReader("mes", FakeConn)

    assert [c.name for c in reader.read_schema("machine").columns] == [
        "code",
        "active",
        "spec",
    ]
    rows = reader.read_rows("machine")  # 권한 없는 연락처를 SELECT하면 원천이 거절한다
    assert list(rows.columns) == ["code", "active", "spec"]

    report = write_snapshot(reader, tmp_path, {"machine": ["active", "phone"]})
    assert report.missing_columns == {"mes.machine": ["phone"]}


def test_values_are_written_like_csv_text():
    row = PostgresSourceReader("mes", FakeConn).read_rows("machine").iloc[0]

    assert row["active"] == "true"  # 파이썬 표기(True)가 아니라 원천 글자
    assert row["spec"] == '{"톤수": 200, "형식": ["A"]}'
    assert json.loads(row["spec"]) == {"톤수": 200, "형식": ["A"]}


class QueryError(Exception):
    def __init__(self, sqlstate):
        super().__init__("canceling statement due to statement timeout on db.internal")
        self.sqlstate = sqlstate


@pytest.mark.parametrize(
    "sqlstate, code, message",
    [
        ("57014", "SOURCE_TIMEOUT", "원천 조회가 시간 제한(30초)을 넘었습니다"),
        ("42P01", "SOURCE_TABLE_MISSING", "원천에서 고른 테이블을 찾지 못했습니다"),
        ("42501", "SOURCE_PERMISSION_DENIED", "원천을 읽을 권한이 없습니다"),
        ("08006", "SOURCE_UNREACHABLE", "원천에 접속하지 못했습니다"),
    ],
)
def test_query_errors_are_told_apart_without_details(sqlstate, code, message):
    reader = PostgresSourceReader("mes", lambda: FakeConn(fail=QueryError(sqlstate)))

    with pytest.raises(SourceError) as caught:
        reader.list_tables()

    assert caught.value.code == code
    assert str(caught.value) == message
