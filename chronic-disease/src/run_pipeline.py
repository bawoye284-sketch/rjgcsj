"""Audit data, train independent models and retain all performance artifacts."""

import argparse
from pathlib import Path

from audit_cohorts import run_audit
from train_models import run_training


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version")
    parser.add_argument("--bootstrap", type=int, default=500)
    parser.add_argument("--qa-scripts", type=Path)
    parser.add_argument("--recipe", choices=["baseline", "optimized"], default="optimized")
    parser.add_argument("--activate", action="store_true")
    args = parser.parse_args()
    run_audit()
    run_training(args.version, args.bootstrap, args.qa_scripts, args.recipe, args.activate)
