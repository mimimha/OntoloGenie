"""백엔드 105(POST /api/v1/internal/analyses/{id}/results)로 실행 상태와 결과를 보낸다."""

import http.client
import json
import urllib.error
import urllib.request
from collections.abc import Callable

from app.config import Settings

TIMEOUT_SEC = 30

Report = Callable[[int, dict], None]

# 백엔드가 이 실행을 더 받지 않는 응답: 이미 끝난 실행(409), 없는 실행(404)
GIVE_UP_STATUSES = {404, 409}


class BackendError(Exception):
    """105 호출 실패. 원인은 HTTP 상태나 오류 종류만 담는다.

    status는 HTTP 상태(응답을 못 받았으면 None), invalid_body는 본문을 JSON으로 만들지 못한 경우다.
    """

    def __init__(
        self, message: str, status: int | None = None, invalid_body: bool = False
    ):
        super().__init__(message)
        self.status = status
        self.invalid_body = invalid_body
        self.after_retry = (
            False  # 접속 실패·5xx로 다시 보낸 요청에서 난 오류인지(runner가 채움)
        )

    @property
    def gave_up(self) -> bool:
        """다시 보내도 받지 않는 실행. 결과도 실패 알림도 보내지 않는다."""
        return self.status in GIVE_UP_STATUSES

    @property
    def retryable(self) -> bool:
        """접속 실패·시간 초과·5xx처럼 잠시 뒤 다시 보내면 될 수 있는 실패."""
        if self.invalid_body:
            return False
        return self.status is None or self.status >= 500 or self.status in (408, 429)


def _encode(body: dict) -> bytes:
    try:
        # NaN·Infinity는 JSON이 아니라 백엔드가 본문 전체를 거절한다
        return json.dumps(body, ensure_ascii=False, allow_nan=False).encode("utf-8")
    except (TypeError, ValueError) as error:  # UnicodeEncodeError도 ValueError
        raise BackendError(
            f"본문을 만들지 못함({type(error).__name__})", invalid_body=True
        ) from None


def results_reporter(settings: Settings) -> Report:
    base = settings.backend_url.rstrip("/")
    token = settings.internal_token or ""

    def report(run_id: int, body: dict) -> None:
        request = urllib.request.Request(
            f"{base}/api/v1/internal/analyses/{run_id}/results",
            data=_encode(body),
            method="POST",
            headers={"Content-Type": "application/json", "X-Internal-Token": token},
        )
        try:
            with urllib.request.urlopen(request, timeout=TIMEOUT_SEC) as response:
                response.read()
        except urllib.error.HTTPError as error:
            raise BackendError(f"HTTP {error.code}", status=error.code) from None
        # 접속 실패·시간 초과(URLError도 OSError), 응답이 중간에 끊김(IncompleteRead 등)
        except (OSError, http.client.HTTPException) as error:
            raise BackendError(type(error).__name__) from None

    return report


def fetch_model_summary(settings: Settings) -> dict:
    """API 043 모델 요약(GET /api/v1/internal/ontology/model-summary)의 data. 못 받으면 BackendError."""
    request = urllib.request.Request(
        f"{settings.backend_url.rstrip('/')}/api/v1/internal/ontology/model-summary",
        method="GET",
        headers={
            "Accept": "application/json",
            "X-Internal-Token": settings.internal_token or "",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SEC) as response:
            body = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        raise BackendError(f"HTTP {error.code}", status=error.code) from None
    except (OSError, http.client.HTTPException) as error:  # 접속 실패·시간 초과·끊김
        raise BackendError(type(error).__name__) from None
    except ValueError:  # JSON이 아님
        raise BackendError("응답 형식 오류") from None
    data = body.get("data") if isinstance(body, dict) else None
    if not isinstance(data, dict):
        raise BackendError("응답 형식 오류")
    return data
