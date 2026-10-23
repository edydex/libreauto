#!/usr/bin/env python3
"""Verify both Gradle unit-test report sets and produce content-free CI totals."""
from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


PROJECT_ROOT = Path(__file__).resolve().parents[1]
MODULES = ("app", "projection")
FIELDS = ("tests", "failures", "errors", "skipped")


class ReportError(ValueError):
    pass


@dataclass
class Totals:
    tests: int = 0
    failures: int = 0
    errors: int = 0
    skipped: int = 0
    suites: int = 0

    def add(self, other: Totals) -> None:
        for field in (*FIELDS, "suites"):
            setattr(self, field, getattr(self, field) + getattr(other, field))


def read_suite(path: Path) -> Totals:
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as error:
        raise ReportError(f"Unreadable unit-test report: {path}") from error
    if root.tag != "testsuite":
        raise ReportError(f"Expected a Gradle testsuite report: {path}")
    counts = {}
    for field in FIELDS:
        value = root.get(field)
        if value is None or not value.isascii() or not value.isdecimal():
            raise ReportError(f"Invalid or missing {field} count in {path}")
        counts[field] = int(value)
    cases = root.findall("testcase")
    observed = {"tests": len(cases)}
    for field, element in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped")):
        observed[field] = sum(case.find(element) is not None for case in cases)
    if counts != observed:
        raise ReportError(f"Declared counts do not match testcase results in {path}")
    return Totals(**counts, suites=1)


def collect_reports(project_root: Path) -> tuple[dict[str, Totals], Totals]:
    modules = {}
    total = Totals()
    for module in MODULES:
        directory = project_root / module / "build/test-results/testDebugUnitTest"
        reports = sorted(directory.glob("TEST-*.xml"))
        if not reports:
            raise ReportError(f"Missing {module} unit-test XML reports")
        subtotal = Totals()
        for report in reports:
            subtotal.add(read_suite(report))
        if subtotal.tests <= subtotal.skipped:
            raise ReportError(f"No executed unit tests in {module}")
        modules[module] = subtotal
        total.add(subtotal)
    return modules, total


def write_reports(modules: dict[str, Totals], total: Totals, json_path: Path, summary_path: Path) -> None:
    json_path.parent.mkdir(parents=True, exist_ok=True)
    summary_path.parent.mkdir(parents=True, exist_ok=True)
    json_path.write_text(json.dumps({"modules": {name: asdict(counts) for name, counts in modules.items()},
                                     "total": asdict(total)}, indent=2) + "\n")
    rows = ["| Module | Tests | Failures | Errors | Skipped | Suites |",
            "| --- | ---: | ---: | ---: | ---: | ---: |"]
    for name, counts in (*modules.items(), ("Total", total)):
        rows.append(f"| {name} | {counts.tests} | {counts.failures} | {counts.errors} | {counts.skipped} | {counts.suites} |")
    summary_path.write_text("\n".join(rows) + "\n")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", type=Path, default=PROJECT_ROOT)
    parser.add_argument("--json", type=Path, required=True)
    parser.add_argument("--summary", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        modules, total = collect_reports(args.project_root)
        write_reports(modules, total, args.json, args.summary)
    except ReportError as error:
        print(f"Unit-test report verification failed: {error}", file=sys.stderr)
        return 1
    print(f"{total.tests} unit tests: {total.failures} failures, {total.errors} errors, {total.skipped} skipped")
    return 1 if total.failures or total.errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
