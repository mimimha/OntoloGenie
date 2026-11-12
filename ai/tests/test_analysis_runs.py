"""분석 시작 알림 수신(API 018 하단)과 105 전송. 백엔드와 원천은 가짜로 바꾼다."""

import http.client
import json
import logging
import threading
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import ClassVar

import pytest
from fastapi.testclient import TestClient

from app.analysis.backend import BackendError, results_reporter
from app.analysis.engine import AnalysisFailure, AnalysisResult
from app.analysis.runner import RESULT_NOT_SENT, STOPPED, UNEXPECTED, AnalysisRunner
from app.api.analysis_runs import get_runner
from app.config import Settings, get_settings
from app.models.analysis import AnalysisStartRequest
from main import app
from tests.fake_postgres import FakeConn, failing_connect

TOKEN = "analysis-start-test-token"
PASSWORD = "ai-password-for-test"
PATH = "/internal/v1/analysis-runs"


def body(run_id=7, resource_key="mes.work_order", **source):
    return {
        "runId": run_id,
        "runKind": "INITIAL",
        "targets": [
            {
                "sourceResourceId": 11,
                "dataSourceCode": "MES_MAIN",
                "resourceKey": resource_key,
                "columns": ["wo_no"],
                "sourceMetadata": {"description": "작업지시"},
            }
        ],
        "sources": [
            {
                "dataSourceCode": "MES_MAIN",
                "sourceKind": "POSTGRES",
                "connection": {
                    "host": "source-mes",
                    "port": 5432,
                    "database": "mes",
                    "sslmode": "require",
                    "username": "platform_ai",
                    "password": PASSWORD,
                },
                "schema": "mes",
                **source,
            }
        ],
        "allowedTransforms": ["TRIM"],  # 아직 쓰지 않는 선택 필드는 버린다
    }


class Backend:
    """105를 흉내 낸다. 받은 본문을 쌓고, fail_on 상태면 실패한다."""

    def __init__(self, fail_on=None):
        self.reports, self.fail_on = [], fail_on

    def __call__(self, run_id, payload):
        self.reports.append((run_id, payload))
        if payload["status"] == self.fail_on:
            raise BackendError("HTTP 409", status=409)


class FlakyBackend:
    """105를 흉내 낸다. failures[상태]에 든 HTTP 상태(None은 접속 실패)를 차례로 내고, 다 쓰면 받는다."""

    def __init__(self, **failures):
        self.reports = []
        self.failures = {k: list(v) for k, v in failures.items()}

    def __call__(self, run_id, payload):
        self.reports.append((run_id, payload))
        left = self.failures.get(payload["status"])
        if left:
            status = left.pop(0)
            message = "URLError" if status is None else f"HTTP {status}"
            raise BackendError(message, status=status)


def run_once(backend, run_id=7, **kwargs):
    """재시도 대기 없이 실행 하나를 돌리고 105로 보낸 상태 순서를 돌려준다."""
    runner = AnalysisRunner(
        backend, connector=lambda c: FakeConn, retry_delays=(0, 0, 0), **kwargs
    )
    assert runner.claim(run_id)
    runner.run(AnalysisStartRequest.model_validate(body(run_id=run_id)))
    return [b["status"] for _, b in backend.reports]


@pytest.fixture
def token(monkeypatch):
    monkeypatch.setenv("AI_ONTOLOGY_INTERNAL_TOKEN", TOKEN)
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


def use(runner):
    app.dependency_overrides[get_runner] = lambda: runner
    return runner


@pytest.fixture(autouse=True)
def clear_overrides():
    yield
    app.dependency_overrides.clear()


def post(payload, token=TOKEN):
    headers = {} if token is None else {"X-Internal-Token": token}
    return TestClient(app).post(PATH, json=payload, headers=headers)


@pytest.mark.parametrize("sent", [None, "wrong-token"])
def test_rejects_missing_or_wrong_token_before_reading_body(token, sent):
    backend = Backend()
    use(AnalysisRunner(backend, connector=lambda c: FakeConn))

    response = post({"runId": "not-a-number"}, token=sent)

    assert response.status_code == 401
    assert response.json()["error"]["code"] == "UNAUTHENTICATED"
    assert backend.reports == []


def test_rejects_everything_when_token_is_not_configured(monkeypatch):
    monkeypatch.delenv("AI_ONTOLOGY_INTERNAL_TOKEN", raising=False)
    get_settings.cache_clear()
    try:
        response = post(body(), token="")
        assert response.status_code == 401
    finally:
        get_settings.cache_clear()


def test_accepts_checks_source_and_reports_running_then_engine_result(token):
    backend = Backend()
    use(AnalysisRunner(backend, connector=lambda c: FakeConn))

    response = post(body())

    assert response.status_code == 202
    assert response.json() == {"runId": 7, "accepted": True}
    assert backend.reports[0] == (7, {"status": "RUNNING"})
    result = backend.reports[1][1]
    assert result["status"] == "SUCCEEDED"
    # 객체 매핑은 표 하나 = OBJECT_TYPE 한 건 안에 담겨 간다
    work_order = next(p for p in result["proposals"] if p["itemKind"] == "OBJECT_TYPE")
    mapping = work_order["payload"]["objectMapping"]
    assert mapping["sourceId"] == "MES_MAIN"
    assert mapping["resourceKey"] == "mes.work_order"


def test_engine_gets_open_sources_and_success_is_reported(token):
    seen = {}

    def analyze(request, sources):
        seen["schema"] = sources["MES_MAIN"].schema
        seen["target"] = request.targets[0]
        return AnalysisResult({"proposalCount": 1}, [{"itemKind": "OBJECT_TYPE"}])

    backend = Backend()
    use(AnalysisRunner(backend, connector=lambda c: FakeConn, analyze=analyze))

    post(body())

    assert seen["schema"] == "mes"
    assert seen["target"].table == "work_order"
    assert seen["target"].columns == ["wo_no"]
    assert backend.reports[-1] == (
        7,
        {
            "status": "SUCCEEDED",
            "resultSummary": {"proposalCount": 1},
            "proposals": [{"itemKind": "OBJECT_TYPE"}],
        },
    )


def test_same_run_still_running_is_not_started_again(token):
    backend = Backend()
    runner = use(AnalysisRunner(backend, connector=lambda c: FakeConn))
    assert runner.claim(7)

    response = post(body())

    assert response.json() == {"runId": 7, "accepted": False}
    assert backend.reports == []


def test_finished_run_can_be_started_again(token):
    backend = Backend()
    use(AnalysisRunner(backend, connector=lambda c: FakeConn))

    post(body())
    second = post(body())

    assert second.json()["accepted"] is True
    assert len(backend.reports) == 4


def test_table_the_ai_account_cannot_read_fails_run(token):
    backend = Backend()
    use(AnalysisRunner(backend, connector=lambda c: FakeConn))

    post(body(resource_key="mes.employee"))

    assert backend.reports[-1][1] == {
        "status": "FAILED",
        "errorMessage": "AI 계정이 이 테이블을 읽을 수 없습니다: mes.employee",
    }


def test_source_auth_failure_reports_cause_without_connection_details(token, caplog):
    backend = Backend()
    use(AnalysisRunner(backend, connector=lambda c: failing_connect()))

    with caplog.at_level(logging.INFO):
        post(body())

    assert backend.reports[-1][1] == {
        "status": "FAILED",
        "errorMessage": "원천 계정 인증에 실패했습니다 (MES_MAIN)",
    }
    assert PASSWORD not in caplog.text and "db.internal" not in caplog.text


def test_unexpected_engine_error_is_reported_without_details(token, caplog):
    def analyze(request, sources):
        raise RuntimeError(f"secret {PASSWORD}")

    backend = Backend()
    use(AnalysisRunner(backend, connector=lambda c: FakeConn, analyze=analyze))

    with caplog.at_level(logging.INFO):
        post(body())

    assert backend.reports[-1][1] == {"status": "FAILED", "errorMessage": UNEXPECTED}
    assert PASSWORD not in caplog.text


def test_running_report_rejected_stops_without_analysis(token):
    called = []
    backend = Backend(fail_on="RUNNING")
    runner = use(
        AnalysisRunner(
            backend,
            connector=lambda c: called.append(c) or FakeConn,
        )
    )

    post(body())

    assert backend.reports == [(7, {"status": "RUNNING"})]
    assert called == []
    assert runner.claim(7)  # 실행 표시는 풀린다


def test_csv_source_checks_folder_and_table_files(token, tmp_path):
    (tmp_path / "work_order.csv").write_text("wo_no\nWO-1\n", encoding="utf-8")
    csv = {"sourceKind": "CSV", "connection": {}, "schema": None, "path": str(tmp_path)}
    backend = Backend()
    use(AnalysisRunner(backend))

    post(body(**csv))
    assert backend.reports[-1][1]["status"] == "SUCCEEDED"

    post(body(resource_key="mes.lot", **csv))
    assert backend.reports[-1][1]["errorMessage"] == (
        "CSV 원천에서 이 테이블을 찾을 수 없습니다: mes.lot"
    )

    post(body(**{**csv, "path": str(tmp_path / "missing")}))
    assert backend.reports[-1][1]["errorMessage"] == (
        "CSV 원천 폴더를 읽을 수 없습니다 (MES_MAIN)"
    )


def test_invalid_body_is_400_without_echoing_values(token):
    payload = body()
    payload["targets"][0]["dataSourceCode"] = "ERP_MAIN"

    response = post(payload)

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "VALIDATION_FAILED"
    assert PASSWORD not in response.text


def test_request_repr_hides_connection():
    request = AnalysisStartRequest.model_validate(body())
    assert PASSWORD not in repr(request)
    assert request.sources[0].db_schema == "mes"


class _Recorder(BaseHTTPRequestHandler):
    received: ClassVar[list] = []
    status = 200

    def do_POST(self):
        length = int(self.headers["Content-Length"])
        self.received.append(
            (
                self.path,
                self.headers["X-Internal-Token"],
                json.loads(self.rfile.read(length)),
            )
        )
        self.send_response(self.status)
        self.end_headers()
        self.wfile.write(b"{}")

    def log_message(self, *args):
        pass


@pytest.fixture
def backend_server():
    _Recorder.received, _Recorder.status = [], 200
    server = ThreadingHTTPServer(("127.0.0.1", 0), _Recorder)
    threading.Thread(
        target=server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True
    ).start()
    yield server
    server.shutdown()


def test_reporter_posts_to_105_with_token(backend_server):
    port = backend_server.server_address[1]
    report = results_reporter(
        Settings(backend_url=f"http://127.0.0.1:{port}/", internal_token=TOKEN)
    )

    report(7, {"status": "FAILED", "errorMessage": "분석 중 오류가 났습니다"})

    assert _Recorder.received == [
        (
            "/api/v1/internal/analyses/7/results",
            TOKEN,
            {"status": "FAILED", "errorMessage": "분석 중 오류가 났습니다"},
        )
    ]


def test_reporter_turns_http_and_connection_errors_into_backend_error(
    backend_server, monkeypatch
):
    port = backend_server.server_address[1]
    _Recorder.status = 409
    report = results_reporter(Settings(backend_url=f"http://127.0.0.1:{port}"))
    with pytest.raises(BackendError, match="HTTP 409"):
        report(7, {"status": "RUNNING"})

    def refused(*args, **kwargs):
        raise urllib.error.URLError(ConnectionRefusedError())

    monkeypatch.setattr(urllib.request, "urlopen", refused)
    closed = results_reporter(Settings(backend_url="http://127.0.0.1:1"))
    with pytest.raises(BackendError):
        closed(7, {"status": "RUNNING"})


def test_stop_reports_running_analyses_as_failed_once_and_drops_late_result():
    # 재배포로 AI가 꺼지면 진행 중이던 실행을 백엔드에 FAILED로 알린다(안 알리면 RUNNING으로 남아 새 요청이 합류해 막힌다)
    backend = Backend()
    started, release = threading.Event(), threading.Event()

    def slow(request, sources):
        started.set()
        release.wait(5)
        return AnalysisResult(summary={}, proposals=[])

    runner = AnalysisRunner(backend, connector=lambda c: FakeConn, analyze=slow)
    assert runner.claim(7)
    worker = threading.Thread(
        target=runner.run, args=(AnalysisStartRequest.model_validate(body()),)
    )
    worker.start()
    assert started.wait(5)

    assert runner.stop() == [7]
    assert runner.stop() == []  # 두 번 알리지 않는다
    release.set()
    worker.join(5)

    statuses = [b["status"] for _, b in backend.reports]
    assert statuses == ["RUNNING", "FAILED"]  # 중단 뒤 늦게 끝난 결과는 보내지 않는다
    assert backend.reports[-1][1]["errorMessage"] == STOPPED


def test_run_received_after_stop_is_reported_stopped_without_analysis():
    backend = Backend()
    called = []
    runner = AnalysisRunner(
        backend, connector=lambda c: FakeConn, analyze=lambda r, s: called.append(1)
    )
    runner.stop()
    assert runner.claim(9)
    runner.run(AnalysisStartRequest.model_validate(body(run_id=9)))

    assert backend.reports == [(9, {"status": "FAILED", "errorMessage": STOPPED})]
    assert called == []


def test_result_report_retries_connection_errors_and_5xx():
    backend = FlakyBackend(SUCCEEDED=[None, 503])

    statuses = run_once(backend)

    assert statuses == ["RUNNING", "SUCCEEDED", "SUCCEEDED", "SUCCEEDED"]
    assert backend.reports[1][1] == backend.reports[3][1]  # 같은 결과를 다시 보낸다


def test_result_report_gives_up_when_run_is_unknown():
    backend = FlakyBackend(SUCCEEDED=[404])

    assert run_once(backend) == ["RUNNING", "SUCCEEDED"]


def test_result_conflict_after_retry_means_earlier_try_was_saved():
    # 앞선 시도가 저장됐는데 응답만 끊겼으면 다시 보낸 요청은 409가 된다. 실패로 덮어쓰지 않는다
    backend = FlakyBackend(SUCCEEDED=[None, 409])

    assert run_once(backend) == ["RUNNING", "SUCCEEDED", "SUCCEEDED"]


def test_result_conflict_on_first_try_is_reported_as_short_failure():
    # 첫 시도의 409는 저장 중 제약 위반일 수도 있다. 이미 끝난 실행이면 FAILED도 409로 무시된다
    backend = FlakyBackend(SUCCEEDED=[409])

    assert run_once(backend) == ["RUNNING", "SUCCEEDED", "FAILED"]
    assert backend.reports[-1][1]["errorMessage"] == RESULT_NOT_SENT


def test_result_rejected_as_invalid_is_reported_as_short_failure():
    backend = FlakyBackend(SUCCEEDED=[400])

    assert run_once(backend) == ["RUNNING", "SUCCEEDED", "FAILED"]
    assert backend.reports[-1][1] == {
        "status": "FAILED",
        "errorMessage": RESULT_NOT_SENT,
    }


def test_result_still_unsent_after_retries_is_reported_as_failure(caplog):
    # 결과를 못 보내고 끝나면 백엔드에서 실행이 시간 제한(60분)까지 RUNNING으로 묶인다
    backend = FlakyBackend(SUCCEEDED=[502, 502, 502, 502], FAILED=[None])

    with caplog.at_level(logging.WARNING):
        statuses = run_once(backend)

    assert statuses == ["RUNNING", *["SUCCEEDED"] * 4, "FAILED"]
    assert backend.reports[-1][1]["errorMessage"] == RESULT_NOT_SENT
    assert "실패 알림도 실패(URLError)" in caplog.text
    assert PASSWORD not in caplog.text


def test_failure_body_that_cannot_be_sent_is_also_reported_as_short_failure():
    def analyze(request, sources):
        raise AnalysisFailure("원천에 접속하지 못했습니다 (MES_MAIN)")

    backend = FlakyBackend(FAILED=[None, None, None, None])

    statuses = run_once(backend, analyze=analyze)

    assert statuses == ["RUNNING", *["FAILED"] * 5]
    assert backend.reports[-1][1]["errorMessage"] == RESULT_NOT_SENT


def test_running_report_retries_connection_errors_then_analyzes():
    backend = FlakyBackend(RUNNING=[None, 500])

    assert run_once(backend) == ["RUNNING", "RUNNING", "RUNNING", "SUCCEEDED"]


@pytest.mark.parametrize("status", [409, 404])
def test_running_report_rejected_gives_up_at_once(status):
    called = []
    backend = FlakyBackend(RUNNING=[status])

    statuses = run_once(backend, analyze=lambda r, s: called.append(1))

    assert statuses == ["RUNNING"]
    assert called == []


def test_running_conflict_after_retry_means_earlier_try_was_saved():
    # 첫 RUNNING이 저장됐는데 응답만 끊기면 다시 보낸 RUNNING은 409다. 분석은 이어 간다
    backend = FlakyBackend(RUNNING=[None, 409])

    assert run_once(backend) == ["RUNNING", "RUNNING", "SUCCEEDED"]


def test_running_report_unreachable_after_retries_does_not_analyze():
    called = []
    backend = FlakyBackend(RUNNING=[None] * 4)

    statuses = run_once(backend, analyze=lambda r, s: called.append(1))

    assert statuses == ["RUNNING"] * 4
    assert called == []


def test_stop_during_retry_wait_ends_retries_without_extra_failure():
    failed_once = threading.Event()

    class SignalingBackend(FlakyBackend):
        def __call__(self, run_id, payload):
            try:
                super().__call__(run_id, payload)
            finally:
                if payload["status"] == "SUCCEEDED":
                    failed_once.set()

    backend = SignalingBackend(SUCCEEDED=[503])
    runner = AnalysisRunner(backend, connector=lambda c: FakeConn, retry_delays=(30,))
    assert runner.claim(7)
    worker = threading.Thread(
        target=runner.run, args=(AnalysisStartRequest.model_validate(body()),)
    )
    worker.start()
    assert failed_once.wait(30)

    assert runner.stop() == [7]
    worker.join(5)

    assert not worker.is_alive()  # 30초 대기를 다 기다리지 않는다
    statuses = [b["status"] for _, b in backend.reports]
    assert statuses == ["RUNNING", "SUCCEEDED", "FAILED"]
    assert backend.reports[-1][1]["errorMessage"] == STOPPED


@pytest.mark.parametrize("resource_key", ["work_order", ".work_order", "mes.", ""])
def test_resource_key_without_schema_and_name_is_400(token, resource_key):
    backend = Backend()
    use(AnalysisRunner(backend, connector=lambda c: FakeConn))

    response = post(body(resource_key=resource_key))

    assert response.status_code == 400
    assert response.json()["error"]["details"]["fields"] == ["targets.0.resourceKey"]
    assert backend.reports == []


def test_sources_sharing_a_schema_name_fail_before_connecting(token):
    payload = body(resource_key="public.item")
    payload["sources"].append({**payload["sources"][0], "dataSourceCode": "ERP_MAIN"})
    payload["targets"].append(
        {**payload["targets"][0], "sourceResourceId": 12, "dataSourceCode": "ERP_MAIN"}
    )
    connected = []
    backend = Backend()
    use(AnalysisRunner(backend, connector=lambda c: connected.append(1) or FakeConn))

    post(payload)

    assert backend.reports[-1][1] == {
        "status": "FAILED",
        "errorMessage": "서로 다른 원천이 같은 스키마 이름(public)을 써서 함께 분석할 수 없습니다: "
        "ERP_MAIN, MES_MAIN. 원천별로 나눠 분석해 주세요",
    }
    assert connected == []


def test_reporter_marks_status_so_runner_can_tell_retry_from_give_up(backend_server):
    port = backend_server.server_address[1]
    report = results_reporter(Settings(backend_url=f"http://127.0.0.1:{port}"))
    expected = {
        409: (True, False),
        404: (True, False),
        400: (False, False),
        503: (False, True),
    }
    for status, (gave_up, retryable) in expected.items():
        _Recorder.status = status
        with pytest.raises(BackendError) as caught:
            report(7, {"status": "RUNNING"})
        assert caught.value.status == status
        assert (caught.value.gave_up, caught.value.retryable) == (gave_up, retryable)


def test_reporter_turns_cut_response_into_retryable_backend_error(monkeypatch):
    def cut(*args, **kwargs):
        raise http.client.IncompleteRead(b"{")

    monkeypatch.setattr(urllib.request, "urlopen", cut)
    report = results_reporter(Settings(backend_url="http://127.0.0.1:1"))

    with pytest.raises(BackendError) as caught:
        report(7, {"status": "RUNNING"})
    assert caught.value.status is None and caught.value.retryable


@pytest.mark.parametrize(
    "summary",
    [{"score": float("nan")}, {"tables": {"a", "b"}}, {"text": "\ud800"}],
    ids=["nan", "set", "lone-surrogate"],
)
def test_reporter_turns_unserializable_body_into_backend_error(backend_server, summary):
    port = backend_server.server_address[1]
    report = results_reporter(Settings(backend_url=f"http://127.0.0.1:{port}"))

    with pytest.raises(BackendError) as caught:
        report(7, {"status": "SUCCEEDED", "resultSummary": summary, "proposals": []})

    assert caught.value.invalid_body and not caught.value.retryable
    assert _Recorder.received == []  # 보내지 않는다


def test_unserializable_result_is_reported_as_short_failure(backend_server):
    port = backend_server.server_address[1]
    report = results_reporter(Settings(backend_url=f"http://127.0.0.1:{port}"))

    def analyze(request, sources):
        return AnalysisResult({"score": float("nan")}, [])

    runner = AnalysisRunner(
        report, connector=lambda c: FakeConn, analyze=analyze, retry_delays=(0,)
    )
    assert runner.claim(7)
    runner.run(AnalysisStartRequest.model_validate(body()))

    assert [b for _, _, b in _Recorder.received] == [
        {"status": "RUNNING"},
        {"status": "FAILED", "errorMessage": RESULT_NOT_SENT},
    ]
