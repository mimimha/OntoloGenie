import csv
import json
import os
import re

# 저장소 위치: ai/data/gold-b8-v1/verify.py → 데이터는 ai/data/data-b8-v1
HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(os.path.dirname(HERE), "data-b8-v1")
with open(os.path.join(HERE, "answer.json"), encoding="utf-8") as f:
    gold = json.load(f)
T, S, ok = {}, {}, True
for sysn in gold["systems"]:
    with open(os.path.join(DATA, sysn, "_schema.json"), encoding="utf-8") as f:
        sc = json.load(f)
    for t, spec in sc["tables"].items():
        with open(os.path.join(DATA, sysn, t + ".csv"), encoding="utf-8", newline="") as f:
            rows = list(csv.reader(f))
        hdr, body = rows[0], rows[1:]
        if hdr != list(spec["columns"]):
            print("HEADER MISMATCH", sysn, t); ok = False
        recs = [{h: (v if v != "" else None) for h, v in zip(hdr, r)} for r in body]
        T[f"{sysn}.{t}"] = recs
        S[f"{sysn}.{t}"] = spec
        pk = spec["primary_key"]
        if pk:
            keys = [tuple(r[c] for c in pk) for r in recs]
            if any(None in k for k in keys) or len(set(keys)) != len(keys):
                print("PK FAIL", sysn, t); ok = False
        print(f"{sysn}.{t}: {len(recs)} rows, pk={pk}")


def split(ref):
    parts = ref.split("+")
    tbl = parts[0].rsplit(".", 1)[0]
    return tbl, [p.rsplit(".", 1)[1] for p in parts]


def digits(s): return re.sub(r"\D", "", s)
def wo(ym, seq): return f"MO{ym}{int(seq):04d}"
def itm(s): return f"P{int(s):06d}"
def reel(s):
    p = s.split("-"); return p[2] if len(p) == 4 else None
def eqp(s): return f"EQ{int(digits(s)):04d}"
def emp(s): return "20" + digits(s)
def mro(s): return f"P{int(digits(s)):06d}"

TF = {"mes.PCB_LOT.WO_YM+mes.PCB_LOT.WO_SEQ": wo, "mes.PCB_LOT.ITM_NO": itm,
      "mes.MAT_LOAD.REEL_ID": reel, "cmms.asset.equipRef": eqp,
      "cmms.technician.empRef": emp, "cmms.partUsage.partCode": mro}
THRESH = 0.85
cross = 0
for rel in gold["relations"]:
    ft, fc = split(rel["from"]); tt, tc = split(rel["to"])
    if ft.split(".")[0] != tt.split(".")[0]:
        cross += 1
    for t, cs in ((ft, fc), (tt, tc)):
        if t not in S or any(c not in S[t]["columns"] for c in cs):
            print("MISSING COLUMN", t, cs); ok = False
    target = {tuple(r[c] for c in tc) for r in T[tt]}
    vals = [tuple(r[c] for c in fc) for r in T[ft]]
    vals = [v for v in vals if None not in v]
    if rel["transform"]:
        f = TF[rel["from"]]
        conv = [f(*v) for v in vals]
        conv = [c if isinstance(c, tuple) else (c,) for c in conv]
    else:
        conv = vals
    rate = sum(c in target for c in conv) / len(conv)
    raw = sum(v in target for v in vals) / len(vals)
    flag = "OK" if rate >= THRESH else "FAIL"
    if rate < THRESH: ok = False
    print(f"{flag} {rate:.3f} (raw {raw:.3f}, n={len(vals)}) {rel['from']} -> {rel['to']}")
print(f"relations {len(gold['relations'])}, cross-system {cross}")

for tr in gold["traps"]:
    ft, fc = split(tr["column"]); tt, tc = split(tr["looks_like"])
    a = {r[fc[0]] for r in T[ft] if r[fc[0]]}; b = {r[tc[0]] for r in T[tt]}
    print(f"TRAP overlap {len(a & b)}/{len(a)} distinct: {tr['column']} ~ {tr['looks_like']}")
for d in gold["dirty"]:
    t, c = split(d["column"])
    assert c[0] in S[t]["columns"], d
print("ALL OK" if ok else "SOME FAIL")
