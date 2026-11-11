"""분석 실행 하나를 백그라운드에서 돌린다: 105 RUNNING → 원천 확인 → 엔진 → SUCCEEDED 또는 FAILED.

같은 실행이 겹치지 않게 진행 중인 runId만 메모리에 둔다. 접속 정보와 요청 본문은 로그에 남기지 않는다.
"""

import logging
import threading
import time
from collections.abc import Callable
from contextlib import ExitStack
from pathlib import Path
from typing import Any

from app.analysis import engine
from app.analysis.backend import BackendError, Report
from app.analysis.engine import AnalysisFailure, AnalysisResult, Sources
from app.models.analysis import AnalysisSource, AnalysisStartRequest
from app.sources.postgres_reader import (
    PostgresSourceReader,
    SourceError,
    psycopg_connector,
)

log = logging.getLogger(__name__)

UNEXPECTED = "분석 중 오류가 났습니다"
STOPPED = "AI 서비스가 다시 시작되어 분석이 중단되었습니다. 다시 분석해 주세요"
STOP_WAIT_SEC = 5  # 컨테이너 종료 유예(기본 10초) 안에 알림을 끝낸다

Connector = Callable[[dict], Callable[[], Any]]
Analyze = Callable[[AnalysisStartRequest, Sources], AnalysisResult]


class AnalysisRunner:
    def __init__(
        self,
        report: Report,
        connector: Connector = psycopg_connector,
        analyze: Analyze = engine.analyze,
    ):
        self._report = report
        self._connector = connector
        self._analyze = analyze
        self._active: set[int] = set()
        self._stopped: set[int] = set()
        self._stopping = False
        self._lock = threading.Lock()

    def claim(self, run_id: int) -> bool:
        """처음 받은 실행이면 True. 같은 실행이 아직 돌고 있으면 False(accepted=false)."""
        with self._lock:
            if run_id in self._active:
                return False
            self._active.add(run_id)
            return True

    def stop(self) -> list[int]:
        """종료(재배포) 직전에 진행 중인 실행을 FAILED로 알린다. 알리지 않으면 백엔드는 실행을 계속 RUNNING으로
        두고, 같은 대상의 새 요청도 그 실행에 합류해 시간 제한까지 막힌다. 실행마다 한 번만 알린다."""
        with self._lock:
            self._stopping = True  # 이 뒤에 받은 실행은 run()이 바로 중단으로 알린다
            runs = sorted(self._active - self._stopped)
            self._stopped.update(runs)

        def send(run_id: int) -> None:
            try:
                self._report(run_id, {"status": "FAILED", "errorMessage": STOPPED})
            except BackendError as error:
                log.warning("분석 실행 %s 중단 알림 실패(%s)", run_id, error)

        threads = [threading.Thread(target=send, args=(r,), daemon=True) for r in runs]
        for t in threads:
            t.start()
        deadline = time.monotonic() + STOP_WAIT_SEC
        for t in threads:
            t.join(max(0.0, deadline - time.monotonic()))
        if runs:
            log.warning("종료로 분석 실행 %s을 중단으로 알림", runs)
        return runs

    def run(self, request: AnalysisStartRequest) -> None:
        run_id = request.run_id
        started = time.monotonic()
        try:
            with self._lock:
                stopping = self._stopping
            if stopping:
                try:
                    self._report(run_id, {"status": "FAILED", "errorMessage": STOPPED})
                except BackendError as error:
                    log.warning("분석 실행 %s 중단 알림 실패(%s)", run_id, error)
                return
            try:
                self._report(run_id, {"status": "RUNNING"})
            except BackendError as error:
                # 백엔드가 이미 끝낸 실행(409)이거나 닿지 않으면 분석하지 않는다
                log.warning(
                    "분석 실행 %s RUNNING 알림 실패(%s), 분석하지 않음", run_id, error
                )
                return
            body = self._analyze_to_body(request)
            with self._lock:
                stopped = run_id in self._stopped
            if stopped:
                return  # 이미 중단으로 알린 실행
            try:
                self._report(run_id, body)
            except BackendError as error:
                log.warning("분석 실행 %s 결과 알림 실패(%s)", run_id, error)
                return
            log.info(
                "분석 실행 %s %s (%.1fs)",
                run_id,
                body["status"],
                time.monotonic() - started,
            )
        finally:
            with self._lock:
                self._active.discard(run_id)
                self._stopped.discard(run_id)

    def _analyze_to_body(self, request: AnalysisStartRequest) -> dict:
        try:
            with ExitStack() as stack:
                sources = {
                    source.data_source_code: self._open(request, source, stack)
                    for source in request.sources
                }
                result = self._analyze(request, sources)
            return {
                "status": "SUCCEEDED",
                "resultSummary": result.summary,
                "proposals": result.proposals,
            }
        except AnalysisFailure as failure:
            return {"status": "FAILED", "errorMessage": str(failure)}
        except Exception as error:  # noqa: BLE001 - 원인은 종류만 남겨 접속 정보가 로그에 새지 않게 한다
            log.error(
                "분석 실행 %s 처리 중 예상하지 못한 오류: %s",
                request.run_id,
                type(error).__name__,
            )
            return {"status": "FAILED", "errorMessage": UNEXPECTED}

    def _open(
        self, request: AnalysisStartRequest, source: AnalysisSource, stack: ExitStack
    ) -> PostgresSourceReader | Path:
        code = source.data_source_code
        targets = request.targets_of(code)
        if source.source_kind == "CSV":
            folder = Path(source.path) if source.path else None
            if folder is None or not folder.is_dir():
                raise AnalysisFailure(f"CSV 원천 폴더를 읽을 수 없습니다 ({code})")
            for target in targets:
                if not (folder / f"{target.table}.csv").is_file():
                    raise AnalysisFailure(
                        f"CSV 원천에서 이 테이블을 찾을 수 없습니다: {target.resource_key}"
                    )
            return folder
        try:
            reader = stack.enter_context(
                PostgresSourceReader(
                    code,
                    self._connector(source.connection),
                    schema=source.db_schema or "public",
                )
            )
            readable = set(reader.list_tables())
        except SourceError as error:
            raise AnalysisFailure(f"{error} ({code})") from None
        for target in targets:
            if target.table not in readable:
                raise AnalysisFailure(
                    f"AI 계정이 이 테이블을 읽을 수 없습니다: {target.resource_key}"
                )
        return reader
