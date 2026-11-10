"""평가 데이터 점수표 명령 (app.validators.scorecard)."""

from app.validators.scorecard import main


def test_scorecard_prints_rows_and_bands(capsys):
    assert main(["--data", "b"]) == 0
    out = capsys.readouterr().out
    assert "B    찾은 관계 6/6" in out
    assert "점수 구간별 실제 정답 비율" in out
