"""숫자만 우연히 겹친 연결(수량·등급·줄 번호 ↔ 순번 키)을 내지 않는지, 제안 이유를 읽는 말로 쓰는지."""

from test_links_general import rules, write_table


def _customers(root, n=50):
    write_table(
        root,
        "scm",
        "customer",
        ["customerId"],
        {
            "customerId": ("integer", list(range(1, n + 1))),
            "name": ("varchar(20)", [f"고객사{i}" for i in range(1, n + 1)]),
        },
    )


def test_small_grade_column_is_not_linked_to_sequence_key(tmp_path):
    _customers(tmp_path)
    rows = 200
    write_table(
        tmp_path,
        "scm",
        "shipment",
        ["shipNo"],
        {
            "shipNo": ("varchar(8)", [f"SH{i:04d}" for i in range(rows)]),
            # 이름에 단서가 없지만 고객 번호 전체에 고루 닿는 진짜 참조
            "consignee": ("integer", [i % 50 + 1 for i in range(rows)]),
            # 등급 1~5: 고객 번호 일부에만 닿는 우연
            "qualityGrade": ("integer", [i % 5 + 1 for i in range(rows)]),
            "boxCnt": ("integer", [i % 40 + 1 for i in range(rows)]),
        },
    )
    found = rules(tmp_path)
    assert ("scm.shipment.consignee", "scm.customer.customerId") in found
    assert ("scm.shipment.qualityGrade", "scm.customer.customerId") not in found
    assert ("scm.shipment.boxCnt", "scm.customer.customerId") not in found


def test_name_text_and_same_table_numbers_are_not_linked(tmp_path):
    write_table(
        tmp_path,
        "erp",
        "customer",
        ["cust_cd"],
        {
            "cust_cd": ("varchar(4)", [f"C{i:02d}" for i in range(1, 31)]),
            # 이름 글에 든 번호가 코드 번호와 같을 뿐이다
            "cust_name": ("varchar(20)", [f"거래처 {i:02d}" for i in range(1, 31)]),
        },
    )
    found = rules(tmp_path)
    assert not [k for k in found if k[0] == "erp.customer.cust_name"]


def test_weaker_composite_with_same_first_column_is_dropped(tmp_path):
    # 홀수 주문은 줄 10만, 짝수 주문은 10·20이 있다
    lines = [
        (f"SO{o:03d}", p)
        for o in range(1, 21)
        for p in ((10, 20) if o % 2 == 0 else (10,))
    ]
    write_table(
        tmp_path,
        "sap",
        "VBAP",
        ["VBELN", "POSNR"],
        {
            "VBELN": ("varchar(5)", [o for o, _ in lines]),
            "POSNR": ("integer", [p for _, p in lines]),
        },
    )
    deliveries = lines * 2
    write_table(
        tmp_path,
        "sap",
        "LIPS",
        ["DLV", "POSNR"],
        {
            "DLV": ("varchar(5)", [f"D{i:03d}" for i in range(len(deliveries))]),
            # 출하 자기 줄 번호: 대부분 참조 줄 번호와 같지만 몇 줄은 다르다(참조 + 자기 줄 번호는 약 94%)
            "POSNR": (
                "integer",
                [20 if i % 17 == 1 else p for i, (_, p) in enumerate(deliveries)],
            ),
            "VGBEL": ("varchar(5)", [o for o, _ in deliveries]),
            "VGPOS": ("integer", [p for _, p in deliveries]),
        },
    )
    composites = [
        k for k in rules(tmp_path) if "+" in k[0] and k[1].startswith("sap.VBAP")
    ]
    assert composites == [("sap.LIPS.VGBEL+VGPOS", "sap.VBAP.VBELN+POSNR")]


def test_partly_matching_values_are_held_not_pending(tmp_path):
    plants = ["1000", "1100", "1200", "2000", "2100"]
    write_table(
        tmp_path,
        "sap",
        "T001W",
        ["WERKS"],
        {"WERKS": ("varchar(4)", plants), "NAME1": ("varchar(10)", list("ABCDE"))},
    )
    widths = [
        "1000",
        "1100",
        "1200",
        "1250",
        "1524",
    ] * 20  # 폭 mm: 공장 코드와 70% 겹칠 뿐
    write_table(
        tmp_path,
        "l2",
        "coil",
        ["coil_id"],
        {
            "coil_id": ("varchar(6)", [f"K{i:05d}" for i in range(len(widths))]),
            "width": ("varchar(4)", widths),
        },
    )
    rel = rules(tmp_path).get(("l2.coil.width", "sap.T001W.WERKS"))
    assert rel is None or rel.review_status == "HELD"


def test_transform_reason_is_plain_korean(tmp_path):
    write_table(
        tmp_path,
        "erp",
        "employee",
        ["emp_no"],
        {"emp_no": ("varchar(6)", [f"E{i:05d}" for i in range(1, 41)])},
    )
    write_table(
        tmp_path,
        "mes",
        "work_log",
        ["log_id"],
        {
            "log_id": ("varchar(5)", [f"L{i:04d}" for i in range(120)]),
            "emp_ref": ("varchar(6)", [f"E-{i % 40 + 1}" for i in range(120)]),
        },
    )
    rel = rules(tmp_path)[("mes.work_log.emp_ref", "erp.employee.emp_no")]
    # 변환 이름(EXTRACT_NUMBER 같은 코드 값)이 아니라 사람이 읽는 말과 실제 값 예로 설명한다
    assert "EXTRACT_NUMBER" not in rel.reason and "PAD_ZEROS" not in rel.reason
    assert "예: 'E-" in rel.reason


def test_column_matching_several_sequence_keys_picks_the_tightest(tmp_path):
    # 담당자 번호 1~18은 기술자(18명)·설비(40대)·부품 사용(500건) 번호 모두에 들어간다.
    # 기술자 번호를 거의 다 쓰는 쪽이 진짜 참조다
    for name, n in (("technician", 18), ("asset", 40), ("partUsage", 500)):
        write_table(
            tmp_path,
            "cmms",
            name,
            [f"{name}Id"],
            {f"{name}Id": ("integer", list(range(1, n + 1)))},
        )
    write_table(
        tmp_path,
        "cmms",
        "request",
        ["requestNo"],
        {
            "requestNo": ("varchar(6)", [f"MR{i:04d}" for i in range(300)]),
            "assignedTo": ("integer", [i % 18 + 1 for i in range(300)]),
        },
    )
    targets = [k[1] for k in rules(tmp_path) if k[0] == "cmms.request.assignedTo"]
    assert targets == ["cmms.technician.technicianId"]


def test_numbers_with_separator_link_after_keeping_digits(tmp_path):
    # 보전 작업자 인사 번호 '19-034'는 숫자만 남기고 앞에 '20'을 붙이면 ERP 사번 '2019034'
    write_table(
        tmp_path,
        "erp",
        "employee",
        ["empNo"],
        {
            "empNo": (
                "varchar(7)",
                [f"20{y}{n:03d}" for y in (18, 19, 20) for n in range(1, 21)],
            )
        },
    )
    write_table(
        tmp_path,
        "cmms",
        "technician",
        ["techId"],
        {
            "techId": ("varchar(4)", [f"T{i:03d}" for i in range(30)]),
            "empRef": (
                "varchar(6)",
                [f"{18 + i % 3}-{i // 3 + 1:03d}" for i in range(30)],
            ),
        },
    )
    rel = rules(tmp_path)[("cmms.technician.empRef", "erp.employee.empNo")]
    assert rel.review_status == "HELD"


def test_transformed_link_is_not_pushed_out_by_raw_sequence_overlap(tmp_path):
    # 품목 번호 1001~은 검사 번호 1~2000에도 그대로 들어가지만, 진짜 참조는 0 채우고 P를 붙인 품목 코드다
    write_table(
        tmp_path,
        "erp",
        "item",
        ["itemCode"],
        {"itemCode": ("varchar(7)", [f"P00{1000 + i}" for i in range(1, 41)])},
    )
    write_table(
        tmp_path,
        "mes",
        "inspection",
        ["INSP_ID"],
        {"INSP_ID": ("integer", list(range(1, 2001)))},
    )
    # 가끔 품목 마스터에 없는 번호가 섞인다(서로 다른 값 기준은 낮고 행 기준은 높다)
    numbers = [1001 + i % 30 for i in range(400)] + [1100 + i for i in range(15)]
    write_table(
        tmp_path,
        "mes",
        "lot",
        ["LOT_NO"],
        {
            "LOT_NO": ("varchar(6)", [f"L{i:05d}" for i in range(len(numbers))]),
            "ITM_NO": ("integer", numbers),
        },
    )
    targets = [k[1] for k in rules(tmp_path) if k[0] == "mes.lot.ITM_NO"]
    assert targets == ["erp.item.itemCode"]


def test_codes_with_other_letters_matched_only_by_number_are_held(tmp_path):
    # 불량 코드 S001·P001·C001 중 C001~만 거래처 C001과 글자까지 같고 나머지는 숫자만 같다
    defects = [f"{k}{i:03d}" for k in "SCP" for i in range(1, 9)]
    write_table(
        tmp_path,
        "mes",
        "defect_cd",
        ["dft_cd"],
        {
            "dft_cd": ("varchar(4)", defects),
            "dft_nm": ("varchar(10)", [f"불량{i}" for i in range(len(defects))]),
        },
    )
    write_table(
        tmp_path,
        "erp",
        "vendor",
        ["vendor_cd"],
        {
            "vendor_cd": ("varchar(4)", [f"C{i:03d}" for i in range(1, 41)]),
            "vendor_nm": ("varchar(10)", [f"거래처{i}" for i in range(1, 41)]),
        },
    )
    found = rules(tmp_path)
    rule = found[("mes.defect_cd.dft_cd", "erp.vendor.vendor_cd")]
    assert rule.review_status == "HELD"
    assert rule.hold_reason.startswith("글자가 다른 값까지 숫자만 남겨")


def test_few_number_values_are_dropped_and_dated_document_numbers_are_held(tmp_path):
    # 처방 버전(1·2) ↔ 칭량 ID 같은 순번 키는 내지 않고, 숫자만 같은 연도·순번 문서 번호는 보류
    write_table(
        tmp_path,
        "erp",
        "weighing",
        ["weigh_id"],
        {
            "weigh_id": ("integer", list(range(1, 81))),
            "recipe_ver": ("integer", [1 + i % 2 for i in range(80)]),
            "wo_no": ("varchar(12)", [f"WO-25-{i:04d}" for i in range(1, 81)]),
        },
    )
    write_table(
        tmp_path,
        "qms",
        "insp",
        ["insp_no"],
        {
            "insp_no": ("varchar(12)", [f"QI-25-{i:05d}" for i in range(1, 81)]),
            "result": ("varchar(4)", ["OK"] * 80),
        },
    )
    found = rules(tmp_path)
    assert ("erp.weighing.recipe_ver", "erp.weighing.weigh_id") not in found
    # 연도·순번 문서 번호는 진짜 참조일 수도 있어 빼지 않고 보류로 보인다
    rule = found[("erp.weighing.wo_no", "qms.insp.insp_no")]
    assert rule.review_status == "HELD"
    assert rule.hold_reason.startswith("연도·순번 문서 번호가 숫자만 같음")


def test_few_codes_touching_small_part_of_master_are_held(tmp_path):
    # 판매 조직(1000·1100·2000)이 플랜트 코드 60개 중 일부와 글자까지 같지만 다른 코드다
    write_table(
        tmp_path,
        "sap",
        "plant",
        ["werks"],
        {"werks": ("varchar(4)", [f"{1000 + i * 100}" for i in range(60)])},
    )
    write_table(
        tmp_path,
        "sap",
        "sales_order",
        ["vbeln"],
        {
            "vbeln": ("varchar(10)", [f"00500{i:05d}" for i in range(120)]),
            "vkorg": (
                "varchar(4)",
                [["1000", "1100", "2000"][i % 3] for i in range(120)],
            ),
        },
    )
    rule = rules(tmp_path)[("sap.sales_order.vkorg", "sap.plant.werks")]
    assert rule.review_status == "HELD"
    assert rule.hold_reason.startswith("코드 값 3개가 도착 값 60개 중 일부에만")


def test_few_number_values_with_matching_column_name_are_held_not_dropped(tmp_path):
    # 값이 1·2뿐이어도 컬럼 이름이 이어지면(plant_id → plant.plant_id) 진짜 참조일 수 있어 보류로 보인다
    write_table(
        tmp_path,
        "mes",
        "plant",
        ["plant_id"],
        {"plant_id": ("integer", [1, 2, 3, 4, 5])},
    )
    write_table(
        tmp_path,
        "mes",
        "line",
        ["line_id"],
        {
            "line_id": ("integer", list(range(101, 141))),
            "plant_id": ("integer", [1 + i % 2 for i in range(40)]),
        },
    )
    rule = rules(tmp_path)[("mes.line.plant_id", "mes.plant.plant_id")]
    assert rule.review_status == "HELD"
