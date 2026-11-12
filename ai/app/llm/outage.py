"""LLM 장애 끊기. 시간 초과·접속 실패가 연속으로 나면 그 단계의 남은 호출을 건너뛴다.

호출마다 응답 대기 시간(기본 60초)을 다 쓰면 표·묶음이 많은 분석이 수십 분 멈춘 것처럼 보이므로 단계마다 하나씩 쓴다.
"""

from app.llm.client import LLMError

LIMIT = 3  # 연속 장애 이 횟수부터 남은 호출을 건너뛴다
# client가 시간 초과·접속 실패·게이트웨이 오류에 붙이는 문구
MARKS = (
    "응답이 없습니다",
    "접속하지 못했습니다",
    "(HTTP 502)",
    "(HTTP 503)",
    "(HTTP 504)",
)


def is_outage(error: BaseException) -> bool:
    """LLM 서버가 답하지 않은 오류인지. 형식이 틀린 답은 서버가 답한 것이라 장애로 세지 않는다."""
    return isinstance(error, LLMError) and any(mark in str(error) for mark in MARKS)


class Outage:
    """연속 장애 수. 답을 한 번이라도 받으면 0으로 돌린다."""

    def __init__(self, limit: int = LIMIT):
        self.limit = limit
        self.count = 0

    def record(self, error: BaseException | None) -> None:
        self.count = self.count + 1 if error is not None and is_outage(error) else 0

    @property
    def tripped(self) -> bool:
        return self.count >= self.limit

    @property
    def reason(self) -> str:
        return f"LLM이 연속 {self.count}번 응답하지 않아 남은 호출을 건너뜀"
