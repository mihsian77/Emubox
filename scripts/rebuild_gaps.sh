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
mkdir -p /workspace/项目库缺口
mv 缺口清单.csv "/workspace/项目库缺口/项目库缺口清单-${DATE}.csv"
mv 缺口清单.md  "/workspace/项目库缺口/项目库缺口清单-${DATE}.md"
mv 项目库缺口总表.html "/workspace/项目库缺口/项目库缺口总表-${DATE}.html" 2>/dev/null || true

echo "done: /workspace/项目库缺口/
# 产物一律搬到 /workspace，仓库根目录不留任何中间文件（见 AGENTS.md 仓库内容红线）
