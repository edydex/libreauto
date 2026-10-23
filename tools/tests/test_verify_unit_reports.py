import contextlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import verify_unit_reports as reports


def suite(path, results=("pass",), **overrides):
    counts = {"tests": len(results), "failures": results.count("failure"),
              "errors": results.count("error"), "skipped": results.count("skipped")}
    counts.update(overrides)
    root = ET.Element("testsuite", {name: str(value) for name, value in counts.items()})
    for index, result in enumerate(results):
        case = ET.SubElement(root, "testcase", name=f"case{index}")
        if result != "pass":
            ET.SubElement(case, result)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(ET.tostring(root))


class UnitReportTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name)

    def report(self, module, name="Example"):
        return self.root / module / f"build/test-results/testDebugUnitTest/TEST-{name}.xml"

    def run_cli(self):
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return reports.main(["--project-root", str(self.root), "--json", str(self.root / "out/totals.json"),
                                 "--summary", str(self.root / "out/summary.md")])

    def test_counts_both_modules_and_multiple_suites_without_double_counting(self):
        suite(self.report("app"), ("pass", "pass"))
        suite(self.report("app", "Second"), ("pass", "skipped"))
        suite(self.report("projection"), ("pass",))
        self.assertEqual(0, self.run_cli())
        totals = json.loads((self.root / "out/totals.json").read_text())
        self.assertEqual({"tests": 5, "failures": 0, "errors": 0, "skipped": 1, "suites": 3}, totals["total"])
        self.assertIn("| Total | 5 | 0 | 0 | 1 | 3 |", (self.root / "out/summary.md").read_text())

    def test_missing_module_reports_fail_even_when_other_module_passes(self):
        suite(self.report("app"))
        self.assertEqual(1, self.run_cli())

    def test_zero_executed_tests_fail(self):
        for results in ((), ("skipped",)):
            with self.subTest(results=results):
                suite(self.report("app"), results)
                suite(self.report("projection"))
                self.assertEqual(1, self.run_cli())

    def test_failure_and_error_exit_nonzero_but_keep_useful_totals(self):
        suite(self.report("app"), ("pass", "failure"))
        suite(self.report("projection"), ("error",))
        self.assertEqual(1, self.run_cli())
        total = json.loads((self.root / "out/totals.json").read_text())["total"]
        self.assertEqual(1, total["failures"])
        self.assertEqual(1, total["errors"])

    def test_truncated_or_wrong_root_xml_fails(self):
        for raw in ("<testsuite", "<testsuites/>"):
            with self.subTest(raw=raw):
                path = self.report("app")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(raw)
                with self.assertRaises(reports.ReportError):
                    reports.read_suite(path)

    def test_invalid_negative_or_missing_declared_count_fails(self):
        for value in ("not a number", "-1", "", "１"):
            with self.subTest(value=value):
                suite(self.report("app"), tests=value)
                with self.assertRaises(reports.ReportError):
                    reports.read_suite(self.report("app"))
        suite(self.report("app"))
        xml = ET.parse(self.report("app"))
        del xml.getroot().attrib["skipped"]
        xml.write(self.report("app"))
        with self.assertRaises(reports.ReportError):
            reports.read_suite(self.report("app"))

    def test_metadata_cannot_hide_actual_failures_or_missing_cases(self):
        for results, overrides in ((("failure",), {"failures": 0}), (("pass",), {"tests": 2})):
            with self.subTest(results=results):
                suite(self.report("app"), results, **overrides)
                with self.assertRaises(reports.ReportError):
                    reports.read_suite(self.report("app"))


if __name__ == "__main__":
    unittest.main()
