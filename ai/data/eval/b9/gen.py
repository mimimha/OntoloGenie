"""화장품 OEM/ODM 제조사 3개 원천 시스템(erp, wms, qms) 테스트 데이터 생성기.

고정 시드로 data/<system>/<table>.csv, data/<system>/_schema.json, answer.json 을 만든다.
"""
import csv
import json
import os
import random
from collections import defaultdict
from datetime import date, datetime, timedelta

random.seed(20251110)
BASE = os.path.dirname(os.path.abspath(__file__))
SCHEMA = {}


def fmt(v):
    if isinstance(v, datetime):
        return v.strftime('%Y-%m-%d %H:%M:%S')
    if isinstance(v, date):
        return v.isoformat()
    return str(v)


def put(system, table, desc, pk, cols, rows):
    d = os.path.join(BASE, 'data', system)
    os.makedirs(d, exist_ok=True)
    names = [c[0] for c in cols]
    with open(os.path.join(d, table + '.csv'), 'w', encoding='utf-8', newline='') as f:
        w = csv.writer(f)
        w.writerow(names)
        for r in rows:
            w.writerow(['' if r.get(n) is None else fmt(r.get(n)) for n in names])
    SCHEMA.setdefault(system, {})[table] = {
        'description': desc,
        'primary_key': pk,
        'columns': {c[0]: {'type': c[1], 'comment': c[2]} for c in cols},
    }


def rand_date(a, b):
    return a + timedelta(days=random.randint(0, (b - a).days))


def workday(d):
    while d.weekday() >= 5:
        d += timedelta(days=1)
    return d


def ean13(body12):
    s = sum(int(c) * (3 if i % 2 else 1) for i, c in enumerate(body12))
    return body12 + str((10 - s % 10) % 10)


# ---------------------------------------------------------------- erp.거래처
CUST_NAMES = ['라온코스메틱', '미소뷰티', '하늘앤코', '루미에르랩', '그린데이뷰티', '소이스킨', '온새미로',
              '베리굿코스', '달빛화장품', '에코플로라', '바른피부연구소', '모아뷰티', '클린하우스코리아',
              '도담코스', '하루한결']
RM_SUP = ['대한케미칼', '한빛소재', '성원바이오', '동방정밀화학', '새한원료', '유니켐코리아', '청정바이오텍',
          '미래향료', '삼일케미텍', '코리아실리콘', '세진천연물', '한국계면', '에이스파우더', '진성화학',
          '태평양소재상사', '우리추출물', '신영케미칼', '한결향료']
PK_SUP = ['대성용기', '한국튜브', '세림패키지', '미래캡', '동일인쇄', '에스엠펌프', '명진지기']
REGIONS = ['서울', '경기', '인천', '충북', '충남', '대전', '부산', '경북', '전북']
REPS = ['김민준', '이서윤', '박도윤', '최지우', '정하준', '강서아', '조은우', '윤지호', '장수아', '임건우',
        '한예린', '오시우', '서지안', '신유준', '권하린']

labels = (['고객사'] * len(CUST_NAMES)) + (['원료공급사'] * len(RM_SUP)) + (['포장재공급사'] * len(PK_SUP))
nums = sorted(random.sample(range(101, 999), len(labels)))
random.shuffle(labels)
name_pool = {'고객사': list(CUST_NAMES), '원료공급사': list(RM_SUP), '포장재공급사': list(PK_SUP)}
biz_used = set()
partners = []
for n, lab in zip(nums, labels):
    while True:
        biz = f"{random.randint(101, 899)}-{random.randint(10, 99)}-{random.randint(10000, 99999)}"
        if biz not in biz_used:
            biz_used.add(biz)
            break
    partners.append({
        '거래처코드': f"C{n:05d}", '거래처명': name_pool[lab].pop(0), '거래처구분': lab, '사업자번호': biz,
        '대표자명': random.choice(REPS), '지역': random.choice(REGIONS), '거래등급': random.randint(1, 5),
        '등록일자': rand_date(date(2016, 1, 1), date(2024, 12, 31)), '사용여부': 'Y', '_num': n,
    })
partners[random.randrange(len(partners))]['사용여부'] = 'N'
cust = [p for p in partners if p['거래처구분'] == '고객사']
rmsup = [p for p in partners if p['거래처구분'] == '원료공급사']
pksup = [p for p in partners if p['거래처구분'] == '포장재공급사']
P_BY_CODE = {p['거래처코드']: p for p in partners}

# ---------------------------------------------------------------- erp.품목
LINES = ['수분', '진정', '미백', '탄력', '장벽', '비타', '어성초', '시카', '히알루', '콜라겐', '녹차', '세라']
TYPES = [('토너', 150), ('에센스', 50), ('세럼', 30), ('크림', 50), ('로션', 120), ('선크림', 50),
         ('클렌징폼', 150), ('앰플', 30), ('미스트', 100), ('아이크림', 30)]
RM_NAMES = ['정제수', '글리세린', '부틸렌글라이콜', '나이아신아마이드', '소듐하이알루로네이트', '세라마이드엔피',
            '판테놀', '알란토인', '카보머', '트로메타민', '디메치콘', '카프릴릭/카프릭트리글리세라이드', '세틸알코올',
            '스테아릭애씨드', '토코페롤', '1,2-헥산다이올', '에틸헥실글리세린', '향료(플로럴)', '징크옥사이드',
            '티타늄디옥사이드', '마데카소사이드', '병풀추출물', '녹차추출물', '시어버터', '스쿠알란', '잔탄검',
            '다이소듐이디티에이', '아데노신', '알부틴', '레몬껍질오일', '폴리솔베이트60', '하이드롤라이즈드콜라겐']
COLD_RM = {'소듐하이알루로네이트', '세라마이드엔피', '향료(플로럴)', '하이드롤라이즈드콜라겐', '레몬껍질오일'}
PK_NAMES = ['PET 용기 150ml', '유리병 50ml', '펌프 디스펜서', '드롭퍼 캡', '튜브 50ml', '튜브 150ml', '원터치 캡',
            '단상자 A형', '단상자 B형', '라벨 스티커', '쿠션 케이스', '에어리스 용기 30ml', '스포이드', '수축 필름']

items = []
combos = random.sample([(l, t) for l in LINES for t in TYPES], 30)
fg_owner = (cust * 2)
random.shuffle(fg_owner)
comp_code = {c['거래처코드']: f"{random.randint(1000, 9999)}" for c in cust}
for i, ((line, (tp, vol)), owner) in enumerate(zip(combos, fg_owner)):
    items.append({
        '품목코드': f"FG-{1001 + i}", '품목명': f"{line} {tp} {vol}ml", '품목구분': '완제품', '규격': f"{vol}ml",
        '단위': 'EA', '표준단가': random.randrange(1800, 9500, 50),
        '바코드': ean13('880' + comp_code[owner['거래처코드']] + f"{random.randint(0, 99999):05d}"),
        '주거래처코드': owner['거래처코드'], '보관조건': '실온', '사용여부': 'Y', '_vol': vol,
    })
sup_cycle = rmsup + [random.choice(rmsup) for _ in range(len(RM_NAMES) - len(rmsup))]
random.shuffle(sup_cycle)
for i, (nm, sup) in enumerate(zip(RM_NAMES, sup_cycle)):
    items.append({
        '품목코드': f"RM-{2001 + i}", '품목명': nm, '품목구분': '원료', '규격': '-', '단위': 'KG',
        '표준단가': 600 if nm == '정제수' else random.randrange(3000, 250000, 100), '바코드': None,
        '주거래처코드': sup['거래처코드'], '보관조건': '냉장(2~8℃)' if nm in COLD_RM else '실온', '사용여부': 'Y',
    })
for i, nm in enumerate(PK_NAMES):
    items.append({
        '품목코드': f"PK-{3001 + i}", '품목명': nm, '품목구분': '포장재', '규격': '-', '단위': 'EA',
        '표준단가': random.randrange(40, 900, 10), '바코드': None,
        '주거래처코드': random.choice(pksup)['거래처코드'], '보관조건': '실온', '사용여부': 'Y',
    })
FG = [x for x in items if x['품목구분'] == '완제품']
RM = [x for x in items if x['품목구분'] == '원료']
PKI = [x for x in items if x['품목구분'] == '포장재']
ITEM = {x['품목코드']: x for x in items}

# ---------------------------------------------------------------- erp.처방
bom = []
versions = {}
for fg in FG:
    nv = 2 if random.random() < 0.3 else 1
    versions[fg['품목코드']] = list(range(1, nv + 1))
    for v in range(1, nv + 1):
        others = random.sample(RM[1:], random.randint(4, 7))
        ratios = [round(random.uniform(0.05, 6.0), 3) for _ in others]
        water = round(100 - sum(ratios), 3)
        comp = [(RM[0], water)] + list(zip(others, ratios))
        for seq, (rm, r) in enumerate(comp, 1):
            bom.append({'제품코드': fg['품목코드'], '처방버전': v, '순번': seq, '원료코드': rm['품목코드'],
                        '배합비율': r, '투입상': 'A' if seq <= 2 else random.choice(['A', 'B', 'C']),
                        '비고': None})
BOM_BY = defaultdict(list)
for b in bom:
    BOM_BY[(b['제품코드'], b['처방버전'])].append(b)

# ---------------------------------------------------------------- erp.생산지시
wo_dates = sorted(workday(rand_date(date(2025, 3, 3), date(2025, 9, 26))) for _ in range(100))
orders = []
for i, d in enumerate(wo_dates, 1):
    fg = random.choice(FG)
    vers = versions[fg['품목코드']]
    ver = vers[-1] if (len(vers) == 1 or random.random() < 0.8) else vers[0]
    if d <= date(2025, 9, 12):
        st = '취소' if random.random() < 0.03 else '완료'
    elif d <= date(2025, 9, 20):
        st = '진행'
    else:
        st = '지시'
    qty = random.randrange(3000, 30001, 500)
    orders.append({
        '지시번호': f"WO-25-{i:04d}", '지시일자': d, '제품코드': fg['품목코드'], '처방버전': ver,
        '고객사코드': fg['주거래처코드'], '지시수량': qty,
        '배치크기': round(qty * fg['_vol'] / 1000 * 1.04, 1), '생산라인': random.randint(1, 6),
        '진행상태': st, '작성자': f"E{random.randint(1001, 1060)}",
    })
WO = {o['지시번호']: o for o in orders}

# ---------------------------------------------------------------- wms.location
ZONES = [('RMA', 8, '원료 A구역', '15~25℃'), ('RMB', 6, '원료 B구역', '15~25℃'), ('CLD', 4, '냉장 창고', '2~8℃'),
         ('PKG', 6, '포장재 구역', '실온'), ('FGA', 8, '완제품 A구역', '실온'), ('FGB', 5, '완제품 B구역', '실온'),
         ('QRT', 3, '격리·보류 구역', '15~25℃')]
locations = []
for z, n, nm, t in ZONES:
    for b in range(1, n + 1):
        locations.append({'zone_cd': z, 'bin_no': b, 'zone_name': nm, 'temp_range': t,
                          'max_pallet': random.choice([4, 6, 8, 10]), 'use_yn': 'Y'})


def pick_bin(z):
    return random.randint(1, {a: b for a, b, _, _ in ZONES}[z])


# ---------------------------------------------------------------- wms 원료·포장재 입고/로트
arrivals = []  # (item, date)
for rm in RM:
    k = random.randint(3, 4)
    ds = [rand_date(date(2025, 2, 3), date(2025, 2, 27))] + \
         [rand_date(date(2025, 3, 1), date(2025, 9, 25)) for _ in range(k - 1)]
    arrivals += [[rm, workday(d)] for d in ds]
for pk in PKI:
    ds = [rand_date(date(2025, 2, 3), date(2025, 9, 25)) for _ in range(random.randint(2, 3))]
    arrivals += [[pk, workday(d)] for d in ds]
# 같은 공급사·같은 날 묶음 입고 일부 생성
for a in arrivals:
    if random.random() < 0.15:
        same = [b for b in arrivals if b is not a and b[0]['주거래처코드'] == a[0]['주거래처코드']
                and b[0]['품목코드'] != a[0]['품목코드']]
        if same:
            a[1] = random.choice(same)[1]
arrivals.sort(key=lambda a: (a[1], a[0]['품목코드']))

receipts = []
rcpt_key = {}
rcpt_seq = defaultdict(int)
lot_seq = defaultdict(int)
lots = []
for it, d in arrivals:
    key = (it['주거래처코드'], d)
    if key not in rcpt_key:
        rcpt_seq[d] += 1
        rno = f"GR{d:%y%m%d}-{rcpt_seq[d]:02d}"
        rcpt_key[key] = rno
        receipts.append({
            'receipt_no': rno, 'receipt_date': d,
            'supplier_biz_no': P_BY_CODE[it['주거래처코드']]['사업자번호'].replace('-', ''),
            'po_ref': f"PO-25-{random.randint(1, 140):04d}", 'dock_no': random.randint(1, 4),
            'pkg_cnt': 0, 'receiver_id': f"w{random.randint(201, 215)}", 'remark': None,
        })
    rno = rcpt_key[key]
    lot_seq[('R', d)] += 1
    lot_no = f"RL{d:%y%m%d}-{lot_seq[('R', d)]:02d}"
    is_rm = it['품목구분'] == '원료'
    zone = ('CLD' if it['보관조건'].startswith('냉장') else random.choice(['RMA', 'RMB'])) if is_rm else 'PKG'
    mfg = d - timedelta(days=random.randint(10, 60))
    status = 'AVAILABLE'
    if d < date(2025, 6, 1) and random.random() < 0.45:
        status = 'CONSUMED'
    elif random.random() < 0.04:
        status = 'HOLD'
        zone = 'QRT'
    qty = round(random.uniform(20, 800), 2) if is_rm else float(random.randrange(2000, 40001, 1000))
    lots.append({
        'lot_no': lot_no, 'item_cd': it['품목코드'].replace('-', ''), 'lot_type': 'RM' if is_rm else 'PK',
        'receipt_no': rno, 'zone_cd': zone, 'bin_no': pick_bin(zone),
        'qty_on_hand': 0.0 if status == 'CONSUMED' else qty, 'uom': 'KG' if is_rm else 'EA',
        'mfg_date': mfg, 'exp_date': mfg + timedelta(days=365 * (2 if is_rm else 5)), 'lot_status': status,
        'last_move_ts': datetime.combine(d, datetime.min.time()) + timedelta(hours=random.randint(9, 17),
                                                                              minutes=random.randint(0, 59)),
        '_item': it['품목코드'], '_date': d,
    })
    r = next(x for x in receipts if x['receipt_no'] == rno)
    r['pkg_cnt'] += random.randint(2, 40)
RM_LOTS = defaultdict(list)
for l in lots:
    RM_LOTS[l['_item']].append(l)

# ---------------------------------------------------------------- erp.칭량실적
weigh = []
wid = 0
SCALES = ['SC-01', 'SC-02', 'SC-03', 'SC-04', 'SC-05']
for o in orders:
    if o['지시일자'] < date(2025, 7, 21) or o['진행상태'] not in ('완료', '진행'):
        continue
    wdate = workday(o['지시일자'] + timedelta(days=1))
    t = datetime.combine(wdate, datetime.min.time()) + timedelta(hours=8, minutes=random.randint(0, 40))
    emp = f"E{random.randint(1101, 1130)}"
    for seq, b in enumerate(BOM_BY[(o['제품코드'], o['처방버전'])], 1):
        wid += 1
        cands = [l for l in RM_LOTS[b['원료코드']] if l['_date'] <= wdate] or RM_LOTS[b['원료코드']]
        lot = cands[-1] if random.random() < 0.8 else random.choice(cands)
        target = round(o['배치크기'] * b['배합비율'] / 100, 3)
        t += timedelta(minutes=random.randint(4, 12))
        weigh.append({
            '칭량ID': wid, '지시번호': o['지시번호'], '칭량순번': seq, '원료코드': b['원료코드'],
            '원료로트번호': lot['lot_no'], '지시중량': target,
            '실칭량': round(target * random.uniform(0.997, 1.003), 3), '저울번호': random.choice(SCALES),
            '칭량자': emp, '칭량일시': t,
        })

# ---------------------------------------------------------------- erp.충전실적 / wms FG 로트
fills = []
fg_lots = []
LOT_WO = {}
for o in orders:
    if o['진행상태'] not in ('완료', '진행'):
        continue
    runs = 1 if o['진행상태'] == '진행' else random.randint(1, 3)
    remain = int(o['지시수량'] * random.uniform(0.96, 1.0))
    d = o['지시일자'] + timedelta(days=2)
    for r in range(runs):
        d = workday(d + timedelta(days=1 if r else 0))
        q = remain // (runs - r) if r < runs - 1 else remain
        remain -= q
        fills.append({
            '지시번호': o['지시번호'], '충전일자': d, '충전라인': o['생산라인'],
            '충전설비': f"FM-{random.randint(1, 9):02d}", '작업조': random.choice(['주간', '야간']),
            '충전수량': q, '불량수량': random.randint(0, max(1, q // 150)), '작업자': f"E{random.randint(1131, 1170)}",
        })
    if o['진행상태'] == '완료':
        lot_seq[('F', d)] += 1
        ln = f"FL{d:%y%m%d}-{lot_seq[('F', d)]:02d}"
        LOT_WO[ln] = o['지시번호']
        zone = random.choice(['FGA', 'FGB'])
        produced = o['지시수량']
        fg_lots.append({
            'lot_no': ln, 'item_cd': o['제품코드'].replace('-', ''), 'lot_type': 'FG', 'receipt_no': None,
            'zone_cd': zone, 'bin_no': pick_bin(zone), 'qty_on_hand': float(produced), 'uom': 'EA',
            'mfg_date': d, 'exp_date': d + timedelta(days=365 * 3), 'lot_status': 'AVAILABLE',
            'last_move_ts': datetime.combine(d, datetime.min.time()) + timedelta(hours=random.randint(13, 22)),
            '_item': o['제품코드'], '_date': d, '_cust': o['고객사코드'],
        })
fills.sort(key=lambda f: (f['충전일자'], f['지시번호']))

# ---------------------------------------------------------------- wms.shipment / shipment_line
shipments = []
lines = []
ship_seq = defaultdict(int)
ship_dates = sorted(workday(rand_date(date(2025, 3, 25), date(2025, 10, 17))) for _ in range(95))
for sd in ship_dates:
    avail = [l for l in fg_lots if l['_date'] < sd]
    if not avail:
        continue
    c = random.choice(sorted({l['_cust'] for l in avail}))
    mine = [l for l in avail if l['_cust'] == c]
    ship_seq[sd] += 1
    sno = f"SH{sd:%y%m%d}-{ship_seq[sd]:02d}"
    shipments.append({
        'shipment_no': sno, 'ship_date': sd, 'customer_cd': c.lower(),
        'carrier': random.choice(['한진택배', 'CJ대한통운', '롯데글로벌', '자차']),
        'vehicle_no': f"{random.randint(80, 99)}{random.choice('가나다라마바사')}{random.randint(1000, 9999)}",
        'dock_no': random.randint(1, 8), 'ship_status': 'SHIPPED' if sd < date(2025, 10, 14) else 'PICKING',
    })
    for ls, lot in enumerate(random.sample(mine, min(len(mine), random.randint(1, 4))), 1):
        q = random.randrange(480, 6001, 48)
        lines.append({'shipment_no': sno, 'line_seq': ls, 'lot_no': lot['lot_no'], 'ship_qty': q,
                      'box_cnt': (q + 47) // 48})
        lot['qty_on_hand'] = max(0.0, lot['qty_on_hand'] - q)
for l in fg_lots:
    if l['qty_on_hand'] == 0:
        l['lot_status'] = 'SHIPPED'
all_lots = sorted(lots + fg_lots, key=lambda l: (l['_date'], l['lot_no']))
LOT = {l['lot_no']: l for l in all_lots}

# ---------------------------------------------------------------- qms.INSP_ITEM_MST
TESTS = [('APP', '외관', '-', 'ALL'), ('ODR', '향취', '-', 'ALL'), ('COLOR', '색상', '-', 'ALL'),
         ('PH', 'pH', '-', 'ALL'), ('VISC', '점도', 'cP', 'FG'), ('SG', '비중', 'g/ml', 'RM'),
         ('HARD', '경도', 'g', 'FG'), ('MP', '융점', '℃', 'RM'), ('TAC', '총호기성생균수', 'CFU/g', 'ALL'),
         ('YM', '진균수', 'CFU/g', 'FG'), ('ECOLI', '대장균', '-', 'FG'), ('PSA', '녹농균', '-', 'FG'),
         ('SAU', '황색포도상구균', '-', 'FG'), ('PB', '납', 'ppm', 'RM'), ('AS', '비소', 'ppm', 'RM'),
         ('HG', '수은', 'ppm', 'RM'), ('SB', '안티몬', 'ppm', 'RM'), ('CD', '카드뮴', 'ppm', 'RM'),
         ('NI', '니켈', 'ppm', 'RM'), ('MEOH', '메탄올', '%', 'RM'), ('FA', '포름알데하이드', 'ppm', 'RM'),
         ('DIOX', '디옥산', 'ppm', 'RM'), ('FILL', '충전량', 'ml', 'FG'), ('LEAK', '누액', '-', 'FG'),
         ('CAPT', '캡 토크', 'kgf·cm', 'FG')]
SPEC = {'PH': (4.5, 7.0), 'VISC': (3000, 12000), 'SG': (0.95, 1.10), 'HARD': (80, 200), 'MP': (45, 70),
        'TAC': (0, 1000), 'YM': (0, 100), 'PB': (0, 20), 'AS': (0, 10), 'HG': (0, 1), 'SB': (0, 10),
        'CD': (0, 5), 'NI': (0, 10), 'MEOH': (0, 0.2), 'FA': (0, 2000), 'DIOX': (0, 100), 'FILL': (0, 0),
        'CAPT': (8, 15)}
insp_items = [{'TEST_ID': i, 'TEST_CD': cd, 'TEST_NM': nm, 'UNIT_CD': u,
               'TEST_MTHD': random.choice(['관능', '기기분석', '미생물한도시험', '화장품안전기준 시험법']),
               'APPLY_TP': ap, 'USE_YN': 'Y'} for i, (cd, nm, u, ap) in enumerate(TESTS, 1)]
RM_TESTS = [t[0] for t in TESTS if t[3] in ('RM', 'ALL')]
FG_TESTS = [t[0] for t in TESTS if t[3] in ('FG', 'ALL')]

# ---------------------------------------------------------------- qms.QC_INSP_HDR / QC_INSP_RSLT
pre = []
for l in all_lots:
    if l['lot_type'] == 'FG':
        d = workday(l['_date'] + timedelta(days=random.randint(1, 3)))
        tp = 'FQC'
    else:
        d = workday(l['_date'] + timedelta(days=random.randint(0, 2)))
        tp = 'IQC'
    pre.append((d, tp, l))
    if random.random() < 0.04:
        pre.append((workday(d + timedelta(days=random.randint(3, 10))), tp, l))
pre.sort(key=lambda x: (x[0], x[2]['lot_no']))
hdrs = []
rslts = []
for i, (d, tp, l) in enumerate(pre, 1):
    ino = f"QI-25-{i:05d}"
    wo = LOT_WO.get(l['lot_no'])
    jdg = 'F' if random.random() < 0.04 else 'P'
    hdrs.append({
        'INSP_NO': ino, 'INSP_TP_CD': tp, 'INSP_DT': d, 'LOT_NO': l['lot_no'][:2] + '-' + l['lot_no'][2:],
        'WO_YY': wo[3:5] if wo else None, 'WO_SEQ': int(wo[6:]) if wo else None,
        'SMPL_QTY': random.randint(1, 20), 'JDG_CD': jdg, 'INSP_EMP_NO': f"Q{random.randint(301, 318)}",
        'RMK': '재검사' if any(h['LOT_NO'] == l['lot_no'][:2] + '-' + l['lot_no'][2:] for h in hdrs) else None,
    })
    pool = FG_TESTS if tp == 'FQC' else RM_TESTS
    n = 2 if tp == 'FQC' else random.choice([1, 1, 1, 2])
    for s, cd in enumerate(['APP'] + random.sample([x for x in pool if x != 'APP'], n - 1) if n > 1 else ['APP'], 1):
        lo, hi = SPEC.get(cd, (None, None))
        if lo is None or (lo == 0 and hi == 0):
            val, lo, hi = None, None, None
        else:
            val = round(random.uniform(lo, hi * (1.05 if jdg == 'F' else 0.98)), 3)
        rslts.append({'INSP_NO': ino, 'INSP_SEQ': s, 'TEST_CD': cd, 'MEAS_VAL': val, 'SPEC_MIN': lo,
                      'SPEC_MAX': hi, 'RSLT_CD': 'F' if (jdg == 'F' and s == n) else 'P'})

# ---------------------------------------------------------------- qms.CUST_CMPL
CMPL_TYPES = ['C01', 'C02', 'C03', 'C04', 'C05', 'C06', 'C07', 'C08']
DESCS = ['내용물 변색', '이취 발생', '펌프 작동 불량', '용기 누액', '충전량 부족', '이물 혼입', '피부 트러블 문의',
         '단상자 인쇄 불량', '캡 파손', '분리 현상']
ship_by = {s['shipment_no']: s for s in shipments}
picks = [random.choice(lines) for _ in range(80)]
cmpl_pre = []
for ln in picks:
    s = ship_by[ln['shipment_no']]
    d = min(s['ship_date'] + timedelta(days=random.randint(7, 90)), date(2025, 11, 7))
    cmpl_pre.append((d, ln, s))
cmpl_pre.sort(key=lambda x: x[0])
cmpls = []
for i, (d, ln, s) in enumerate(cmpl_pre, 1):
    lot = LOT[ln['lot_no']]
    cmpls.append({
        'CMPL_NO': f"CMP-25-{i:04d}", 'RCPT_DT': d, 'CUST_NO': P_BY_CODE[s['customer_cd'].upper()]['_num'],
        'PRD_LOT_TXT': f"{lot['lot_no']} / EXP {lot['exp_date']:%Y.%m}", 'CMPL_TP_CD': random.choice(CMPL_TYPES),
        'SEVERITY': random.choices([1, 2, 3, 4, 5], [30, 30, 20, 15, 5])[0], 'CMPL_QTY': random.randint(1, 12),
        'CMPL_DESC': random.choice(DESCS),
        'RTN_TRK_NO': f"5{random.randint(10 ** 11, 10 ** 12 - 1)}" if random.random() < 0.6 else None,
        'STAT_CD': 'CLS' if d < date(2025, 10, 1) else random.choice(['RCV', 'INV']),
    })

# ================================================================ dirt
dirt = []


def null_some(rows, col, rate, label):
    cand = [r for r in rows if r.get(col) is not None]
    k = max(1, round(len(cand) * rate))
    for r in random.sample(cand, k):
        r[col] = None
    dirt.append(f"{label}: {k}건 값 누락")


null_some(weigh, '원료로트번호', 0.03, 'erp.칭량실적.원료로트번호')
null_some(fills, '지시번호', 0.02, 'erp.충전실적.지시번호')
null_some(receipts, 'supplier_biz_no', 0.02, 'wms.inbound_receipt.supplier_biz_no')
null_some(shipments, 'customer_cd', 0.02, 'wms.shipment.customer_cd')
null_some(hdrs, 'LOT_NO', 0.03, 'qms.QC_INSP_HDR.LOT_NO')
null_some(orders, '고객사코드', 0.02, 'erp.생산지시.고객사코드')

# 고아 값
for r, v in zip(random.sample([w for w in weigh if w['원료로트번호']], 2), ['RL250231-01', 'RL250718-09']):
    r['원료로트번호'] = v
dirt.append('erp.칭량실적.원료로트번호: 존재하지 않는 로트 2건(RL250231-01, RL250718-09)')
for r in random.sample([l for l in all_lots if l['lot_type'] == 'RM'], 2):
    r['item_cd'] = 'RM2099'
dirt.append('wms.inventory_lot.item_cd: 단종 원료 코드 RM2099 2건(ERP 미등록)')
random.choice(cmpls)['CUST_NO'] = 998
dirt.append('qms.CUST_CMPL.CUST_NO: ERP에 없는 고객 번호 998 1건')
random.choice(lines)['lot_no'] = 'FL250999-01'
dirt.append('wms.shipment_line.lot_no: 존재하지 않는 로트 FL250999-01 1건')

# 오타·표기 흔들림
r = random.choice([o for o in orders if o['제품코드'] == 'FG-1005'] or orders)
r['제품코드'] = 'FG-1O05'
dirt.append(f"erp.생산지시.제품코드: 숫자 0 대신 영문 O 입력 1건(FG-1O05, {r['지시번호']})")
r = random.choice([x for x in receipts if x['supplier_biz_no']])
r['supplier_biz_no'] = r['supplier_biz_no'][:3] + '-' + r['supplier_biz_no'][3:5] + '-' + r['supplier_biz_no'][5:]
dirt.append(f"wms.inbound_receipt.supplier_biz_no: 하이픈이 남은 값 1건({r['receipt_no']})")
r = random.choice([x for x in shipments if x['customer_cd']])
r['customer_cd'] = r['customer_cd'].upper()
dirt.append(f"wms.shipment.customer_cd: 소문자 규칙과 달리 대문자로 저장된 값 1건({r['shipment_no']})")
r = random.choice([x for x in hdrs if x['LOT_NO']])
r['LOT_NO'] = r['LOT_NO'].lower()
dirt.append(f"qms.QC_INSP_HDR.LOT_NO: 소문자로 입력된 로트 1건({r['INSP_NO']})")
r = random.choice([x for x in rslts if x['TEST_CD'] == 'APP'])
r['TEST_CD'] = 'AAP'
dirt.append(f"qms.QC_INSP_RSLT.TEST_CD: 오타 AAP 1건({r['INSP_NO']})")
dirt.append('wms.inventory_lot.receipt_no: 완제품(FG) 로트는 입고 전표가 없어 정상적으로 비어 있음(오염 아님)')
dirt.append('qms.QC_INSP_HDR.WO_YY·WO_SEQ: 수입검사(IQC)는 작업지시가 없어 정상적으로 비어 있음(오염 아님)')

# ================================================================ write
V = 'VARCHAR'
put('erp', '거래처', '고객사·원료 공급사·포장재 공급사를 함께 관리하는 업체 기준정보', ['거래처코드'], [
    ('거래처코드', f'{V}(10)', '업체 고유 코드'), ('거래처명', f'{V}(50)', ''),
    ('거래처구분', f'{V}(10)', '고객사/원료공급사/포장재공급사'), ('사업자번호', 'CHAR(12)', '사업자등록번호(하이픈 포함)'),
    ('대표자명', f'{V}(20)', ''), ('지역', f'{V}(20)', ''), ('거래등급', 'INTEGER', '1(우수)~5'),
    ('등록일자', 'DATE', ''), ('사용여부', 'CHAR(1)', 'Y/N')], partners)
put('erp', '품목', '완제품·원료·포장재 품목 기준정보', ['품목코드'], [
    ('품목코드', f'{V}(10)', ''), ('품목명', f'{V}(60)', ''), ('품목구분', f'{V}(10)', '완제품/원료/포장재'),
    ('규격', f'{V}(20)', ''), ('단위', f'{V}(5)', ''), ('표준단가', 'NUMERIC(12,2)', '원'),
    ('바코드', 'CHAR(13)', '완제품 EAN-13'), ('주거래처코드', f'{V}(10)', '주 매입·매출 업체'),
    ('보관조건', f'{V}(20)', ''), ('사용여부', 'CHAR(1)', 'Y/N')], items)
put('erp', '처방', '완제품별 처방(BOM) 원료 구성과 배합비', ['제품코드', '처방버전', '순번'], [
    ('제품코드', f'{V}(10)', '완제품'), ('처방버전', 'INTEGER', ''), ('순번', 'INTEGER', '투입 순서'),
    ('원료코드', f'{V}(10)', '투입 원료'), ('배합비율', 'NUMERIC(7,3)', '%'), ('투입상', 'CHAR(1)', 'A/B/C 상'),
    ('비고', f'{V}(100)', '')], bom)
put('erp', '생산지시', '고객사 위탁 생산 작업지시', ['지시번호'], [
    ('지시번호', f'{V}(12)', ''), ('지시일자', 'DATE', ''), ('제품코드', f'{V}(10)', '생산 완제품'),
    ('처방버전', 'INTEGER', '적용 처방 버전'), ('고객사코드', f'{V}(10)', '위탁 고객사'), ('지시수량', 'INTEGER', 'EA'),
    ('배치크기', 'NUMERIC(10,1)', 'kg'), ('생산라인', 'INTEGER', '라인 번호'),
    ('진행상태', f'{V}(10)', '지시/진행/완료/취소'), ('작성자', f'{V}(10)', '사번')], orders)
put('erp', '칭량실적', '작업지시별 원료 칭량 기록', ['칭량ID'], [
    ('칭량ID', 'INTEGER', '일련번호'), ('지시번호', f'{V}(12)', ''), ('칭량순번', 'INTEGER', ''),
    ('원료코드', f'{V}(10)', ''), ('원료로트번호', f'{V}(15)', '투입한 원료 로트'), ('지시중량', 'NUMERIC(10,3)', 'kg'),
    ('실칭량', 'NUMERIC(10,3)', 'kg'), ('저울번호', f'{V}(10)', ''), ('칭량자', f'{V}(10)', '사번'),
    ('칭량일시', 'TIMESTAMP', '')], weigh)
put('erp', '충전실적', '충전·포장 라인 작업 실적 로그', [], [
    ('지시번호', f'{V}(12)', ''), ('충전일자', 'DATE', ''), ('충전라인', 'INTEGER', ''),
    ('충전설비', f'{V}(10)', ''), ('작업조', f'{V}(4)', ''), ('충전수량', 'INTEGER', 'EA'), ('불량수량', 'INTEGER', 'EA'),
    ('작업자', f'{V}(10)', '사번')], fills)

put('wms', 'location', '창고 구역·랙 위치 기준정보', ['zone_cd', 'bin_no'], [
    ('zone_cd', f'{V}(5)', 'zone code'), ('bin_no', 'INTEGER', 'bin number in zone'), ('zone_name', f'{V}(30)', ''),
    ('temp_range', f'{V}(10)', ''), ('max_pallet', 'INTEGER', ''), ('use_yn', 'CHAR(1)', '')], locations)
put('wms', 'inbound_receipt', '원료·포장재 입고 전표', ['receipt_no'], [
    ('receipt_no', f'{V}(12)', ''), ('receipt_date', 'DATE', ''), ('supplier_biz_no', f'{V}(10)', 'supplier tax id'),
    ('po_ref', f'{V}(12)', 'purchase order ref'), ('dock_no', 'INTEGER', 'unloading dock'),
    ('pkg_cnt', 'INTEGER', ''), ('receiver_id', f'{V}(10)', ''), ('remark', f'{V}(100)', '')], receipts)
put('wms', 'inventory_lot', '로트별 재고(원료·포장재·완제품)', ['lot_no'], [
    ('lot_no', f'{V}(15)', ''), ('item_cd', f'{V}(10)', ''), ('lot_type', f'{V}(2)', 'RM/PK/FG'),
    ('receipt_no', f'{V}(12)', 'inbound doc'), ('zone_cd', f'{V}(5)', ''), ('bin_no', 'INTEGER', ''),
    ('qty_on_hand', 'NUMERIC(12,2)', ''), ('uom', f'{V}(3)', ''), ('mfg_date', 'DATE', ''), ('exp_date', 'DATE', ''),
    ('lot_status', f'{V}(12)', ''), ('last_move_ts', 'TIMESTAMP', '')], all_lots)
put('wms', 'shipment', '완제품 출하 헤더', ['shipment_no'], [
    ('shipment_no', f'{V}(12)', ''), ('ship_date', 'DATE', ''), ('customer_cd', f'{V}(10)', 'ship-to'),
    ('carrier', f'{V}(20)', ''), ('vehicle_no', f'{V}(12)', ''), ('dock_no', 'INTEGER', 'loading dock'),
    ('ship_status', f'{V}(10)', '')], shipments)
put('wms', 'shipment_line', '출하 상세(로트별 출하 수량)', ['shipment_no', 'line_seq'], [
    ('shipment_no', f'{V}(12)', ''), ('line_seq', 'INTEGER', ''), ('lot_no', f'{V}(15)', ''),
    ('ship_qty', 'INTEGER', 'EA'), ('box_cnt', 'INTEGER', '')], lines)

put('qms', 'INSP_ITEM_MST', '품질 시험 항목 기준정보', ['TEST_ID'], [
    ('TEST_ID', 'INTEGER', '시험항목 ID'), ('TEST_CD', f'{V}(10)', '시험항목 약어(고유)'), ('TEST_NM', f'{V}(30)', ''),
    ('UNIT_CD', f'{V}(10)', ''), ('TEST_MTHD', f'{V}(30)', ''), ('APPLY_TP', f'{V}(3)', 'RM/FG/ALL'),
    ('USE_YN', 'CHAR(1)', '')], insp_items)
put('qms', 'QC_INSP_HDR', '수입검사(IQC)·완제품검사(FQC) 의뢰 및 판정', ['INSP_NO'], [
    ('INSP_NO', f'{V}(12)', ''), ('INSP_TP_CD', f'{V}(3)', 'IQC/FQC'), ('INSP_DT', 'DATE', ''),
    ('LOT_NO', f'{V}(16)', '검사 대상 로트'), ('WO_YY', 'CHAR(2)', '작업지시 연도'), ('WO_SEQ', 'INTEGER', '작업지시 일련번호'),
    ('SMPL_QTY', 'INTEGER', '채취 시료 수'), ('JDG_CD', 'CHAR(1)', 'P/F'), ('INSP_EMP_NO', f'{V}(6)', ''),
    ('RMK', f'{V}(100)', '')], hdrs)
put('qms', 'QC_INSP_RSLT', '검사 항목별 측정 결과', ['INSP_NO', 'INSP_SEQ'], [
    ('INSP_NO', f'{V}(12)', ''), ('INSP_SEQ', 'INTEGER', ''), ('TEST_CD', f'{V}(10)', ''),
    ('MEAS_VAL', 'NUMERIC(12,3)', ''), ('SPEC_MIN', 'NUMERIC(12,3)', ''), ('SPEC_MAX', 'NUMERIC(12,3)', ''),
    ('RSLT_CD', 'CHAR(1)', 'P/F')], rslts)
put('qms', 'CUST_CMPL', '고객 불만(VOC) 접수 및 처리', ['CMPL_NO'], [
    ('CMPL_NO', f'{V}(12)', ''), ('RCPT_DT', 'DATE', '접수일'), ('CUST_NO', 'INTEGER', '고객 번호'),
    ('PRD_LOT_TXT', f'{V}(40)', '용기 인쇄 문자 그대로'), ('CMPL_TP_CD', f'{V}(3)', '불만 유형 C01~C08'),
    ('SEVERITY', 'INTEGER', '1~5'), ('CMPL_QTY', 'INTEGER', ''), ('CMPL_DESC', f'{V}(200)', ''),
    ('RTN_TRK_NO', 'CHAR(13)', '반품 회수 송장번호'), ('STAT_CD', f'{V}(3)', 'RCV/INV/CLS')], cmpls)

for s, tables in SCHEMA.items():
    with open(os.path.join(BASE, 'data', s, '_schema.json'), 'w', encoding='utf-8') as f:
        json.dump({'tables': tables}, f, ensure_ascii=False, indent=2)

# ================================================================ answer
R = lambda f, t, tr=None: {'from': f, 'to': t, 'transform': tr, 'cardinality': 'N:1'}
relations = [
    R('erp.품목.주거래처코드', 'erp.거래처.거래처코드'),
    R('erp.처방.제품코드', 'erp.품목.품목코드'),
    R('erp.처방.원료코드', 'erp.품목.품목코드'),
    R('erp.생산지시.제품코드', 'erp.품목.품목코드'),
    R('erp.생산지시.고객사코드', 'erp.거래처.거래처코드'),
    R('erp.칭량실적.지시번호', 'erp.생산지시.지시번호'),
    R('erp.칭량실적.원료코드', 'erp.품목.품목코드'),
    R('erp.충전실적.지시번호', 'erp.생산지시.지시번호'),
    R('wms.inventory_lot.receipt_no', 'wms.inbound_receipt.receipt_no'),
    R('wms.inventory_lot.zone_cd+wms.inventory_lot.bin_no', 'wms.location.zone_cd+wms.location.bin_no'),
    R('wms.shipment_line.shipment_no', 'wms.shipment.shipment_no'),
    R('wms.shipment_line.lot_no', 'wms.inventory_lot.lot_no'),
    R('qms.QC_INSP_RSLT.INSP_NO', 'qms.QC_INSP_HDR.INSP_NO'),
    R('qms.QC_INSP_RSLT.TEST_CD', 'qms.INSP_ITEM_MST.TEST_CD'),
    R('wms.inbound_receipt.supplier_biz_no', 'erp.거래처.사업자번호',
      'WMS는 하이픈 없는 10자리 숫자로 저장(1234567890), ERP는 3-2-5 하이픈 형식(123-45-67890). 대상은 PK가 아닌 고유 컬럼'),
    R('wms.inventory_lot.item_cd', 'erp.품목.품목코드', 'WMS는 하이픈 제거(RM2001), ERP는 RM-2001'),
    R('wms.shipment.customer_cd', 'erp.거래처.거래처코드', 'WMS는 소문자(c00103), ERP는 대문자(C00103)'),
    R('erp.칭량실적.원료로트번호', 'wms.inventory_lot.lot_no'),
    R('qms.QC_INSP_HDR.LOT_NO', 'wms.inventory_lot.lot_no',
      'QMS는 두 글자 접두어 뒤에 하이픈을 하나 더 넣음(RL-250503-01), WMS는 RL250503-01. 첫 하이픈 제거'),
    R('qms.QC_INSP_HDR.WO_YY+qms.QC_INSP_HDR.WO_SEQ', 'erp.생산지시.지시번호',
      "두 컬럼 결합: 'WO-' + WO_YY + '-' + WO_SEQ를 4자리 0채움(25, 12 → WO-25-0012). FQC 행만 값이 있음"),
    R('qms.CUST_CMPL.PRD_LOT_TXT', 'wms.inventory_lot.lot_no',
      "용기 인쇄 문자열 'FL250503-01 / EXP 2028.05'에서 ' / ' 앞부분(앞 11자리)만 잘라야 로트번호"),
    R('qms.CUST_CMPL.CUST_NO', 'erp.거래처.거래처코드', "정수만 저장(103). 'C' 접두어 + 5자리 0채움 → C00103"),
]
traps = [
    {'column': 'qms.QC_INSP_HDR.SMPL_QTY', 'looks_like': 'qms.INSP_ITEM_MST.TEST_ID',
     'why': '시료 채취 수량(1~20)이라 값이 1~25 일련 ID 안에 전부 들어가지만 의미가 없는 수량'},
    {'column': 'qms.QC_INSP_RSLT.INSP_SEQ', 'looks_like': 'qms.INSP_ITEM_MST.TEST_ID',
     'why': '검사 내 결과 순번(1~2). 실제 시험항목 연결은 TEST_CD → INSP_ITEM_MST.TEST_CD'},
    {'column': 'erp.생산지시.생산라인', 'looks_like': 'erp.칭량실적.칭량ID',
     'why': '라인 번호(1~6)가 칭량 일련번호와 겹치지만 라인 기준정보 표가 없는 단순 속성'},
    {'column': 'erp.처방.순번', 'looks_like': 'erp.칭량실적.칭량ID',
     'why': '처방 내 투입 순서(1~8). 복합 PK의 일부일 뿐 칭량 일련번호와 무관'},
    {'column': 'wms.shipment.dock_no', 'looks_like': 'wms.location.bin_no',
     'why': '상차 도크 번호(1~8)로 랙 위치 번호와 값 범위가 같지만 다른 개념이며 bin_no 단독은 키도 아님'},
    {'column': 'wms.inbound_receipt.po_ref', 'looks_like': 'erp.생산지시.지시번호',
     'why': "구매발주번호 PO-25-0012가 작업지시 WO-25-0012와 숫자·형식이 같지만 발주는 이 데이터셋에 없는 다른 엔터티"},
    {'column': 'qms.CUST_CMPL.CMPL_TP_CD', 'looks_like': 'erp.거래처.거래처코드',
     'why': "불만 유형 코드 C01~C08이 거래처코드와 같은 'C' 접두어를 쓰지만 유형 분류 코드"},
    {'column': 'qms.CUST_CMPL.RTN_TRK_NO', 'looks_like': 'erp.품목.바코드',
     'why': '반품 송장번호도 CHAR(13) 숫자지만 바코드(880…)와 값이 겹치지 않는 택배 번호'},
]
with open(os.path.join(BASE, 'answer.json'), 'w', encoding='utf-8') as f:
    json.dump({'systems': ['erp', 'wms', 'qms'], 'relations': relations, 'traps': traps, 'dirt': dirt},
              f, ensure_ascii=False, indent=2)

for s, tables in SCHEMA.items():
    for t in tables:
        with open(os.path.join(BASE, 'data', s, t + '.csv'), encoding='utf-8') as fh:
            n = sum(1 for _ in fh) - 1
        print(f"{s}.{t}: {n}")
