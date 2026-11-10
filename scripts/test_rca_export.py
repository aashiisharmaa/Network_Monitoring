"""Real analyzer/cache/workbook regression checks. Run with the application's Python."""
import gzip
import json
import sys
import tempfile
import unittest
from contextlib import closing
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "python service"))
import main as rca
import rca_export
from openpyxl import load_workbook, Workbook


class RcaExportTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.source = self.root / "input.csv"
        self.source.write_text("date,cell,site,tech,sinr,cqi\n" + "".join(
            f"2026-03-{i // 2 + 1:02d},CELL_{i % 2},SITE,LTE,{12 if i < 10 else -4},{10 if i < 10 else 1}\n" for i in range(12)))
        self.config = {"vendor": "ericsson", "column_mapping": {"Short name": "cell", "Date": "date", "Cell I'd": "cell", "Site I'd": "site", "Tech": "tech"},
                       "kpi_mapping": {"MV_PUSCH SINR": "sinr", "MV_E-UTRAN Average CQI": "cqi"}, "selected_kpis": ["MV_PUSCH SINR", "MV_E-UTRAN Average CQI"], "response_row_limit": 1}
        self.bundle = self.root / "analysis.json.gz"

    def tearDown(self):
        self.directory.cleanup()

    def capture(self):
        return rca.run_rca(str(self.source), self.config, analysis_output_path=str(self.bundle))

    def test_export_reuses_full_analysis_and_saves_once_without_reading_workbook_again(self):
        result = self.capture()
        self.assertLessEqual(len(result["anomaly_details"]), 1)
        bundle = rca_export.read_bundle(self.bundle)
        self.assertEqual(len(bundle["periods"][0]["tables"]["all_scored"]["data"]), 12)
        output = self.root / "export.xlsx"
        original_save = Workbook.save
        with patch.object(rca.analyser, "analyze_frame", side_effect=AssertionError("RCA reran")), patch.object(rca, "load_workbook", side_effect=AssertionError("Workbook reopened")), patch.object(Workbook, "save", autospec=True, side_effect=original_save) as save:
            rca_export.write_workbook(bundle, output, rca._build_vendor_recommendation_row, rca._parameter_guide_rows)
            save.assert_called_once()
        with closing(load_workbook(output, read_only=True)) as book:
            self.assertEqual(sum(1 for _ in book["All_Data_Scored"].rows), 13)
            headers = [cell.value for cell in next(book["Anomaly_Details"].rows)]
            self.assertIn("Vendor Recommendation", headers)
            self.assertIn("Recommended Parameters", headers)
            self.assertNotIn("Parameter_Guide", book.sheetnames)  # Nokia-only reference is not attached to Ericsson.

    def test_pre_post_contains_both_full_scored_periods(self):
        self.config.update(date_mode="pre_post", pre_date_filter={"start_date": "2026-03-01", "end_date": "2026-03-03"}, post_date_filter={"start_date": "2026-03-04", "end_date": "2026-03-06"})
        self.capture()
        bundle = rca_export.read_bundle(self.bundle)
        self.assertEqual([period["period"] for period in bundle["periods"]], ["Pre", "Post"])
        output = self.root / "export.xlsx"
        rca_export.write_workbook(bundle, output, rca._build_vendor_recommendation_row, rca._parameter_guide_rows)
        with closing(load_workbook(output, read_only=True)) as book:
            for period in ["Pre", "Post"]:
                self.assertEqual(sum(1 for _ in book[period + "_All_Data_Scored"].rows), 7)

    def test_source_text_cannot_become_an_excel_formula_and_missing_stays_blank(self):
        self.capture()
        bundle = rca_export.read_bundle(self.bundle)
        table = bundle["periods"][0]["tables"]["all_scored"]
        index = table["columns"].index("Short name")
        table["data"][0][index] = '=HYPERLINK("https://example.invalid")'
        metric = table["columns"].index("MV_PUSCH SINR")
        table["data"][0][metric] = None
        output = self.root / "safe.xlsx"
        rca_export.write_workbook(bundle, output, rca._build_vendor_recommendation_row, rca._parameter_guide_rows)
        with closing(load_workbook(output)) as book:
            self.assertEqual(book["All_Data_Scored"].cell(2, index + 1).data_type, "s")
            self.assertIsNone(book["All_Data_Scored"].cell(2, metric + 1).value)

    def test_decompressed_bundle_size_is_bounded(self):
        with gzip.open(self.bundle, "wb") as stream:
            stream.write(b" " * 2048)
        with patch.object(rca_export, "MAX_BUNDLE_BYTES", 1024):
            with self.assertRaisesRegex(ValueError, "size limit"):
                rca_export.read_bundle(self.bundle)


if __name__ == "__main__":
    unittest.main()
