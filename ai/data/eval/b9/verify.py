"""answer.json의 모든 관계가 실제 데이터에서 성립하는지 확인한다(비어 있지 않은 원천 값의 90% 이상 일치)."""
import csv
import json
import os
import sys

BASE = os.path.dirname(os.path.abspath(__file__))
sys.stdout.reconfigure(encoding='utf-8')


def load(sys_, table):
    with open(os.path.join(BASE, 'data', sys_, table + '.csv'), encoding='utf-8', newline='') as f:
        return list(csv.DictReader(f))


# 변환 규칙: 원천 값(들) -> 대상 표기. hard=True 는 어려운 변환(요구사항상 검사 대상 밖이지만 함께 확인)
def biz(v):
    return v[:3] + '-' + v[3:5] + '-' + v[5:] if len(v) == 10 and v.isdigit() else v


TRANSFORMS = {
    'wms.inbound_receipt.supplier_biz_no': (biz, False),
    'wms.inventory_lot.item_cd': (lambda v: v[:2] + '-' + v[2:], False),
    'wms.shipment.customer_cd': (lambda v: v.upper(), False),
    'qms.QC_INSP_HDR.LOT_NO': (lambda v: v.replace('-', '', 1), False),
    'qms.CUST_CMPL.CUST_NO': (lambda v: f"C{int(v):05d}", False),
    'qms.QC_INSP_HDR.WO_YY+qms.QC_INSP_HDR.WO_SEQ': (lambda yy, seq: f"WO-{yy}-{int(seq):04d}", True),
    'qms.CUST_CMPL.PRD_LOT_TXT': (lambda v: v.split(' / ')[0], True),
}

with open(os.path.join(BASE, 'answer.json'), encoding='utf-8') as fh:
    ans = json.load(fh)
cache = {}
schemas = {}


def table(st):
    if st not in cache:
        cache[st] = load(*st.split('.'))
    return cache[st]


ok = True
# 1) 각 표 PK 유일성
for s in ans['systems']:
    with open(os.path.join(BASE, 'data', s, '_schema.json'), encoding='utf-8') as fh:
        schemas[s] = json.load(fh)['tables']
    for t, meta in schemas[s].items():
        pk = meta['primary_key']
        rows = table(f"{s}.{t}")
        assert list(rows[0].keys()) == list(meta['columns'].keys()), f"{s}.{t} 컬럼 불일치"
        if pk:
            keys = [tuple(r[c] for c in pk) for r in rows]
            if len(keys) != len(set(keys)) or any('' in k for k in keys):
                print(f"PK FAIL {s}.{t}")
                ok = False

# 2) 관계별 일치율
for rel in ans['relations']:
    fcols = rel['from'].split('+')
    tcols = rel['to'].split('+')
    ftab = '.'.join(fcols[0].split('.')[:2])
    ttab = '.'.join(tcols[0].split('.')[:2])
    fnames = [c.split('.')[2] for c in fcols]
    tnames = [c.split('.')[2] for c in tcols]
    trows = table(ttab)
    tvals = [tuple(r[c] for c in tnames) for r in trows]
    nonnull_t = [v for v in tvals if all(v)]
    unique = len(nonnull_t) == len(set(nonnull_t))
    fn, hard = TRANSFORMS.get(rel['from'], (None, False))
    if (fn is None) != (rel['transform'] is None):
        print(f"TRANSFORM 정의 불일치 {rel['from']}")
        ok = False
    tset = set(nonnull_t)
    n = m = 0
    for r in table(ftab):
        v = tuple(r[c] for c in fnames)
        if not all(v):
            continue
        n += 1
        if fn is not None:
            v = (fn(*v),)
        if v in tset:
            m += 1
    ratio = m / n if n else 0
    good = ratio >= 0.9 and unique
    ok &= good
    print(f"{'OK  ' if good else 'FAIL'} {ratio:6.1%} ({m}/{n}) {'[hard] ' if hard else ''}{rel['from']} -> {rel['to']}"
          f"{'' if unique else '  (대상 비유일)'}")

# 3) 함정 컬럼은 관계 목록에 없어야 함
rel_from = {c for rel in ans['relations'] for c in rel['from'].split('+')}
for tr in ans['traps']:
    if tr['column'] in rel_from:
        print(f"TRAP FAIL {tr['column']} 이(가) 관계에도 있음")
        ok = False

# 4) 스키마 주석에 대상 표 이름이 없는지 확인
names = {t for s in schemas.values() for t in s}
for rel in ans['relations']:
    for c in rel['from'].split('+'):
        s, t, col = c.split('.')
        cm = schemas[s][t]['columns'][col]['comment']
        tgt = rel['to'].split('+')[0].split('.')[1]
        if tgt in cm:
            print(f"COMMENT FAIL {c}: '{cm}'")
            ok = False

print('ALL OK' if ok else 'FAILED')
sys.exit(0 if ok else 1)
