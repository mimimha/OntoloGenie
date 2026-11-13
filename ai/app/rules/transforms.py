import re
import unicodedata
from datetime import datetime
from decimal import Decimal, InvalidOperation
from enum import Enum

from pydantic import BaseModel, model_validator


class TransformOp(str, Enum):
    """값 변환 허용 목록(정의 관리 FN-MAP-05와 같음). AI 후보도 이 목록 안에서만 만든다."""

    STRIP_PREFIX = "STRIP_PREFIX"
    ADD_PREFIX = "ADD_PREFIX"
    PAD_ZEROS = "PAD_ZEROS"
    UPPER = "UPPER"
    TRIM = "TRIM"
    EXTRACT_NUMBER = "EXTRACT_NUMBER"
    TO_INTEGER = "TO_INTEGER"
    TO_DECIMAL = "TO_DECIMAL"
    TO_DATE = "TO_DATE"
    MAP_VALUE = "MAP_VALUE"


TEXT_ARG_OPS = {TransformOp.STRIP_PREFIX, TransformOp.ADD_PREFIX}


class TransformError(ValueError):
    """변환 실패. 정합성 검사에서는 조건 위반으로 분류될 수 있다."""


class TransformStep(BaseModel):
    op: TransformOp
    arg: str | int | dict[str, str] | None = None

    @model_validator(mode="after")
    def _arg_matches_op(self) -> "TransformStep":
        if self.op in TEXT_ARG_OPS:
            if not (isinstance(self.arg, str) and self.arg):
                raise ValueError(f"{self.op.value}에는 문자열 인자가 필요합니다.")
        elif self.op == TransformOp.PAD_ZEROS:
            if not (isinstance(self.arg, int) and self.arg > 0):
                raise ValueError("PAD_ZEROS에는 1 이상의 자릿수가 필요합니다.")
        elif self.op == TransformOp.MAP_VALUE:
            if not (isinstance(self.arg, dict) and self.arg):
                raise ValueError("MAP_VALUE에는 값 대응표가 필요합니다.")
        elif self.arg is not None:
            raise ValueError(f"{self.op.value}에는 인자가 없습니다.")
        return self


# 백엔드 검사(ValueTransformer.java)와 같은 결과를 내야 규칙 시험 적용과 AI 비교가 어긋나지 않는다.
# Java String.strip()이 지우는 공백: Character.isWhitespace (줄바꿈 없는 공백 U+00A0·U+2007·U+202F는 안 지움)
_JAVA_SPACE_CONTROLS = set("\t\n\x0b\x0c\r\x1c\x1d\x1e\x1f")
_NO_BREAK_SPACES = {"\u00a0", "\u2007", "\u202f"}
# str 정규식의 \d는 유니코드 Nd(Java BigInteger·BigDecimal이 받는 숫자)
_INTEGER = re.compile(r"[+-]?\d+")
# Java new BigDecimal(String): 부호, 숫자와 소수점, 지수. NaN·Infinity·'1_000'·안쪽 공백은 받지 않는다
_DECIMAL = re.compile(r"[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?")
# Java DateTimeFormatter(uuuu-MM-dd 등, STRICT): 연 4자리·월 2자리·일 2자리 ASCII 숫자만. '2026-1-5'는 실패
_DATES = [
    (
        re.compile(rf"[0-9]{{4}}{re.escape(sep)}[0-9]{{2}}{re.escape(sep)}[0-9]{{2}}"),
        f"%Y{sep}%m{sep}%d",
    )
    for sep in ("-", ".", "/", "")
]


def _is_java_whitespace(ch: str) -> bool:
    if ch in _JAVA_SPACE_CONTROLS:
        return True
    return unicodedata.category(ch) in ("Zs", "Zl", "Zp") and ch not in _NO_BREAK_SPACES


def java_strip(value: str) -> str:
    start, end = 0, len(value)
    while start < end and _is_java_whitespace(value[start]):
        start += 1
    while end > start and _is_java_whitespace(value[end - 1]):
        end -= 1
    return value[start:end]


def _is_decimal_digit(ch: str) -> bool:
    """Java Character.isDigit: 유니코드 Nd만. '²'·'①'은 숫자가 아니다(파이썬 isdigit은 숫자로 봄)."""
    return unicodedata.category(ch) == "Nd"


def apply_step(step: TransformStep, value: str) -> str:
    if step.op == TransformOp.TRIM:
        return java_strip(value)
    if step.op == TransformOp.UPPER:
        return value.upper()
    if step.op == TransformOp.STRIP_PREFIX:
        # 접두어가 없으면 그대로 둔다. 이미 다른 형식으로 입력된 값을 망가뜨리지 않기 위함.
        return value.removeprefix(step.arg)
    if step.op == TransformOp.ADD_PREFIX:
        return step.arg + value
    if step.op == TransformOp.PAD_ZEROS:
        if value and all(_is_decimal_digit(ch) for ch in value):
            return "0" * max(0, step.arg - len(value)) + value
        return value
    if step.op == TransformOp.EXTRACT_NUMBER:
        # 숫자와 소수점만 남긴다. 예: '600톤' → '600', 'OD-2608-012' → '2608012'
        number = "".join(ch for ch in value if _is_decimal_digit(ch) or ch == ".")
        if not any(_is_decimal_digit(ch) for ch in number):
            raise TransformError(f"숫자가 없습니다: {value!r}")
        return number
    if step.op == TransformOp.TO_INTEGER:
        # Java new BigInteger(value.strip()): 부호 하나 + 숫자만. 파이썬 int가 받는 '1_000'·앞뒤 줄바꿈 없는 공백은 실패
        text = java_strip(value)
        try:
            if not _INTEGER.fullmatch(text):
                raise ValueError(text)
            return str(int(text))
        except ValueError as error:
            raise TransformError(f"정수가 아닙니다: {value!r}") from error
    if step.op == TransformOp.TO_DECIMAL:
        text = java_strip(value)
        try:
            if not _DECIMAL.fullmatch(text):
                raise InvalidOperation(text)
            number = Decimal(text)
        except InvalidOperation as error:
            raise TransformError(f"소수가 아닙니다: {value!r}") from error
        # BigDecimal에는 -0이 없다('-0' → '0', '-0.00' → '0.00'). toString과 str(Decimal)은 같은 표기 규칙이다
        return str(number.copy_abs() if number.is_zero() else number)
    if step.op == TransformOp.TO_DATE:
        text = java_strip(value)
        for pattern, fmt in _DATES:
            if not pattern.fullmatch(text):
                continue
            try:
                return datetime.strptime(text, fmt).date().isoformat()
            except ValueError:
                continue  # STRICT: 2026-02-30 같은 없는 날짜는 실패
        raise TransformError(f"날짜 형식이 아닙니다: {value!r}")
    if step.op == TransformOp.MAP_VALUE:
        if value not in step.arg:
            raise TransformError(f"대응표에 없는 값입니다: {value!r}")
        return step.arg[value]
    raise ValueError(f"지원하지 않는 변환입니다: {step.op}")


def apply_steps(steps: list[TransformStep], value: str | None) -> str | None:
    """None은 변환하지 않는다(빈 값을 다른 값과 잇지 않게). 한 단계라도 실패하면 TransformError."""
    if value is None:
        return None
    for step in steps:
        value = apply_step(step, value)
    return value


_LABELS = {
    TransformOp.TRIM: "앞뒤 공백 제거",
    TransformOp.UPPER: "대문자로",
    TransformOp.EXTRACT_NUMBER: "숫자만 남기기",
    TransformOp.TO_INTEGER: "정수로",
    TransformOp.TO_DECIMAL: "소수로",
    TransformOp.TO_DATE: "날짜로",
}


def describe_steps(steps: list[TransformStep]) -> str:
    """사람이 읽는 설명. 예: 'W' 빼기 → 3자리로 0 채우기 → 'E' 붙이기"""
    parts = []
    for step in steps:
        if step.op in _LABELS:
            parts.append(_LABELS[step.op])
        elif step.op == TransformOp.STRIP_PREFIX:
            parts.append(f"앞의 '{step.arg}' 빼기")
        elif step.op == TransformOp.ADD_PREFIX:
            parts.append(f"앞에 '{step.arg}' 붙이기")
        elif step.op == TransformOp.PAD_ZEROS:
            parts.append(f"{step.arg}자리로 0 채우기")
        elif step.op == TransformOp.MAP_VALUE:
            parts.append(f"대응표로 바꾸기({len(step.arg)}개)")
    return " → ".join(parts) if parts else "그대로"
