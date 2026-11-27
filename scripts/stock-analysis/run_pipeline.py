#!/usr/bin/env python3
"""CLI wrapper forwarding to the shared `functions-stock/stock_pipeline.py` module."""

from __future__ import annotations

import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
FUNCTIONS_STOCK_DIR = REPO_ROOT / "functions-stock"
if str(FUNCTIONS_STOCK_DIR) not in sys.path:
    sys.path.insert(0, str(FUNCTIONS_STOCK_DIR))

from stock_pipeline import *  # noqa: E402, F401, F403

if __name__ == "__main__":
    raise SystemExit(main())
