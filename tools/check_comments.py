# -*- coding: utf-8 -*-
"""校验 DDL 中每个字段都有 COMMENT，且没有多余的 COMMENT。"""
import re
import sys

path = sys.argv[1] if len(sys.argv) > 1 else r"db/migration/V1__meta_model.sql"
text = open(path, encoding="utf-8").read()

tables = {}
cur = None
for ln in text.splitlines():
    m = re.match(r"create table\s+([a-z_]+\.[a-z_]+)\s*\(", ln)
    if m:
        cur = m.group(1)
        tables[cur] = []
        continue
    if cur:
        if ln.startswith(");"):
            cur = None
            continue
        if ln.strip().startswith(("constraint", "primary key", "unique", "foreign key", "check")):
            continue
        m = re.match(r"\s{4}([a-z_][a-z0-9_]*)\s+[a-z]", ln)
        if m:
            tables[cur].append(m.group(1))

have = {(t, c) for t, c in re.findall(
    r"comment on column\s+([a-z_]+\.[a-z_]+)\.([a-z_]+)\s+is", text)}

total_cols = sum(len(v) for v in tables.values())
missing = [(t, c) for t, cols in tables.items() for c in cols if (t, c) not in have]
extra = sorted((t, c) for (t, c) in have if t not in tables or c not in tables[t])

print(f"tables={len(tables)} columns={total_cols} column_comments={len(have)}")
print(f"missing={len(missing)}")
for t, c in missing:
    print(f"  - {t}.{c}")
print(f"extra={len(extra)}")
for t, c in extra:
    print(f"  + {t}.{c}")

tbl_comments = set(re.findall(r"comment on table\s+([a-z_]+\.[a-z_]+)\s+is", text))
print(f"table_comments={len(tbl_comments)} missing_tables={sorted(set(tables) - tbl_comments)}")

sys.exit(0 if not missing and not extra and not (set(tables) - tbl_comments) else 1)
