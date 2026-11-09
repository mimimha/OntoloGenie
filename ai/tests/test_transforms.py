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
