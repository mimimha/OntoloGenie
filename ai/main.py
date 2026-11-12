import logging
import signal
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.analysis.engine import remove_stale_workdirs
from app.api.analysis_runs import get_runner
from app.api.analysis_runs import router as analysis_runs_router
from app.api.errors import ApiError, api_error, error_response
from app.api.health import router as health_router
from app.config import get_settings


async def validation_error(
    request: Request, error: RequestValidationError
) -> JSONResponse:
    """요청 형식 오류도 팀 공통 오류 형식으로 돌려준다 (개발 가이드 8.3). 입력값은 되돌려주지 않는다."""
    fields = [".".join(str(p) for p in e["loc"][1:]) for e in error.errors()][:5]
    return error_response(
        400, "VALIDATION_FAILED", "요청 필드를 확인해 주세요.", {"fields": fields}
    )


@asynccontextmanager
async def lifespan(app: FastAPI):
    """재배포로 컨테이너가 꺼질 때(SIGTERM) 진행 중인 분석을 FAILED로 알린다. uvicorn은 백그라운드 분석이
    끝날 때까지 종료를 미루다 유예 시간이 지나면 강제 종료되므로, 종료 신호를 받는 즉시 알린다.
    개발 중 Ctrl+C(SIGINT)는 다루지 않는다.

    시작할 때는 강제 종료로 남은 원천 값 임시 폴더를 지운다. 이 프로세스의 분석은 아직 시작 전이다."""
    removed = remove_stale_workdirs()
    if removed:
        logging.getLogger(__name__).warning(
            "남아 있던 분석 임시 폴더 %d개를 지움", removed
        )
    previous = signal.getsignal(signal.SIGTERM)

    def on_term(signum, frame):
        get_runner().stop()
        if callable(previous):
            previous(signum, frame)

    try:
        signal.signal(signal.SIGTERM, on_term)
    except ValueError:  # 메인 스레드가 아닐 때(테스트 클라이언트)
        pass
    yield
    get_runner().stop()


def create_app() -> FastAPI:
    settings = get_settings()
    # app.* 로거의 실행 기록(runId·상태·소요 시간·오류 코드)이 uvicorn 로그에 함께 나오게 한다
    logging.basicConfig(
        level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s"
    )
    app = FastAPI(
        title="AI Ontology Proposer", version=settings.version, lifespan=lifespan
    )
    if not settings.internal_token:
        logging.getLogger(__name__).warning(
            "AI_ONTOLOGY_INTERNAL_TOKEN이 비어 있어 분석 시작 요청을 모두 거절합니다."
        )
    app.include_router(health_router)
    app.include_router(analysis_runs_router)
    app.add_exception_handler(RequestValidationError, validation_error)
    app.add_exception_handler(ApiError, api_error)
    return app


app = create_app()
