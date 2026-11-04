#!/usr/bin/env bash
# 缺口流水线一键重建：验证 → 审计 → 合表 → 出总表/清单
# 用法: bash scripts/rebuild_gaps.sh
set -euo pipefail
cd "$(dirname "$0")/.."

echo "[1/6] validate"
python3 scripts/validate-emulators.py

echo "[2/6] audit (约 90s)"
python3 scripts/audit_registry.py

echo "[3/6] rows"
python3 scripts/build_gap_rows.py

echo "[4/6] board"
python3 scripts/gen_gap_board.py

echo "[5/6] markdown"
python3 scripts/gen_gap_md.py

echo "[6/6] csv"
python3 scripts/gen_gap_report.py
DATE=$(date +%Y%m%d)
cp 缺口清单.csv "/workspace/项目库缺口清单-${DATE}.csv"
cp 缺口清单.md  "/workspace/项目库缺口清单-${DATE}.md"

echo "done: /workspace/项目库缺口总表.html"
