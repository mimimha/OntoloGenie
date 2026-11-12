"""원천 PostgreSQL을 흉내 내는 가짜 연결 (드라이버 없이 테스트)."""

import re

from app.sources import postgres_reader as pg

ROW_QUERY = re.compile(
    r'SELECT (?P<cols>.+?) FROM "?\w+"?\."?(?P<table>\w+)"?(?: ORDER BY (?P<order>.+?))?(?: LIMIT (?P<limit>\d+))?$'
)

# kind는 pg_class.relkind(r 표, v 뷰, m 구체화 뷰). denied는 컬럼 SELECT 권한이 없는 컬럼
TABLES = {
    "work_order": {
        "kind": "r",
        "comment": "작업지시",
        "columns": [
            ("wo_no", "character varying(20)", "작업지시 번호"),
            ("qty", "integer", None),
        ],
        "pk": ["wo_no"],
        # 기본키 순서와 다르게 넣어 ORDER BY가 실제로 정렬하는지 본다
        "rows": [("WO-2", None), ("WO-1", 10)],
    },
    "v_open": {
        "kind": "v",
        "comment": None,
        "columns": [("wo_no", "text", None)],
        "pk": [],
        "rows": [],
    },
    "mv_daily": {
        "kind": "m",
        "comment": "일별 생산 집계",
        "columns": [("work_date", "date", None)],
        "pk": [],
        "rows": [("2026-08-20",)],
    },
    # 컬럼 단위로만 권한을 준 표. 연락처는 AI 계정이 읽지 못한다
    "machine": {
        "kind": "r",
        "comment": "설비",
        "columns": [
            ("code", "character varying(10)", None),
            ("active", "boolean", None),
            ("spec", "jsonb", None),
            ("phone", "text", None),
        ],
        "denied": ["phone"],
        "pk": ["code"],
        "rows": [("M-1", True, {"톤수": 200, "형식": ["A"]}, "010-0000-0000")],
    },
}


class PermissionDenied(Exception):
    sqlstate = "42501"


class FakeCursor:
    def __init__(self, conn):
        self.conn, self.result = conn, []

    def execute(self, sql, params=()):
        if self.conn.fail:
            raise self.conn.fail
        if sql == pg.LIST_RESOURCES:
            self.result = [(name, t["kind"]) for name, t in sorted(TABLES.items())]
        elif sql == pg.TABLE_COMMENT:
            self.result = [(TABLES[params[1]]["comment"],)]
        elif sql == pg.COLUMNS:
            table = TABLES[params[1]]
            denied = table.get("denied", [])
            self.result = [c for c in table["columns"] if c[0] not in denied]
        elif sql == pg.PRIMARY_KEY:
            self.result = [(c,) for c in TABLES[params[1]]["pk"]]
        elif m := ROW_QUERY.match(sql):
            self.conn.row_queries.append(sql)
            table = TABLES[m["table"]]
            names = [c[0] for c in table["columns"]]
            picked = [names.index(c.strip('"')) for c in m["cols"].split(", ")]
            if any(names[i] in table.get("denied", []) for i in picked):
                raise PermissionDenied("permission denied for table")
            source = table["rows"]
            if m["order"]:
                keys = [names.index(c.strip('"')) for c in m["order"].split(", ")]
                source = sorted(source, key=lambda row: [row[i] for i in keys])
            rows = [tuple(row[i] for i in picked) for row in source]
            self.result = rows[: int(m["limit"])] if m["limit"] else rows
        else:
            raise AssertionError(sql)

    def fetchall(self):
        return self.result


class FakeConn:
    def __init__(self, writable=0, fail=None):
        self.writable, self.fail, self.closed, self.row_queries = (
            writable,
            fail,
            False,
            [],
        )

    def cursor(self):
        return FakeCursor(self)

    def close(self):
        self.closed = True


class ConnectError(Exception):
    """드라이버의 접속 단계 오류. 실제 psycopg처럼 sqlstate 없이 서버 메시지만 있다."""

    sqlstate = None


def failing_connect(
    message='FATAL:  password authentication failed for user "reader" host "db.internal"',
):
    def connect():
        raise ConnectError(message)

    return connect
