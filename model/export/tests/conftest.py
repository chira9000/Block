"""Shared test setup: make reference_scorer.py importable and give tests the key folders."""
import sys
from pathlib import Path

EXPORT_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(EXPORT_DIR))

TEST_CASES_DIR = EXPORT_DIR / "test_cases"
NOTEBOOK_DIR = EXPORT_DIR.parent / "notebooks"
