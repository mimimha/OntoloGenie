# B8 PCB 표면실장(SMT) 조립 공장 평가 데이터 생성기 (고정 시드)
# 시스템: erp(한글 컬럼), mes(대문자 약어), cmms(camelCase 설비보전)
import csv
import datetime as dt
import json
import os
import random

SEED = 20261108
rng = random.Random(SEED)
# 저장소 위치: ai/data/eval/b8/gen.py → 데이터는 ai/data/eval/b8/data
HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "data")
SCHEMA = {}


def table(system, name, desc, pk, cols, rows):
    """cols: list of (name, type, comment)"""
    SCHEMA.setdefault(system, {"tables": {}})
    SCHEMA[system]["tables"][name] = {
        "description": desc,
        "primary_key": pk,
        "columns": {c: {"type": t, "comment": cm} for c, t, cm in cols},
    }
    d = os.path.join(DATA, system)
    os.makedirs(d, exist_ok=True)
    header = [c for c, _, _ in cols]
    with open(os.path.join(d, name + ".csv"), "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(header)
        for r in rows:
            w.writerow(["" if r.get(c) is None else str(r[c]) for c in header])


def rdate(a, b):
    return a + dt.timedelta(days=rng.randint(0, (b - a).days))


def p(x):
    return rng.random() < x


SUR = list("김이박최정강조윤장임한오서신권황안송류홍")
GIV = list("민서지현준수영희진호우연은성태")


def masked():
    return rng.choice(SUR) + "*" + rng.choice(GIV)


D0, D1 = dt.date(2026, 4, 1), dt.date(2026, 10, 25)

# ======================= ERP =======================
# 거래처: C001~C015 고객, C016~C040 공급
partners = []
for i in range(1, 41):
    cust = i <= 15
    partners.append({
        "거래처코드": f"C{i:03d}",
        "거래처명": (f"고객사{i:02d}" if cust else f"공급사{i - 15:02d}"),
        "구분": "고객" if cust else "공급",
        "사업자번호": f"{rng.randint(101, 899)}-{rng.randint(10, 99)}-{rng.randint(10000, 99999)}",
        "지역": rng.choice(["경기", "충남", "경북", "서울", "인천", "경남"]),
        "담당자": None if p(0.08) else masked(),
    })
CUST = [r["거래처코드"] for r in partners[:15]]
SUPP = [r["거래처코드"] for r in partners[15:]]
table("erp", "거래처", "고객·공급 거래처", ["거래처코드"], [
    ("거래처코드", "VARCHAR(4)", ""), ("거래처명", "VARCHAR(40)", ""), ("구분", "VARCHAR(4)", "고객/공급"),
    ("사업자번호", "VARCHAR(12)", ""), ("지역", "VARCHAR(10)", ""), ("담당자", "VARCHAR(20)", ""),
], partners)

# 품목: 완제품 P001001~, 부품 P002001~, 설비 소모품 P009001~
items = []
FG_KIND = ["메인보드", "전원보드", "통신모듈", "디스플레이보드", "센서보드"]
for i in range(20):
    items.append({"품목코드": f"P{1001 + i:06d}", "품목명": f"{FG_KIND[i % 5]} 모델{i + 1:02d}",
                  "품목구분": "완제품", "단위": "EA", "표준단가": rng.randint(8, 95) * 1000,
                  "릴수량": None, "층수": rng.choice([2, 4, 4, 6, 8]), "주공급사": None})
COMP_KIND = [("칩저항", ["0402", "0603", "1005"], ["10K", "4.7K", "100R", "1M", "22R"]),
             ("MLCC", ["0402", "0603", "1608"], ["100nF", "10uF", "1uF", "22pF"]),
             ("MCU", ["QFP64", "LQFP100", "QFN48"], ["32bit", "16bit"]),
             ("다이오드", ["SOD123", "SMA"], ["쇼트키", "TVS", "정류"]),
             ("트랜지스터", ["SOT23", "SOT223"], ["NPN", "PNP", "MOSFET"]),
             ("커넥터", ["2.54mm", "1.25mm"], ["4핀", "10핀", "20핀"]),
             ("LED", ["0603", "3528"], ["적색", "녹색", "백색"])]
seen = set()
while len(seen) < 45:
    k, pk, sp = rng.choice(COMP_KIND)
    seen.add(f"{k} {rng.choice(pk)} {rng.choice(sp)}")
for i, nm in enumerate(sorted(seen)):
    items.append({"품목코드": f"P{2001 + i:06d}", "품목명": nm, "품목구분": "부품", "단위": "EA",
                  "표준단가": rng.randint(1, 900) * 10, "릴수량": rng.choice([1000, 2000, 3000, 4000, 5000, 10000]),
                  "층수": None, "주공급사": rng.choice(SUPP)})
MRO = ["흡착노즐 CN030", "흡착노즐 CN040", "흡착노즐 CN065", "피더 8mm", "피더 12mm", "피더 16mm",
       "스퀴지 블레이드 300mm", "메탈마스크 클리닝지", "리플로우 컨베이어 체인", "AOI 조명 모듈",
       "진공 필터", "노즐 O링", "피더 스프링", "히터 엘리먼트", "타이밍 벨트"]
for i, nm in enumerate(MRO):
    items.append({"품목코드": f"P{9001 + i:06d}", "품목명": nm, "품목구분": "소모품", "단위": "EA",
                  "표준단가": rng.randint(5, 400) * 100, "릴수량": None, "층수": None,
                  "주공급사": rng.choice(SUPP)})
items[50]["주공급사"] = "C044"  # 없는 공급사 1건
for r in items:
    r["사용여부"] = "N" if p(0.04) else "Y"
FG = [r["품목코드"] for r in items if r["품목구분"] == "완제품"]
COMP = [r for r in items if r["품목구분"] == "부품"]
SPARE = [r["품목코드"] for r in items if r["품목구분"] == "소모품"]
table("erp", "품목", "품목 마스터", ["품목코드"], [
    ("품목코드", "VARCHAR(7)", ""), ("품목명", "VARCHAR(60)", ""), ("품목구분", "VARCHAR(6)", "완제품/부품/소모품"),
    ("단위", "VARCHAR(4)", ""), ("표준단가", "INTEGER", "원"), ("릴수량", "INTEGER", "릴 1개당 부품 수"),
    ("층수", "INTEGER", "PCB 층수"), ("주공급사", "VARCHAR(4)", ""), ("사용여부", "CHAR(1)", ""),
], items)

# 사원
DEPTS = [("설비보전팀", 16), ("생산1팀", 10), ("생산2팀", 10), ("생산기술팀", 8), ("품질팀", 7), ("자재팀", 5), ("영업팀", 4)]
emps, used = [], set()
for dept, n in DEPTS:
    for _ in range(n):
        while True:
            y = rng.randint(2008, 2025); s = rng.randint(1, 120)
            if (y, s) not in used:
                used.add((y, s)); break
        rank = ["부장", "차장", "과장", "대리", "주임", "사원"][min(5, (y - 2008) // 3)]
        emps.append({"사번": f"{y}{s:03d}", "성명": masked(), "부서": dept, "직급": rank,
                     "입사일": dt.date(y, rng.randint(1, 12), rng.randint(1, 28)).isoformat(), "_y": y, "_s": s})
rng.shuffle(emps)
table("erp", "사원", "사원 마스터", ["사번"], [
    ("사번", "VARCHAR(7)", "입사연도+순번"), ("성명", "VARCHAR(20)", ""), ("부서", "VARCHAR(20)", ""),
    ("직급", "VARCHAR(6)", ""), ("입사일", "DATE", ""),
], emps)
PLANNERS = [e["사번"] for e in emps if e["부서"] in ("생산1팀", "생산2팀", "생산기술팀")]
MAINT = [e for e in emps if e["부서"] == "설비보전팀"]

# 수주 / 수주품목
orders = []
for _ in range(250):
    orders.append({"_d": rdate(D0, dt.date(2026, 10, 10))})
orders.sort(key=lambda r: r["_d"])
for i, o in enumerate(orders, 1):
    o["수주번호"] = f"SO-26-{i:04d}"
    o["거래처"] = None if p(0.01) else (f"C{rng.randint(41, 60):03d}" if p(0.02) else rng.choice(CUST))
    o["수주일자"] = o["_d"].strftime("%Y.%m.%d") if p(0.04) else o["_d"].isoformat()
    o["납기일"] = (o["_d"] + dt.timedelta(days=rng.randint(14, 45))).isoformat()
    o["상태"] = rng.choice(["확정", "확정", "생산중", "출하완료"])
table("erp", "수주", "수주 헤더", ["수주번호"], [
    ("수주번호", "VARCHAR(10)", ""), ("거래처", "VARCHAR(4)", "고객"), ("수주일자", "VARCHAR(10)", ""),
    ("납기일", "DATE", ""), ("상태", "VARCHAR(6)", ""),
], orders)
olines = []
for o in orders:
    k = rng.choices([1, 2, 3, 4], [25, 30, 25, 20])[0]
    for ln, it in enumerate(rng.sample(FG, k), 1):
        if p(0.02):
            it = f"P{rng.randint(1021, 1040):06d}"
        olines.append({"수주번호": o["수주번호"], "행번": ln, "품목코드": it,
                       "수량": rng.randint(2, 100) * 50, "단가": rng.randint(8, 95) * 1000, "_d": o["_d"]})
table("erp", "수주품목", "수주 품목 행", ["수주번호", "행번"], [
    ("수주번호", "VARCHAR(10)", ""), ("행번", "INTEGER", ""), ("품목코드", "VARCHAR(7)", ""),
    ("수량", "INTEGER", ""), ("단가", "INTEGER", ""),
], olines)

# 생산지시 (MO+YYMM+월별 순번4)
wos = []
for ln in olines:
    for _ in range(rng.choices([0, 1, 2], [10, 75, 15])[0]):
        wos.append({"수주번호": ln["수주번호"], "수주행번": ln["행번"], "품목코드": ln["품목코드"],
                    "_d": min(ln["_d"] + dt.timedelta(days=rng.randint(2, 10)), D1), "지시수량": ln["수량"]})
for _ in range(100):  # 재고 생산
    wos.append({"수주번호": None, "수주행번": None, "품목코드": rng.choice(FG),
                "_d": rdate(D0, D1), "지시수량": rng.randint(4, 60) * 50})
wos.sort(key=lambda r: r["_d"])
mseq = {}
for w in wos:
    ym = w["_d"].strftime("%y%m")
    mseq[ym] = mseq.get(ym, 0) + 1
    w["_ym"], w["_seq"] = ym, mseq[ym]
    w["지시번호"] = f"MO{ym}{mseq[ym]:04d}"
    w["계획시작일"] = w["_d"].isoformat()
    if w["수주번호"] and p(0.015):
        w["수주행번"] = 9  # 없는 수주 행
    w["담당자"] = None if p(0.03) else (f"20{rng.randint(10, 24)}{rng.randint(121, 199)}" if p(0.02) else rng.choice(PLANNERS))
    w["상태"] = "완료" if w["_d"] < dt.date(2026, 10, 10) else rng.choice(["지시", "진행"])
table("erp", "생산지시", "생산 작업지시", ["지시번호"], [
    ("지시번호", "VARCHAR(10)", ""), ("수주번호", "VARCHAR(10)", "재고 생산은 비움"), ("수주행번", "INTEGER", ""),
    ("품목코드", "VARCHAR(7)", ""), ("지시수량", "INTEGER", ""), ("계획시작일", "DATE", ""),
    ("담당자", "VARCHAR(7)", "생산 계획 담당"), ("상태", "VARCHAR(4)", ""),
], wos)

# ======================= MES =======================
lines = []
for i in range(1, 9):
    lines.append({"LINE_ID": i, "LINE_NM": f"SMT-{i:02d}", "FLR_NO": (i - 1) // 3 + 1,
                  "STD_CPH": rng.choice([42000, 55000, 60000, 78000]), "USE_YN": "N" if i == 8 else "Y"})
table("mes", "LINE_MST", "SMT 라인", ["LINE_ID"], [
    ("LINE_ID", "INTEGER", ""), ("LINE_NM", "VARCHAR(10)", ""), ("FLR_NO", "INTEGER", "층"),
    ("STD_CPH", "INTEGER", "표준 시간당 실장 수"), ("USE_YN", "CHAR(1)", ""),
], lines)

TPNM = {"PRT": "스크린프린터", "MNT": "마운터", "RFL": "리플로우"}
nums = rng.sample(range(3, 400), 32)
eqps, cnt = [], {}
for ln in range(1, 9):
    for tp in ["PRT", "MNT", "MNT", "RFL"]:
        cnt[tp] = cnt.get(tp, 0) + 1
        n = nums.pop()
        eqps.append({"EQP_CD": f"EQ{n:04d}", "EQP_NM": f"{TPNM[tp]} {cnt[tp]}호기", "EQP_TP": tp,
                     "LINE_ID": ln, "MKR_NM": rng.choice(["A사", "B사", "C사"]),
                     "INST_DT": rdate(dt.date(2015, 1, 1), dt.date(2024, 12, 31)).isoformat(),
                     "USE_YN": "N" if ln == 8 else "Y", "_n": n})
table("mes", "EQP_MST", "라인 설비", ["EQP_CD"], [
    ("EQP_CD", "VARCHAR(6)", ""), ("EQP_NM", "VARCHAR(30)", ""), ("EQP_TP", "CHAR(3)", "PRT/MNT/RFL"),
    ("LINE_ID", "INTEGER", ""), ("MKR_NM", "VARCHAR(20)", "제조사"), ("INST_DT", "DATE", "설치일"),
    ("USE_YN", "CHAR(1)", ""),
], eqps)
MNT_BY_LINE = {ln: [e["EQP_CD"] for e in eqps if e["LINE_ID"] == ln and e["EQP_TP"] == "MNT"] for ln in range(1, 9)}

defects = []
for grp, pre, nms in [("솔더", "S", ["브릿지", "냉납", "미납", "솔더볼", "과납", "기포", "젖음 불량", "솔더 튐"]),
                      ("부품", "C", ["미삽", "틀어짐", "역삽", "툼스톤", "부품 들뜸", "오삽", "파손", "리드 휨", "극성 오류", "이물"]),
                      ("기판", "P", ["기판 휨", "패턴 단선", "스크래치", "오염", "실크 불량", "홀 막힘"])]:
    for i, nm in enumerate(nms, 1):
        defects.append({"DFT_CD": f"{pre}{i:03d}", "DFT_NM": nm, "DFT_GRP": grp, "SEV_LVL": rng.randint(1, 3)})
table("mes", "DEFECT_CD", "불량 코드", ["DFT_CD"], [
    ("DFT_CD", "VARCHAR(4)", ""), ("DFT_NM", "VARCHAR(20)", ""), ("DFT_GRP", "VARCHAR(6)", ""),
    ("SEV_LVL", "INTEGER", "심각도"),
], defects)

lots = []
for w in wos:
    if w["상태"] == "지시":
        continue
    for _ in range(rng.choices([1, 2], [75, 25])[0]):
        st = dt.datetime.combine(w["_d"] + dt.timedelta(days=rng.randint(0, 3)), dt.time(rng.randint(6, 21), rng.choice([0, 15, 30, 45])))
        seq = w["_seq"] + 3000 if p(0.02) else w["_seq"]
        lots.append({"_st": st, "WO_YM": w["_ym"], "WO_SEQ": seq, "ITM_NO": int(w["품목코드"][1:]),
                     "LINE_ID": rng.randint(1, 7), "PNL_QTY": rng.randint(20, 400),
                     "ARRAY_CNT": rng.choice([2, 4, 6, 8, 10, 12])})
lots.sort(key=lambda r: r["_st"])
dseq = {}
for l in lots:
    d = l["_st"].strftime("%y%m%d")
    dseq[d] = dseq.get(d, 0) + 1
    l["LOT_NO"] = f"L{d}-{dseq[d]:04d}"
    l["STRT_DTM"] = l["_st"].strftime("%Y-%m-%d %H:%M:%S")
    run = l["_st"].date() > dt.date(2026, 10, 22) and p(0.5)
    l["END_DTM"] = None if run else (l["_st"] + dt.timedelta(minutes=rng.randint(40, 600))).strftime("%Y-%m-%d %H:%M:%S")
    l["LOT_STAT"] = "RUN" if run else "END"
table("mes", "PCB_LOT", "생산 로트(보드 묶음)", ["LOT_NO"], [
    ("LOT_NO", "VARCHAR(12)", ""), ("WO_YM", "CHAR(4)", "작지 연월"), ("WO_SEQ", "INTEGER", "작지 순번"),
    ("ITM_NO", "INTEGER", "제품 번호"), ("LINE_ID", "INTEGER", ""), ("PNL_QTY", "INTEGER", "패널 수"),
    ("ARRAY_CNT", "INTEGER", "패널당 보드 수"), ("STRT_DTM", "TIMESTAMP", ""), ("END_DTM", "TIMESTAMP", ""),
    ("LOT_STAT", "CHAR(3)", "RUN/END"),
], lots)


def bad_lot():
    return f"L26{rng.randint(4, 10):02d}{rng.randint(1, 28):02d}-{rng.randint(900, 999):04d}"


loads, reel_seq = [], 0
for l in lots:
    for _ in range(rng.choices([1, 2, 3], [25, 45, 30])[0]):
        c = rng.choice(COMP)
        reel_seq += 1
        eq = rng.choice(MNT_BY_LINE[l["LINE_ID"]])
        if p(0.01):
            eq = eq.lower()
        if p(0.03):
            reel = f"VR{rng.randint(10 ** 9, 10 ** 10 - 1)}"  # 공급사 라벨 그대로 스캔
        else:
            rym = (l["_st"] - dt.timedelta(days=rng.randint(3, 40))).strftime("%y%m")
            reel = f"RL-{rym}-{c['품목코드']}-{reel_seq % 10000:04d}"
        loads.append({"_t": l["_st"] + dt.timedelta(minutes=rng.randint(-30, 120)),
                      "LOT_NO": bad_lot() if p(0.01) else l["LOT_NO"], "EQP_CD": eq,
                      "FDR_SLOT": rng.randint(1, 60), "REEL_ID": reel,
                      "LOAD_QTY": rng.randint(1, c["릴수량"] // 100) * 100})
loads.sort(key=lambda r: r["_t"])
for i, r in enumerate(loads, 1):
    r["LOAD_SEQ"] = i
    r["LOAD_DTM"] = r["_t"].strftime("%Y-%m-%d %H:%M:%S")
table("mes", "MAT_LOAD", "피더 릴 장착 이력", ["LOAD_SEQ"], [
    ("LOAD_SEQ", "INTEGER", ""), ("LOT_NO", "VARCHAR(12)", ""), ("EQP_CD", "VARCHAR(6)", "마운터"),
    ("FDR_SLOT", "INTEGER", "피더 슬롯"), ("REEL_ID", "VARCHAR(24)", "릴 라벨"), ("LOAD_QTY", "INTEGER", ""),
    ("LOAD_DTM", "TIMESTAMP", ""),
], loads)

aoi = []
for _ in range(2000):
    l = rng.choice(lots)
    ng = p(0.25)
    dc = None
    if ng:
        dc = "S099" if p(0.01) else rng.choice(defects)["DFT_CD"]
    aoi.append({"_t": l["_st"] + dt.timedelta(minutes=rng.randint(30, 700)),
                "LOT_NO": bad_lot() if p(0.01) else l["LOT_NO"], "PNL_SEQ": rng.randint(1, min(50, l["PNL_QTY"])),
                "JDG": "NG" if ng else "OK", "DFT_CD": dc,
                "REF_DES": (rng.choice("CRUDLQ") + str(rng.randint(1, 150))) if ng else None})
aoi.sort(key=lambda r: r["_t"])
for i, r in enumerate(aoi, 1):
    r["INSP_ID"] = i
    r["INSP_DTM"] = r["_t"].strftime("%Y-%m-%d %H:%M:%S")
table("mes", "AOI_RSLT", "자동광학검사 결과", ["INSP_ID"], [
    ("INSP_ID", "INTEGER", ""), ("LOT_NO", "VARCHAR(12)", ""), ("PNL_SEQ", "INTEGER", "패널 순번"),
    ("JDG", "CHAR(2)", "OK/NG"), ("DFT_CD", "VARCHAR(4)", ""), ("REF_DES", "VARCHAR(6)", "부품 위치 기호"),
    ("INSP_DTM", "TIMESTAMP", ""),
], aoi)

# ======================= CMMS =======================
techs = []
for e in rng.sample(MAINT, 14):
    ref = f"{e['_y'] % 100:02d}-{e['_s']:03d}"
    if p(0.12):
        ref = ref.replace("-", "")
    techs.append({"techName": e["성명"], "empRef": ref, "vendor": None})
for i in range(4):
    techs.append({"techName": masked(), "empRef": None, "vendor": f"협력사{i % 2 + 1:02d}"})
rng.shuffle(techs)
for i, t in enumerate(techs, 1):
    t["techId"] = i
    t["skillGrade"] = rng.randint(1, 5)
    t["shift"] = rng.choice(["A", "B", "C"])
table("cmms", "technician", "보전 작업자", ["techId"], [
    ("techId", "INTEGER", ""), ("techName", "VARCHAR(20)", ""), ("empRef", "VARCHAR(7)", "인사 번호"),
    ("vendor", "VARCHAR(20)", "외주 소속"), ("skillGrade", "INTEGER", "기술 등급"), ("shift", "CHAR(1)", ""),
], techs)

assets = []
for e in eqps:
    ref = f"EQ-{e['_n']:04d}" if p(0.15) else f"eq-{e['_n']}"
    assets.append({"assetName": f"SMT-{e['LINE_ID']:02d} {TPNM[e['EQP_TP']]}", "equipRef": ref,
                   "category": "생산설비", "criticality": 1 if e["EQP_TP"] == "MNT" else 2})
for nm in ["공압 컴프레서 1", "공압 컴프레서 2", "칠러", "질소 발생기", "항온항습기 1", "항온항습기 2", "집진기", "변압기"]:
    assets.append({"assetName": nm, "equipRef": None, "category": "유틸리티", "criticality": 3})
rng.shuffle(assets)
for i, a in enumerate(assets, 1):
    a["assetId"] = i
    a["installYear"] = rng.randint(2015, 2024)
table("cmms", "asset", "보전 대상 자산", ["assetId"], [
    ("assetId", "INTEGER", ""), ("assetName", "VARCHAR(40)", ""), ("equipRef", "VARCHAR(8)", ""),
    ("category", "VARCHAR(10)", ""), ("criticality", "INTEGER", "1=높음"), ("installYear", "INTEGER", ""),
], assets)

FAIL = ["노즐 막힘", "피더 걸림", "흡착 에러", "컨베이어 정지", "온도 편차", "비전 인식 불량", "스퀴지 마모", "진공 저하", "센서 오류"]
reqs = []
for _ in range(300):
    reqs.append({"_t": dt.datetime.combine(rdate(D0, D1), dt.time(rng.randint(0, 23), rng.randint(0, 59)))})
reqs.sort(key=lambda r: r["_t"])
for i, r in enumerate(reqs, 1):
    r["requestNo"] = f"MR26{i:04d}"
    r["unit"] = rng.randint(41, 45) if p(0.01) else rng.choice([x["assetId"] for x in assets if x["category"] == "생산설비"] * 4 + [x["assetId"] for x in assets])
    r["reportedAt"] = r["_t"].strftime("%Y-%m-%dT%H:%M")
    r["failureType"] = rng.choice(FAIL)
    r["priority"] = rng.randint(1, 3)
    op = r["_t"].date() > dt.date(2026, 10, 15) and p(0.6)
    r["status"] = "OPEN" if op else "DONE"
    r["assignedTo"] = None if op and p(0.7) else rng.randint(1, len(techs))
    r["downtimeMin"] = None if op else rng.randint(5, 480)
table("cmms", "maintenanceRequest", "고장·보전 요청", ["requestNo"], [
    ("requestNo", "VARCHAR(8)", ""), ("unit", "INTEGER", ""), ("reportedAt", "VARCHAR(16)", ""),
    ("failureType", "VARCHAR(20)", ""), ("priority", "INTEGER", ""), ("status", "VARCHAR(4)", ""),
    ("assignedTo", "INTEGER", ""), ("downtimeMin", "INTEGER", "정지 시간(분)"),
], reqs)

done = [r for r in reqs if r["status"] == "DONE"]
usage = []
for i in range(1, 501):
    r = rng.choice(done)
    code = "MRO" + rng.choice(SPARE)[-4:]
    if p(0.02):
        code = f"MRO90{rng.randint(31, 60)}"
    elif p(0.05):
        code = code.lower()
    usage.append({"usageId": i, "requestNo": f"MR26{rng.randint(900, 999):04d}" if p(0.01) else r["requestNo"],
                  "partCode": code, "qty": rng.randint(1, 10),
                  "usedAt": (r["_t"] + dt.timedelta(minutes=rng.randint(10, 300))).strftime("%Y-%m-%dT%H:%M")})
table("cmms", "partUsage", "보전 부품 사용", ["usageId"], [
    ("usageId", "INTEGER", ""), ("requestNo", "VARCHAR(8)", ""), ("partCode", "VARCHAR(8)", "자재"),
    ("qty", "INTEGER", ""), ("usedAt", "VARCHAR(16)", ""),
], usage)

for sysn, sc in SCHEMA.items():
    with open(os.path.join(DATA, sysn, "_schema.json"), "w", encoding="utf-8") as f:
        json.dump(sc, f, ensure_ascii=False, indent=2)
print({s: {t: None for t in sc["tables"]} for s, sc in SCHEMA.items()})
