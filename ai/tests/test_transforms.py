import pytest
from pydantic import ValidationError

from app.rules.transforms import (
    TransformError,
    TransformOp,
    TransformStep,
    apply_steps,
    describe_steps,
)


def _steps(*pairs):
    return [TransformStep(op=op, arg=arg) for op, arg in pairs]


ERD_EXAMPLE = _steps(
    (TransformOp.STRIP_PREFIX, "W"),
    (TransformOp.PAD_ZEROS, 3),
    (TransformOp.ADD_PREFIX, "E"),
)


def test_erd_example_worker_to_employee():
    assert apply_steps(ERD_EXAMPLE, "W11") == "E011"
    assert apply_steps(ERD_EXAMPLE, "W23") == "E023"


def test_missing_value_stays_missing_and_prefix_only_stripped_when_present():
    assert apply_steps(ERD_EXAMPLE, None) is None
    assert (
        apply_steps(
            _steps(
                (TransformOp.STRIP_PREFIX, "W"),
            ),
            "31",
        )
        == "31"
    )
    assert (
        apply_steps(
            _steps(
                (TransformOp.PAD_ZEROS, 4),
            ),
            "P1O3",
        )
        == "P1O3"
    )


def test_trim_and_case():
    assert (
        apply_steps(
            _steps((TransformOp.TRIM, None), (TransformOp.UPPER, None)), "  p103 "
        )
        == "P103"
    )


def test_step_argument_validation():
    with pytest.raises(ValidationError):
        TransformStep(op=TransformOp.PAD_ZEROS, arg="3")
    with pytest.raises(ValidationError):
        TransformStep(op=TransformOp.STRIP_PREFIX)
    with pytest.raises(ValidationError):
        TransformStep(op=TransformOp.UPPER, arg="x")
    # 허용 목록(FN-MAP-05) 밖의 변환은 받지 않는다
    for op, arg in (("SUBSTRING", "1"), ("LOWER", None), ("REMOVE_CHARS", "-")):
        with pytest.raises(ValidationError):
            TransformStep(op=op, arg=arg)


def test_value_conversions_from_mapping_list():
    assert (
        apply_steps(
            _steps(
                (TransformOp.EXTRACT_NUMBER, None),
            ),
            "600톤",
        )
        == "600"
    )
    assert (
        apply_steps(
            _steps(
                (TransformOp.TO_INTEGER, None),
            ),
            " 011 ",
        )
        == "11"
    )
    assert (
        apply_steps(
            _steps(
                (TransformOp.TO_DECIMAL, None),
            ),
            "1.5",
        )
        == "1.5"
    )
    assert (
        apply_steps(
            _steps(
                (TransformOp.TO_DATE, None),
            ),
            "2026.08.15",
        )
        == "2026-08-15"
    )
    assert (
        apply_steps(
            _steps(
                (TransformOp.MAP_VALUE, {"정비": "MAINTENANCE"}),
            ),
            "정비",
        )
        == "MAINTENANCE"
    )


def test_failed_conversion_is_reported():
    with pytest.raises(TransformError):
        apply_steps(
            _steps(
                (TransformOp.TO_DATE, None),
            ),
            "8/14 야간",
        )
    with pytest.raises(TransformError):
        apply_steps(
            _steps(
                (TransformOp.MAP_VALUE, {"정비": "MAINTENANCE"}),
            ),
            "고장",
        )
    with pytest.raises(TransformError):
        apply_steps(
            _steps(
                (TransformOp.EXTRACT_NUMBER, None),
            ),
            "없음",
        )


def test_describe_in_plain_korean():
    assert (
        describe_steps(ERD_EXAMPLE)
        == "앞의 'W' 빼기 → 3자리로 0 채우기 → 앞에 'E' 붙이기"
    )
    assert describe_steps([]) == "그대로"


def test_same_results_as_backend_value_transformer():
    """백엔드 ValueTransformer.java와 같게: BigInteger·String.strip()·Character.isDigit(Nd만)."""
    to_int = _steps((TransformOp.TO_INTEGER, None))
    assert apply_steps(to_int, " +007 ") == "7"
    assert apply_steps(to_int, "-12") == "-12"
    for value in ("1_000", "1.0", "12a", "", "+"):
        with pytest.raises(TransformError):
            apply_steps(to_int, value)
    trim = _steps((TransformOp.TRIM, None))
    # 줄바꿈 없는 공백은 Java strip()이 지우지 않는다
    assert apply_steps(trim, " A01 ") == " A01 "
    assert apply_steps(trim, "\t A01 \n") == "A01"
    with pytest.raises(TransformError):
        apply_steps(to_int, " 12")
    digits = _steps((TransformOp.EXTRACT_NUMBER, None))
    assert apply_steps(digits, "①-12") == "12"
    with pytest.raises(TransformError):
        apply_steps(digits, "①②")
    pad = _steps((TransformOp.PAD_ZEROS, 4))
    assert apply_steps(pad, "²") == "²"
    assert apply_steps(pad, "12") == "0012"


def test_decimal_and_date_match_backend_big_decimal_and_strict_formatter():
    """Java new BigDecimal(value.strip()).toString(), DateTimeFormatter uuuu-MM-dd 등 STRICT."""
    to_dec = _steps((TransformOp.TO_DECIMAL, None))
    assert apply_steps(to_dec, " 1.50 ") == "1.50"
    assert apply_steps(to_dec, "-0") == "0"
    assert apply_steps(to_dec, "-0.00") == "0.00"
    assert apply_steps(to_dec, "+1e3") == "1E+3"
    for value in ("1_000", "NaN", "Infinity", "-Infinity", " 1.5", "1 5", "."):
        with pytest.raises(TransformError):
            apply_steps(to_dec, value)
    to_date = _steps((TransformOp.TO_DATE, None))
    assert apply_steps(to_date, "2026.01.05") == "2026-01-05"
    assert apply_steps(to_date, "20260105") == "2026-01-05"
    for value in ("2026-1-5", "2026/1/05", "2026-02-30", "26-01-05", " 2026-01-05"):
        with pytest.raises(TransformError):
            apply_steps(to_date, value)
