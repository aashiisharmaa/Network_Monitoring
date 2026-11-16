"""Real RCA period-mean regression; temporary synthetic inputs only."""
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "python service"))
import main as rca
import rca_export


class PeriodMeanTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.source = self.root / "input.csv"
        self.source.write_text(
            "date,cell,site,tech,sinr,cqi\n"
            "2026-03-01,A,SITE,LTE,0,1\n"
            "2026-03-02,A,SITE,LTE,3,1\n"
            "2026-03-03,A,SITE,LTE,9,1\n"
            "2026-03-03,A,SITE,LTE,,1\n"
            "2026-03-04,A,SITE,LTE,1000,1\n"
            "2026-03-01,B,SITE,LTE,-20,1\n"
            "2026-03-02,B,SITE,LTE,-10,1\n"
        )
        self.config = {
            "vendor": "ericsson",
            "column_mapping": {"Short name": "cell", "Date": "date", "Cell I'd": "cell", "Site I'd": "site", "Tech": "tech"},
            "kpi_mapping": {"MV_PUSCH SINR": "sinr", "MV_E-UTRAN Average CQI": "cqi"},
            "selected_kpis": ["MV_PUSCH SINR", "MV_E-UTRAN Average CQI"],
        }

    def tearDown(self):
        self.directory.cleanup()

    def capture(self):
        bundle = self.root / "analysis.json.gz"
        rca.run_rca(str(self.source), self.config, analysis_output_path=str(bundle))
        return rca_export.read_bundle(bundle)["periods"]

    @staticmethod
    def records(period):
        table = period["tables"]["all_scored"]
        return [dict(zip(table["columns"], row)) for row in table["data"]]

    def test_mean_keeps_zero_and_negative_values_and_excludes_invalid_values(self):
        self.assertEqual(rca.analyser.mean_skipna(pd.Series([0, 6, None, np.inf, -np.inf, "bad"])), 3)
        self.assertEqual(rca.analyser.mean_skipna(pd.Series([-4, 0])), -2)
        self.assertTrue(pd.isna(rca.analyser.mean_skipna(pd.Series([None, np.inf]))))

    def test_single_period_groups_by_cell_and_respects_date_filter(self):
        self.config["date_filter"] = {"start_date": "2026-03-01", "end_date": "2026-03-03"}
        rows = self.records(self.capture()[0])
        a = next(row for row in rows if row["Short name"] == "A" and row["MV_PUSCH SINR"] == 0)
        b = next(row for row in rows if row["Short name"] == "B")
        self.assertIn("PUSCH SINR (dB): 0.00 vs median 3.00 (mean 4.00)", a["Evidence"])
        self.assertIn("(mean -15.00)", b["Evidence"])

    def test_pre_and_post_have_separate_means(self):
        self.config.update(date_mode="pre_post",
                           pre_date_filter={"start_date": "2026-03-01", "end_date": "2026-03-02"},
                           post_date_filter={"start_date": "2026-03-03", "end_date": "2026-03-04"})
        periods = {period["period"]: self.records(period) for period in self.capture()}
        pre = next(row for row in periods["Pre"] if row["Short name"] == "A" and row["MV_PUSCH SINR"] == 0)
        post = next(row for row in periods["Post"] if row["Short name"] == "A" and row["MV_PUSCH SINR"] == 9)
        self.assertIn("(mean 1.50)", pre["Evidence"])
        self.assertIn("(mean 504.50)", post["Evidence"])

    def test_reporting_mean_does_not_change_scoring_or_causes(self):
        first = self.records(self.capture()[0])
        with patch.object(rca.analyser, "mean_skipna", return_value=999):
            second = self.records(self.capture()[0])
        keys = ["Short name", "Date", "Anomaly Score", "Severity", "Primary Cause", "Secondary Causes", "Is Anomaly"]
        self.assertEqual([[row[key] for key in keys] for row in first], [[row[key] for key in keys] for row in second])


if __name__ == "__main__":
    unittest.main()
