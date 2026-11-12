from app.llm.privacy import mask_text


def test_names_with_titles_phones_and_emails_are_masked():
    masked = mask_text(
        "김 대리에게 연락했고 박민수 반장님 확인 완료. 010-1234-5678, kim@example.com"
    )
    assert masked.text == "*** 대리에게 연락했고 *** 반장님 확인 완료. ***, ***"
    assert sorted(masked.kinds) == ["EMAIL", "NAME", "NAME", "PHONE"]


def test_equipment_and_order_codes_stay():
    text = "새벽 3시쯤 3호기 슬라이드 유압 누유로 정지. RP-26-041 정비 진행 중. 작성 57"
    assert mask_text(text).text == text
    assert mask_text(text).kinds == []
    assert (
        mask_text("정비 기사 호출, 성형 반장 교대").text
        == "정비 기사 호출, 성형 반장 교대"
    )


def test_korean_particles_right_after_numbers_and_emails():
    masked = mask_text(
        "010-1234-5678로 연락, kim@example.com으로 보냄, 900101-1234567은 지움"
    )
    assert "1234" not in masked.text and "example" not in masked.text
    assert sorted(masked.kinds) == ["EMAIL", "PHONE", "RRN"]


def test_name_in_assignee_column_is_masked_but_codes_and_words_are_not():
    # 담당 부서 칸에 섞인 사람 이름은 LLM 질문·근거에 그대로 쓰지 않는다
    from app.llm.privacy import mask_value

    assert mask_value("홍보람", "technician 담당 부서") == "***"
    assert mask_value("보전팀", "technician 담당 부서") == "보전팀"
    # 사람 칸이 아니면 상태·지역·상호는 그대로(LLM이 표 종류를 판단하는 데 필요)
    assert mask_value("진행", "status") == "진행"
    assert mask_value("서울", "region") == "서울"
    assert mask_value("백두상사", "supplierName") == "백두상사"


def test_phone_formats_and_honorific_names_are_masked():
    text = (
        "010.1234.5678 / 010 1234 5678 / +82-10-1234-5678 / 900101-1234567 / "
        "홍길동님께 전달, 김철수 책임·이영희 선임·박 매니저 확인"
    )
    masked = mask_text(text).text
    for secret in (
        "1234",
        "5678",
        "900101",
        "홍길동",
        "김철수",
        "이영희",
        "박 ",
    ):
        assert secret not in masked, secret
    assert "님께 전달" in masked and "책임" in masked and "매니저 확인" in masked


def test_thirteen_digit_numbers_without_hyphen_are_not_masked():
    # 하이픈 없는 13자리는 바코드·운송장 번호와 구별할 수 없다(EAN-13 8801234567890은 생년월일 모양)
    for code in ("8801234567890", "9001011234567", "운송장 6012345678901"):
        assert mask_text(code).text == code
    # 하이픈이 있는 주민번호는 그대로 가린다
    assert mask_text("주민번호 900101-1234567 확인").text == "주민번호 *** 확인"


def test_honorific_after_job_words_is_not_a_name():
    # '~님' 앞말이 사·팀·장으로 끝나거나 업무 낱말이면 사람 이름이 아니다
    text = "정비사님 호출, 고객사님 요청, 정비팀님 확인, 고장님 아님, 이상님 없음"
    assert mask_text(text).text == text
    # 사람 이름 + 님은 가린다
    assert mask_text("홍길동님께, 길동님 확인").text == "***님께, ***님 확인"


def test_department_words_before_titles_are_not_names():
    text = (
        "제조 책임 확인, 원가 매니저 보고, 회계 책임, 설계 선임, 운영 매니저, 개발 책임"
    )
    assert mask_text(text).text == text
    assert mask_text("김철수 책임, 이 매니저").text == "*** 책임, *** 매니저"


def test_numbers_inside_codes_are_not_phones_or_rrn():
    # 영문·하이픈 뒤에 붙은 번호는 코드의 일부다
    for code in (
        "SN01012345678",
        "WO-010-1234-5678",
        "LOT900101-1234567",
        "A-02-123-4567",
    ):
        assert mask_text(code).text == code, code
    # 앞에 공백·한글·문장부호가 오면 전화번호다
    assert (
        mask_text("연락 010-1234-5678, 전화:01012345678").text == "연락 ***, 전화:***"
    )


def test_titles_and_codes_are_not_mistaken_for_names_or_phones():
    text = (
        "고객님 요청, 기사님 호출, 손님 대기, 차장님 보고, 반장님 확인. 최종 책임은 품질팀. "
        "P-101 정지, LOT-20260101-0001, LT261024-1, JB2608-0103 잔량 1,100개, "
        "실적 1234567890123건, 측정 20261029 08:00"
    )
    assert mask_text(text).text == text


def test_data_v1_notes_and_comments_are_not_changed_by_masking():
    import csv
    import json
    from pathlib import Path

    root = Path(__file__).resolve().parents[1] / "data" / "data-v1.0"
    texts = []
    for name in ("mes/handover_note.csv", "mes/maintenance_order.csv"):
        with (root / name).open(encoding="utf-8") as f:
            texts += [v for row in csv.DictReader(f) for v in row.values() if v]
    for schema in root.glob("*/_schema.json"):
        for table in json.loads(schema.read_text(encoding="utf-8"))["tables"].values():
            texts.append(table.get("description") or "")
            texts += [c.get("comment") or "" for c in table["columns"].values()]
    assert texts
    assert [t for t in texts if mask_text(t).text != t] == []


def test_keep_length_mask_keeps_positions():
    from app.llm.privacy import mask_keep_length

    text = "김철수 책임 010-1234-5678로, kim@example.com 회신"
    masked = mask_keep_length(text)
    assert len(masked) == len(text)
    assert masked == "*** 책임 *************로, *************** 회신"
