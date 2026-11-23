#!/usr/bin/env python3
"""Rebuilds the vendor and Bluetooth lookup tables in app/src/main/assets/.

Download these into tools/data/ first:
  oui.csv                   https://standards-oui.ieee.org/oui/oui.csv
  company_identifiers.yaml  https://bitbucket.org/bluetooth-SIG/public/raw/main/assigned_numbers/company_identifiers/company_identifiers.yaml
  member_uuids.yaml         .../assigned_numbers/uuids/member_uuids.yaml
  service_uuids.yaml        .../assigned_numbers/uuids/service_uuids.yaml
  appearance_values.yaml    .../assigned_numbers/core/appearance_values.yaml
  ad_types.yaml             .../assigned_numbers/core/ad_types.yaml

Needs PyYAML: pip install pyyaml
"""
import csv
import gzip
import re
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "tools" / "data"
OUT = ROOT / "app" / "src" / "main" / "assets"

LEGAL = re.compile(r"[\s,]*(co\.?,?\s*ltd\.?|company limited|corporation|corp\.?|incorporated|inc\.?|ltd\.?|limited|l\.?l\.?c\.?|gmbh(\s*&\s*co\.?\s*kg)?|s\.?a\.?s?\.?|a\.?g\.?|b\.?v\.?|s\.?r\.?l\.?|s\.?p\.?a\.?|pte\.?|pty\.?|oy|ab|as|kg|plc|sdn\.?\s*bhd\.?|co\.?)$", re.I)

def short(name):
    n = " ".join(name.replace("\u201c", "").replace("\u201d", "").replace('"', "").split())
    for _ in range(4):
        m = LEGAL.sub("", n).strip(" ,.-")
        if m == n or not m:
            break
        n = m
    if n.isupper() and len(n) > 4:  # SHOUTY NAMES -> Title case for long alphabetic words only
        n = " ".join(w.title() if (w.isalpha() and len(w) >= 5) else w for w in n.split())
    return n or name.strip()

# IEEE MA-L (24-bit OUI) registry
rows = []
with open(DATA / "oui.csv", newline="", encoding="utf-8") as f:
    for r in csv.DictReader(f):
        if r["Registry"] == "MA-L":
            rows.append(f'{r["Assignment"].upper()}\t{short(r["Organization Name"])}')
rows.sort()
with gzip.open(OUT / "oui.tsv.gz", "wt", encoding="utf-8") as f:
    f.write("\n".join(rows) + "\n")
print("oui", len(rows))

def load(path, key):
    return yaml.safe_load(open(DATA / path, encoding="utf-8"))[key]

companies = load("company_identifiers.yaml", "company_identifiers")
with open(OUT / "bt_company_ids.tsv", "w", encoding="utf-8") as f:
    for c in sorted(companies, key=lambda c: c["value"]):
        f.write(f'{c["value"]:04X}\t{short(str(c["name"]))}\n')
print("companies", len(companies))

uuids = {}
for u in load("service_uuids.yaml", "uuids"):
    uuids[u["uuid"]] = u["name"]
for u in load("member_uuids.yaml", "uuids"):
    uuids.setdefault(u["uuid"], short(str(u["name"])))
with open(OUT / "bt_uuid16.tsv", "w", encoding="utf-8") as f:
    for k in sorted(uuids):
        f.write(f"{k:04X}\t{uuids[k]}\n")
print("uuid16", len(uuids))

app = load("appearance_values.yaml", "appearance_values")
with open(OUT / "bt_appearance.tsv", "w", encoding="utf-8") as f:
    for c in app:
        cat = c["category"]
        f.write(f"{cat << 6:04X}\t{c['name']}\n")
        for s in c.get("subcategory", []) or []:
            f.write(f"{(cat << 6) | s['value']:04X}\t{c['name']}: {s['name']}\n")
print("appearance categories:", ", ".join(f"{c['category']}={c['name']}" for c in app))

ad = load("ad_types.yaml", "ad_types")
with open(OUT / "bt_ad_types.tsv", "w", encoding="utf-8") as f:
    seen = set()
    for a in ad:
        if a["value"] in seen: continue
        seen.add(a["value"]); f.write(f'{a["value"]:02X}\t{a["name"]}\n')
