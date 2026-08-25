import argparse
import json
import re
import warnings
import tempfile
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import numpy as np
import pandas as pd
from openpyxl import load_workbook
from openpyxl.styles import Alignment

import analyser
import lb_service


PRESETS_FILE = Path(__file__).with_name("rca_presets.json")
DEFAULT_VENDOR = "nokia"
BASE_FIELDS = ["Short name", "Date", "Cell I'd", "Site I'd", "Tech"]
DEFAULT_EXPORT_NAME = "vendor_kpi_anomaly_rca_output.xlsx"
DEFAULT_RESPONSE_ROW_LIMIT = 2000
MAX_RESPONSE_ROW_LIMIT = 5000
DATE_PARSE_FORMATS = (
    "%d-%b-%y",
    "%d-%b-%Y",
    "%Y-%m-%d",
    "%Y/%m/%d",
    "%d/%m/%Y",
    "%m/%d/%Y",
    "%d-%m-%Y",
    "%m-%d-%Y",
    "%Y-%m-%d %H:%M:%S",
    "%Y-%m-%d %H:%M:%S.%f",
    "%Y-%m-%dT%H:%M",
    "%Y-%m-%dT%H:%M:%S",
    "%Y-%m-%dT%H:%M:%S.%f",
)


def _json_default(value: Any) -> Any:
    if isinstance(value, (pd.Timestamp, np.datetime64)):
        return pd.Timestamp(value).isoformat()
    if isinstance(value, (np.integer,)):
        return int(value)
    if isinstance(value, (np.floating,)):
        return float(value)
    if isinstance(value, (np.bool_,)):
        return bool(value)
    return str(value)


def _to_records(df: pd.DataFrame) -> List[Dict[str, Any]]:
    if df is None or df.empty:
        return []
    clean = df.replace({np.nan: None})
    return clean.to_dict(orient="records")


def _positive_int(value: Any, default: int, maximum: int) -> int:
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        parsed = default
    if parsed <= 0:
        return default
    return min(parsed, maximum)


def _normalize_name(value: Any) -> str:
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9]+", " ", str(value or "").lower())).strip()


def _is_date_candidate_name(value: Any) -> bool:
    normalized = _normalize_name(value)
    if not normalized:
        return False
    return (
        normalized == "date"
        or " date " in f" {normalized} "
        or normalized.startswith("date")
        or normalized.endswith("date")
        or "datetime" in normalized
        or normalized in {"time", "day"}
    )


def _clean_datetime_strings(values: pd.Series) -> pd.Series:
    text = values.astype("string").str.strip()
    return text.replace(
        {
            "": pd.NA,
            "nan": pd.NA,
            "NaN": pd.NA,
            "None": pd.NA,
            "null": pd.NA,
            "NULL": pd.NA,
            "<NA>": pd.NA,
        }
    )


def _to_datetime_safely(values: Any, **kwargs: Any) -> pd.Series:
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", UserWarning)
        return pd.to_datetime(values, **kwargs)


def _parse_datetime_series(values: Any) -> pd.Series:
    if values is None:
        return pd.Series(dtype="datetime64[ns]")
    if isinstance(values, pd.DataFrame):
        if values.empty:
            return pd.Series(dtype="datetime64[ns]")
        values = values.iloc[:, 0]
    if not isinstance(values, pd.Series):
        values = pd.Series(values)
    if values.empty:
        return pd.Series(dtype="datetime64[ns]")

    parsed = _to_datetime_safely(values, errors="coerce")
    if parsed.notna().any() or values.dropna().empty:
        return parsed

    text = _clean_datetime_strings(values)
    parsed = _to_datetime_safely(text, errors="coerce")
    if parsed.notna().any():
        return parsed

    numeric = pd.to_numeric(text, errors="coerce")
    if numeric.notna().any():
        plausible = numeric.between(20000, 80000)
        if plausible.any():
            serial = pd.Series(pd.NaT, index=text.index, dtype="datetime64[ns]")
            serial.loc[plausible] = pd.Timestamp("1899-12-30") + pd.to_timedelta(numeric.loc[plausible], unit="D")
            if serial.notna().any():
                return serial

    for fmt in DATE_PARSE_FORMATS:
        parsed = _to_datetime_safely(text, errors="coerce", format=fmt)
        if parsed.notna().any():
            return parsed

    parsed = _to_datetime_safely(text, errors="coerce", dayfirst=True)
    return parsed


def _select_best_date_series(raw_df: pd.DataFrame, mapped_source: Optional[str]) -> pd.Series:
    candidates: List[Tuple[str, pd.Series]] = []
    seen: set[str] = set()

    def add_candidate(column_name: Any) -> None:
        if column_name is None:
            return
        column_text = str(column_name)
        if column_text in seen or column_text not in raw_df.columns:
            return
        seen.add(column_text)
        candidates.append((column_text, raw_df[column_text]))

    add_candidate(mapped_source)
    for column_name in raw_df.columns:
        if _is_date_candidate_name(column_name):
            add_candidate(column_name)

    best_series: Optional[pd.Series] = None
    best_score = -1

    for column_name, candidate in candidates:
        parsed = _parse_datetime_series(candidate)
        parsed_count = int(parsed.notna().sum())
        if parsed_count == 0:
            continue

        normalized_name = _normalize_name(column_name)
        score = parsed_count
        if normalized_name == "date":
            score += 10000
        elif "datetime" in normalized_name:
            score += 9000
        elif "time" in normalized_name:
            score += 8000
        elif "date" in normalized_name:
            score += 7000
        elif normalized_name == "day":
            score += 6000

        if not _is_date_candidate_name(column_name) and pd.api.types.is_numeric_dtype(candidate):
            score -= 100000

        if score > best_score:
            best_score = score
            best_series = parsed

    if best_series is not None:
        return best_series
    return pd.Series(dtype="datetime64[ns]")


def _safe_float(value: Any, fallback: float) -> float:
    if value is None:
        return fallback
    try:
        return float(value)
    except (TypeError, ValueError):
        return fallback


def _read_config(path: str) -> Dict[str, Any]:
    with open(path, "r", encoding="utf-8") as fp:
        return json.load(fp)


def _read_input_frame(input_path: str, sheet_name: str = None) -> pd.DataFrame:
    source = Path(input_path)
    if not source.exists():
        raise FileNotFoundError(f"Input file not found: {input_path}")
    if source.suffix.lower() == ".csv":
        return pd.read_csv(source)
    if source.suffix.lower() in {".xlsx", ".xlsm", ".xls"}:
        return pd.read_excel(source, sheet_name=sheet_name) if sheet_name else pd.read_excel(source)
    raise ValueError("Unsupported input format. Use .csv, .xlsx, .xlsm, or .xls")


def _build_kpi_meta(config: Dict[str, Any]) -> Dict[str, Dict[str, Any]]:
    base_meta = dict(analyser.KPI_META)
    kpi_config = config.get("kpis", {})
    selected = set(config.get("selected_kpis", list(kpi_config.keys())))
    if not selected:
        selected = set(base_meta.keys())

    final_meta: Dict[str, Dict[str, Any]] = {}
    for kpi_name, base in base_meta.items():
        if kpi_name not in selected:
            continue
        override = kpi_config.get(kpi_name, {})
        final_meta[kpi_name] = {
            "direction": override.get("direction", base["direction"]),
            "threshold": _safe_float(override.get("threshold"), base["threshold"]),
            "weight": _safe_float(override.get("weight"), base["weight"]),
            "min_scale": _safe_float(override.get("min_scale"), base["min_scale"]),
            "number_format": override.get("number_format", base["number_format"]),
            "friendly": override.get("friendly", base["friendly"]),
            "rule": override.get("rule", base["rule"]),
        }
    return final_meta


def _build_threshold_overrides(config: Dict[str, Any]) -> Tuple[Dict[str, float], Dict[str, float], Dict[str, float]]:
    rca_in = config.get("rca_thresholds") or config.get("advanced_thresholds") or {}
    severity_in = config.get("severity_thresholds") or {}
    anomaly_in = config.get("anomaly_thresholds") or {}

    rca_rules = dict(analyser.RCA_RULE_DEFAULTS)
    for key, default_val in analyser.RCA_RULE_DEFAULTS.items():
        if key in rca_in:
            rca_rules[key] = _safe_float(rca_in.get(key), default_val)

    severity_rules = dict(analyser.SEVERITY_DEFAULTS)
    for key, default_val in analyser.SEVERITY_DEFAULTS.items():
        if key in severity_in:
            severity_rules[key] = _safe_float(severity_in.get(key), default_val)

    anomaly_rules = dict(analyser.ANOMALY_DEFAULTS)
    for key, default_val in analyser.ANOMALY_DEFAULTS.items():
        if key in anomaly_in:
            anomaly_rules[key] = _safe_float(anomaly_in.get(key), default_val)

    return rca_rules, severity_rules, anomaly_rules


def _filter_df_by_range(
    df: pd.DataFrame,
    start_raw: Any,
    end_raw: Any,
    label: str = "date",
    allow_empty: bool = False,
) -> Tuple[pd.DataFrame, Dict[str, Any]]:
    start_ts = pd.to_datetime(start_raw, errors="coerce") if start_raw else None
    end_ts = pd.to_datetime(end_raw, errors="coerce") if end_raw else None
    if start_raw and pd.isna(start_ts):
        raise ValueError(f"Invalid {label} start_date: {start_raw}")
    if end_raw and pd.isna(end_ts):
        raise ValueError(f"Invalid {label} end_date: {end_raw}")
    if end_ts is not None and re.fullmatch(r"\d{4}-\d{2}-\d{2}", str(end_raw or "").strip()):
        end_ts = end_ts + pd.Timedelta(days=1) - pd.Timedelta(microseconds=1)
    if start_ts is not None and end_ts is not None and start_ts > end_ts:
        raise ValueError(f"{label} start_date cannot be greater than end_date.")

    rows_before = int(len(df))
    filtered = df
    if start_ts is not None:
        filtered = filtered[filtered["Date"] >= start_ts]
    if end_ts is not None:
        filtered = filtered[filtered["Date"] <= end_ts]
    filtered = filtered.reset_index(drop=True)

    rows_after = int(len(filtered))
    if rows_after == 0 and not allow_empty:
        selected_start = start_ts.date().isoformat() if start_ts is not None else "first available date"
        selected_end = end_ts.date().isoformat() if end_ts is not None else "last available date"
        available_dates = pd.to_datetime(df["Date"], errors="coerce").dropna() if "Date" in df.columns else pd.Series(dtype="datetime64[ns]")
        available_text = ""
        if not available_dates.empty:
            available_text = (
                f" Available data range is {available_dates.min().date().isoformat()} "
                f"to {available_dates.max().date().isoformat()}."
            )
        raise ValueError(
            f"No rows available after applying {label} date filter "
            f"({selected_start} to {selected_end}).{available_text}"
        )

    return filtered, {
        "start_date": start_ts.date().isoformat() if start_ts is not None else None,
        "end_date": end_ts.date().isoformat() if end_ts is not None else None,
        "rows_before_filter": rows_before,
        "rows_after_filter": rows_after,
    }


def _apply_date_filter(df: pd.DataFrame, config: Dict[str, Any]) -> Tuple[pd.DataFrame, Dict[str, Any]]:
    date_filter = config.get("date_filter") or {}
    return _filter_df_by_range(
        df=df,
        start_raw=date_filter.get("start_date"),
        end_raw=date_filter.get("end_date"),
        label="single",
        allow_empty=False,
    )


def _normalize_frame(raw_df: pd.DataFrame, config: Dict[str, Any], final_meta: Dict[str, Dict[str, Any]]) -> Tuple[pd.DataFrame, List[str], List[str]]:
    column_mapping = config.get("column_mapping", {})
    kpi_mapping = config.get("kpi_mapping", {})

    normalized = pd.DataFrame(index=raw_df.index)
    missing_required: List[str] = []
    missing_kpis: List[str] = []

    # Base fields are mandatory (manual mapping expected from UI)
    for expected_col in BASE_FIELDS:
        source_col = column_mapping.get(expected_col)
        if source_col and source_col in raw_df.columns:
            if expected_col == "Date":
                normalized[expected_col] = _select_best_date_series(raw_df, source_col)
            else:
                normalized[expected_col] = raw_df[source_col]
        else:
            if expected_col == "Date":
                normalized[expected_col] = _select_best_date_series(raw_df, None)
                if normalized[expected_col].notna().any():
                    continue
            missing_required.append(expected_col)
            normalized[expected_col] = np.nan

    # Keep analyzer-compatible optional columns so downstream sheet creation never fails.
    for expected_col in analyser.SELECTED_COLS:
        if expected_col in BASE_FIELDS:
            continue
        source_col = kpi_mapping.get(expected_col)
        if source_col and source_col in raw_df.columns:
            normalized[expected_col] = raw_df[source_col]
        else:
            if expected_col in final_meta:
                missing_kpis.append(expected_col)
            normalized[expected_col] = np.nan

    return normalized, missing_required, missing_kpis


def _build_vendor_recommendation_row(vendor: str, row: pd.Series) -> Dict[str, str]:
    primary = row.get("Primary Cause")
    secondary = row.get("Secondary Causes")
    evidence = row.get("Evidence")
    drivers = row.get("Top KPI Drivers")
    if vendor == "ericsson":
        params, values, notes = analyser.build_ericsson_parameter_recommendation(primary, secondary, evidence, drivers)
        text = "Ericsson recommendation generated from detected RCA pattern."
    else:
        params, values, notes = analyser.build_nokia_parameter_recommendation(primary, secondary, evidence, drivers)
        text = row.get("Nokia Recommendation", "Nokia recommendation generated from detected RCA pattern.")
    params = str(params or "").replace("\\n", "\n")
    values = str(values or "").replace("\\n", "\n")
    notes = str(notes or "").replace("\\n", "\n")

    return {
        "Vendor Recommendation": text,
        "Recommended Parameters": params,
        "Recommended Values": values,
        "Implementation Notes": notes,
    }


def _parameter_guide_rows() -> List[Dict[str, str]]:
    return [
        {
            "Cause Bucket": "Mobility",
            "Common Nokia / 3GPP Parameter or Feature": "a3Offset / a3TimeToTrigger / hysteresisA3",
            "Suggested Initial Value / Action": "2-3 dB / 320-480 ms / 1-2 dB",
            "Use Case / Caution": "Use for late HO, HOF, ping-pong tuning; validate with HOF and ping-pong split",
        },
        {
            "Cause Bucket": "Mobility",
            "Common Nokia / 3GPP Parameter or Feature": "cellIndividualOffsetEUTRA",
            "Suggested Initial Value / Action": "+/-1 to +/-3 dB neighbour bias",
            "Use Case / Caution": "Useful for traffic steering and edge balancing",
        },
        {
            "Cause Bucket": "Coverage",
            "Common Nokia / 3GPP Parameter or Feature": "Electrical tilt (RET)",
            "Suggested Initial Value / Action": "+/-1.0 deg to +/-2.0 deg trial",
            "Use Case / Caution": "Most practical first RF action for coverage hole or overshoot",
        },
        {
            "Cause Bucket": "Coverage",
            "Common Nokia / 3GPP Parameter or Feature": "Azimuth",
            "Suggested Initial Value / Action": "5 deg to 15 deg only after map validation",
            "Use Case / Caution": "Avoid changing without geo evidence",
        },
        {
            "Cause Bucket": "Coverage",
            "Common Nokia / 3GPP Parameter or Feature": "RS power / ref signal power",
            "Suggested Initial Value / Action": "+1 to +2 dB where allowed",
            "Use Case / Caution": "Use only after interference and licence check",
        },
        {
            "Cause Bucket": "UL Interference",
            "Common Nokia / 3GPP Parameter or Feature": "p0NominalPUSCH / p0NominalPUCCH",
            "Suggested Initial Value / Action": "-1 to -2 dB / -1 dB step",
            "Use Case / Caution": "Monitor UL SINR, BLER and coverage after each change",
        },
        {
            "Cause Bucket": "UL Interference",
            "Common Nokia / 3GPP Parameter or Feature": "Interference-aware UL scheduling",
            "Suggested Initial Value / Action": "Enable / verify active",
            "Use Case / Caution": "Helpful when UL NI / RSSI is high",
        },
        {
            "Cause Bucket": "Congestion",
            "Common Nokia / 3GPP Parameter or Feature": "MLB / Scheduler profile / CA-MIMO check",
            "Suggested Initial Value / Action": "Enable or verify active",
            "Use Case / Caution": "Use when PRB is high and throughput is low",
        },
        {
            "Cause Bucket": "Accessibility",
            "Common Nokia / 3GPP Parameter or Feature": "Admission control / RRC-ERAB timer audit",
            "Suggested Initial Value / Action": "Keep near vendor baseline",
            "Use Case / Caution": "Tune only after identifying whether failures are RF, transport, licence or core driven",
        },
        {
            "Cause Bucket": "Transport",
            "Common Nokia / 3GPP Parameter or Feature": "Backhaul / QoS / queue check",
            "Suggested Initial Value / Action": "Target near-zero packet loss",
            "Use Case / Caution": "Packet loss is often transport plus RF, not RF alone",
        },
    ]


def _run_analyser_on_frame(
    input_df: pd.DataFrame,
    output_path: Path,
    final_meta: Dict[str, Dict[str, Any]],
    rca_rules: Dict[str, float],
    severity_rules: Dict[str, float],
    anomaly_rules: Dict[str, float],
    export_excel: bool = False,
) -> pd.DataFrame:
    original_cols = list(analyser.SELECTED_COLS)
    original_meta = dict(analyser.KPI_META)
    original_rca_rules = dict(analyser.RCA_RULES)
    original_severity_rules = dict(analyser.SEVERITY_RULES)
    original_anomaly_rules = dict(analyser.ANOMALY_RULES)

    try:
        analyser.SELECTED_COLS = list(analyser.SELECTED_COLS)
        analyser.KPI_META = final_meta
        analyser.FRIENDLY_MAP = {k: v["friendly"] for k, v in final_meta.items()}
        analyser.RCA_RULES = rca_rules
        analyser.SEVERITY_RULES = severity_rules
        analyser.ANOMALY_RULES = anomaly_rules
        frames = analyser.analyze_frame(input_df)
        if export_excel:
            _write_fast_excel_export(frames, output_path)
        return frames["anomaly_details"]
    finally:
        analyser.SELECTED_COLS = original_cols
        analyser.KPI_META = original_meta
        analyser.FRIENDLY_MAP = {k: v["friendly"] for k, v in original_meta.items()}
        analyser.RCA_RULES = original_rca_rules
        analyser.SEVERITY_RULES = original_severity_rules
        analyser.ANOMALY_RULES = original_anomaly_rules


def _write_fast_excel_export(frames: Dict[str, pd.DataFrame], output_path: Path) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    sheet_map = [
        ("Anomaly_Details", "anomaly_details"),
        ("All_Data_Scored", "all_scored"),
        ("Root_Cause_Summary", "summary_root"),
        ("Top_Impacted_Cells", "summary_cells"),
        ("Date_Trend", "summary_dates"),
        ("Rules_Thresholds", "rules_df"),
    ]
    with pd.ExcelWriter(output_path, engine="openpyxl") as writer:
        for sheet_name, frame_key in sheet_map:
            frame = frames.get(frame_key)
            if isinstance(frame, pd.DataFrame):
                frame.to_excel(writer, sheet_name=sheet_name, index=False)


def _run_analyser_on_frame_legacy_excel(
    input_df: pd.DataFrame,
    output_path: Path,
    final_meta: Dict[str, Dict[str, Any]],
    rca_rules: Dict[str, float],
    severity_rules: Dict[str, float],
    anomaly_rules: Dict[str, float],
) -> pd.DataFrame:

    temp_input = Path(tempfile.gettempdir()) / f"rca_input_{next(tempfile._get_candidate_names())}.xlsx"
    input_df.to_excel(temp_input, index=False)

    original_input = analyser.INPUT_PATH
    original_output = analyser.OUTPUT_XLSX
    original_cols = list(analyser.SELECTED_COLS)
    original_meta = dict(analyser.KPI_META)
    original_rca_rules = dict(analyser.RCA_RULES)
    original_severity_rules = dict(analyser.SEVERITY_RULES)
    original_anomaly_rules = dict(analyser.ANOMALY_RULES)

    try:
        analyser.INPUT_PATH = str(temp_input)
        analyser.OUTPUT_XLSX = str(output_path)
        analyser.SELECTED_COLS = list(analyser.SELECTED_COLS)
        analyser.KPI_META = final_meta
        analyser.FRIENDLY_MAP = {k: v["friendly"] for k, v in final_meta.items()}
        analyser.RCA_RULES = rca_rules
        analyser.SEVERITY_RULES = severity_rules
        analyser.ANOMALY_RULES = anomaly_rules
        analyser.build_output(input_path=str(temp_input), output_xlsx=str(output_path))
    finally:
        analyser.INPUT_PATH = original_input
        analyser.OUTPUT_XLSX = original_output
        analyser.SELECTED_COLS = original_cols
        analyser.KPI_META = original_meta
        analyser.FRIENDLY_MAP = {k: v["friendly"] for k, v in original_meta.items()}
        analyser.RCA_RULES = original_rca_rules
        analyser.SEVERITY_RULES = original_severity_rules
        analyser.ANOMALY_RULES = original_anomaly_rules
        if temp_input.exists():
            temp_input.unlink(missing_ok=True)

    return pd.read_excel(output_path, sheet_name="Anomaly_Details")


def _build_pre_post_worsened_rows(
    pre_rows: List[Dict[str, Any]],
    post_rows: List[Dict[str, Any]],
    pre_filter_meta: Optional[Dict[str, Any]] = None,
    post_filter_meta: Optional[Dict[str, Any]] = None,
    top_n: Optional[int] = None,
) -> List[Dict[str, Any]]:
    debug_short_name = "VESTVR001"

    if not pre_rows or not post_rows:
        return []

    pre_df = pd.DataFrame(pre_rows)
    post_df = pd.DataFrame(post_rows)
    if pre_df.empty or post_df.empty:
        return []
    if "Short name" not in pre_df.columns or "Short name" not in post_df.columns:
        return []
    if "Anomaly Score" not in pre_df.columns or "Anomaly Score" not in post_df.columns:
        return []

    def _build_selected_dates(filter_meta: Optional[Dict[str, Any]]) -> List[pd.Timestamp]:
        start_raw = filter_meta.get("start_date") if isinstance(filter_meta, dict) else None
        end_raw = filter_meta.get("end_date") if isinstance(filter_meta, dict) else None
        start_ts = pd.to_datetime(start_raw, errors="coerce") if start_raw else None
        end_ts = pd.to_datetime(end_raw, errors="coerce") if end_raw else None
        if start_ts is None or end_ts is None or pd.isna(start_ts) or pd.isna(end_ts):
            return []
        if start_ts > end_ts:
            return []
        return list(pd.date_range(start=start_ts, end=end_ts, freq="D"))

    def _build_period_avg(
        frame: pd.DataFrame,
        output_name: str,
        filter_meta: Optional[Dict[str, Any]],
    ) -> pd.DataFrame:
        score_frame = frame.copy()
        if "Date" in score_frame.columns:
            score_frame["Date"] = pd.to_datetime(
                score_frame["Date"],
                errors="coerce",
            ).dt.normalize()

        group_cols = ["Short name"]
        if "Date" in score_frame.columns:
            group_cols.append("Date")

        daily_scores = (
            score_frame.groupby(group_cols, dropna=False)["Anomaly Score"]
            .mean()
            .reset_index()
        )

        selected_dates = _build_selected_dates(filter_meta)
        if selected_dates:
            selected_date_index = pd.DatetimeIndex(selected_dates).normalize()
            daily_scores = daily_scores[
                daily_scores["Date"].isin(selected_date_index)
            ].reset_index(drop=True)
            if daily_scores.empty:
                return pd.DataFrame(columns=["Short name", output_name])

        return (
            daily_scores.groupby("Short name", dropna=False)["Anomaly Score"]
            .mean()
            .rename(output_name)
            .reset_index()
        )

    def _collect_debug_daily_values(
        frame: pd.DataFrame,
        filter_meta: Optional[Dict[str, Any]],
        short_name: str,
    ) -> List[Dict[str, Any]]:
        score_frame = frame.copy()
        if "Date" in score_frame.columns:
            score_frame["Date"] = pd.to_datetime(
                score_frame["Date"],
                errors="coerce",
            ).dt.normalize()

        daily_scores = (
            score_frame.groupby(["Short name", "Date"], dropna=False)["Anomaly Score"]
            .mean()
            .reset_index()
        )
        selected_dates = _build_selected_dates(filter_meta)
        if selected_dates:
            selected_date_index = pd.DatetimeIndex(selected_dates).normalize()
            daily_scores = daily_scores[
                daily_scores["Date"].isin(selected_date_index)
            ].reset_index(drop=True)

        debug_scores = daily_scores[
            daily_scores["Short name"].astype(str) == str(short_name)
        ].copy()
        if debug_scores.empty:
            return []

        debug_scores["Date"] = debug_scores["Date"].dt.strftime("%Y-%m-%d")
        debug_scores["Anomaly Score"] = debug_scores["Anomaly Score"].round(4)
        return debug_scores.to_dict(orient="records")

    pre_avg = _build_period_avg(pre_df, "pre_avg", pre_filter_meta)
    post_avg = _build_period_avg(post_df, "post_avg", post_filter_meta)
    merged = pre_avg.merge(post_avg, on="Short name", how="inner")
    if merged.empty:
        return []

    debug_row = merged[merged["Short name"].astype(str) == debug_short_name]
    if not debug_row.empty:
        debug_entry = debug_row.iloc[0]
        print(
            "[RECOMMENDATION][DEBUG_AVG]",
            json.dumps(
                {
                    "short_name": debug_short_name,
                    "pre_filter": pre_filter_meta,
                    "post_filter": post_filter_meta,
                    "pre_daily_scores": _collect_debug_daily_values(
                        pre_df,
                        pre_filter_meta,
                        debug_short_name,
                    ),
                    "post_daily_scores": _collect_debug_daily_values(
                        post_df,
                        post_filter_meta,
                        debug_short_name,
                    ),
                    "computed_pre_avg": round(float(debug_entry["pre_avg"]), 4),
                    "computed_post_avg": round(float(debug_entry["post_avg"]), 4),
                    "computed_delta": round(float(debug_entry["post_avg"] - debug_entry["pre_avg"]), 4),
                },
                default=_json_default,
            ),
            flush=True,
        )

    merged["delta"] = merged["post_avg"] - merged["pre_avg"]
    worsened = merged[merged["delta"] > 0].copy()
    improved = merged[merged["delta"] < 0].copy()

    worsened = worsened.sort_values(["delta", "post_avg"], ascending=[False, False])
    improved = improved.sort_values(["delta", "post_avg"], ascending=[True, False])
    if isinstance(top_n, int) and top_n > 0:
        worsened = worsened.head(top_n)
        improved = improved.head(top_n)

    post_pick = (
        post_df.sort_values("Anomaly Score", ascending=False)
        .groupby("Short name", dropna=False)
        .first()
        .reset_index()
    )
    post_pick = post_pick.rename(
        columns={
            "Recommended Parameters": "Nokia Parameter / Feature to Check",
            "Recommended Values": "Suggested Initial Value / Action",
            "Implementation Notes": "Implementation Note",
        }
    )

    rec_cols = [
        "Short name",
        "Nokia Parameter / Feature to Check",
        "Suggested Initial Value / Action",
        "Implementation Note",
    ]

    bad_out = worsened.merge(
        post_pick[rec_cols],
        on="Short name",
        how="left",
    )
    bad_out["delta_direction"] = "bad"

    good_out = improved.copy()
    good_out["Nokia Parameter / Feature to Check"] = ""
    good_out["Suggested Initial Value / Action"] = ""
    good_out["Implementation Note"] = ""
    good_out["delta_direction"] = "good"

    out = pd.concat([bad_out, good_out], ignore_index=True)
    if out.empty:
        return []

    out["pre_avg"] = out["pre_avg"].round(2)
    out["post_avg"] = out["post_avg"].round(2)
    out["delta"] = out["delta"].round(2)
    return _to_records(out)


def _build_response(anomaly_df: pd.DataFrame, vendor: str, detail_limit: Optional[int] = DEFAULT_RESPONSE_ROW_LIMIT) -> Dict[str, Any]:
    data = anomaly_df.copy()
    if "Date" in data.columns:
        data["Date"] = pd.to_datetime(data["Date"], errors="coerce")

    summary = {
        "anomaly_count": int(len(data)),
        "critical_count": int((data["Severity"] == "Critical").sum()) if "Severity" in data.columns else 0,
        "major_count": int((data["Severity"] == "Major").sum()) if "Severity" in data.columns else 0,
    }

    root_cause_summary = []
    top_impacted_cells = []
    date_trend = []

    if not data.empty:
        if "Primary Cause" in data.columns:
            root = data.groupby("Primary Cause", dropna=False).agg(
                Anomaly_Count=("Primary Cause", "size"),
                Avg_Score=("Anomaly Score", "mean"),
                Critical_Count=("Severity", lambda s: int((s == "Critical").sum())),
                Major_Count=("Severity", lambda s: int((s == "Major").sum())),
            ).reset_index().sort_values("Anomaly_Count", ascending=False)
            root["Avg_Score"] = root["Avg_Score"].round(2)
            root_cause_summary = _to_records(root)

        req_cols = {"Short name", "Site I'd", "Tech", "Anomaly Score"}
        if req_cols.issubset(set(data.columns)):
            top_cells = data.groupby(["Short name", "Site I'd", "Tech"], dropna=False).agg(
                Anomaly_Count=("Short name", "size"),
                Max_Score=("Anomaly Score", "max"),
                Top_Cause=("Primary Cause", lambda s: s.value_counts().index[0] if not s.empty else "Unknown"),
            ).reset_index().sort_values(["Anomaly_Count", "Max_Score"], ascending=[False, False]).head(20)
            top_cells["Max_Score"] = top_cells["Max_Score"].round(2)
            top_impacted_cells = _to_records(top_cells)

        if "Date" in data.columns:
            trend = data.groupby("Date", dropna=False).agg(
                Anomaly_Count=("Date", "size"),
                Critical_Count=("Severity", lambda s: int((s == "Critical").sum())),
                Avg_Score=("Anomaly Score", "mean"),
            ).reset_index().sort_values("Date")
            trend["Avg_Score"] = trend["Avg_Score"].round(2)
            date_trend = _to_records(trend)

    detail_data = data
    if detail_limit is not None and detail_limit > 0 and len(detail_data) > detail_limit:
        detail_data = detail_data.head(detail_limit).copy()
    else:
        detail_data = detail_data.copy()

    rec_df = detail_data.apply(lambda r: pd.Series(_build_vendor_recommendation_row(vendor, r)), axis=1) if not detail_data.empty else pd.DataFrame()
    if not rec_df.empty:
        detail_data = pd.concat([detail_data, rec_df], axis=1)

    return {
        "summary": summary,
        "root_cause_summary": root_cause_summary,
        "top_impacted_cells": top_impacted_cells,
        "date_trend": date_trend,
        "anomaly_details": _to_records(detail_data),
        "anomaly_details_total": int(len(data)),
        "anomaly_details_returned": int(len(detail_data)),
        "anomaly_details_limited": bool(len(detail_data) < len(data)),
    }


def _append_recommendation_columns_to_export(output_path: Path, vendor: str) -> None:
    if not output_path.exists():
        return
    wb = load_workbook(output_path)
    if "Anomaly_Details" not in wb.sheetnames:
        wb.save(output_path)
        return

    ws = wb["Anomaly_Details"]
    if ws.max_row < 2:
        wb.save(output_path)
        return

    headers = [ws.cell(1, c).value for c in range(1, ws.max_column + 1)]
    rows = list(ws.iter_rows(min_row=2, max_row=ws.max_row, values_only=True))
    anomaly_df = pd.DataFrame(rows, columns=headers)
    if anomaly_df.empty:
        wb.save(output_path)
        return

    rec_df = anomaly_df.apply(
        lambda r: pd.Series(_build_vendor_recommendation_row(vendor, r)),
        axis=1,
    )
    export_cols = {
        "Nokia Parameter / Feature to Check": rec_df["Recommended Parameters"],
        "Suggested Initial Value / Action": rec_df["Recommended Values"],
        "Implementation Note": rec_df["Implementation Notes"],
    }

    header_style_source = ws.cell(1, 1)
    for export_header, export_values in export_cols.items():
        if export_header in headers:
            col_idx = headers.index(export_header) + 1
        else:
            col_idx = ws.max_column + 1
            headers.append(export_header)
            header_cell = ws.cell(1, col_idx, export_header)
            header_cell.font = header_style_source.font.copy()
            header_cell.fill = header_style_source.fill.copy()
            header_cell.border = header_style_source.border.copy()
            header_cell.alignment = Alignment(horizontal="center", vertical="center", wrap_text=True)

        for row_idx, value in enumerate(export_values.tolist(), start=2):
            cell = ws.cell(row_idx, col_idx, value)
            cell.alignment = Alignment(vertical="top", wrap_text=True)

    # Parameter guide sheet
    sheet_name = "Parameter_Guide"
    if sheet_name in wb.sheetnames:
        del wb[sheet_name]
    guide_ws = wb.create_sheet(sheet_name)
    guide_rows = _parameter_guide_rows()
    guide_headers = [
        "Cause Bucket",
        "Common Nokia / 3GPP Parameter or Feature",
        "Suggested Initial Value / Action",
        "Use Case / Caution",
    ]

    for col_idx, header in enumerate(guide_headers, start=1):
        head = guide_ws.cell(1, col_idx, header)
        head.font = header_style_source.font.copy()
        head.fill = header_style_source.fill.copy()
        head.border = header_style_source.border.copy()
        head.alignment = Alignment(horizontal="center", vertical="center", wrap_text=True)

    for row_idx, row in enumerate(guide_rows, start=2):
        for col_idx, header in enumerate(guide_headers, start=1):
            c = guide_ws.cell(row_idx, col_idx, row.get(header, ""))
            c.alignment = Alignment(vertical="top", wrap_text=True)

    guide_ws.column_dimensions["A"].width = 18
    guide_ws.column_dimensions["B"].width = 48
    guide_ws.column_dimensions["C"].width = 34
    guide_ws.column_dimensions["D"].width = 55
    guide_ws.freeze_panes = "A2"
    guide_ws.auto_filter.ref = f"A1:D{guide_ws.max_row}"

    wb.save(output_path)


def _load_presets() -> Dict[str, Any]:
    if not PRESETS_FILE.exists():
        return {}
    with open(PRESETS_FILE, "r", encoding="utf-8") as fp:
        return json.load(fp)


def _save_presets(data: Dict[str, Any]) -> None:
    with open(PRESETS_FILE, "w", encoding="utf-8") as fp:
        json.dump(data, fp, indent=2, default=_json_default)


def save_preset(name: str, config: Dict[str, Any]) -> None:
    presets = _load_presets()
    presets[name] = config
    _save_presets(presets)


def get_preset(name: str) -> Dict[str, Any]:
    presets = _load_presets()
    if name not in presets:
        raise KeyError(f"Preset not found: {name}")
    return presets[name]


def list_presets() -> List[str]:
    return sorted(_load_presets().keys())


def run_rca(input_path: str, config: Dict[str, Any], export_excel: bool = False, excel_output_path: str = None) -> Dict[str, Any]:
    vendor = str(config.get("vendor", DEFAULT_VENDOR)).strip().lower()
    if vendor not in {"nokia", "ericsson"}:
        vendor = DEFAULT_VENDOR
    response_row_limit = _positive_int(
        config.get("response_row_limit"),
        DEFAULT_RESPONSE_ROW_LIMIT,
        MAX_RESPONSE_ROW_LIMIT,
    )
    date_mode = str(config.get("date_mode", "single")).strip().lower()
    if date_mode not in {"single", "pre_post"}:
        date_mode = "single"

    sheet_name = config.get("sheet_name")
    raw_df = _read_input_frame(input_path=input_path, sheet_name=sheet_name)

    final_meta = _build_kpi_meta(config)
    rca_rules, severity_rules, anomaly_rules = _build_threshold_overrides(config)
    normalized_df, missing_required, missing_kpis = _normalize_frame(raw_df, config, final_meta)
    if missing_required:
        raise ValueError(f"Missing required base mappings: {', '.join(missing_required)}")

    # Ensure Date parsing pre-validation for a clearer error.
    normalized_df["Date"] = pd.to_datetime(normalized_df["Date"], errors="coerce")
    if normalized_df["Date"].isna().all():
        raise ValueError("Mapped Date column could not be parsed into valid dates.")
    normalized_df = normalized_df[normalized_df["Date"].notna()].reset_index(drop=True)
    if normalized_df.empty:
        raise ValueError("No valid Date rows found after parsing.")

    output_path: Optional[Path] = None

    if date_mode == "pre_post":
        pre_filter = config.get("pre_date_filter") or {}
        post_filter = config.get("post_date_filter") or {}
        pre_df, pre_filter_meta = _filter_df_by_range(
            df=normalized_df,
            start_raw=pre_filter.get("start_date"),
            end_raw=pre_filter.get("end_date"),
            label="pre",
            allow_empty=False,
        )
        post_df, post_filter_meta = _filter_df_by_range(
            df=normalized_df,
            start_raw=post_filter.get("start_date"),
            end_raw=post_filter.get("end_date"),
            label="post",
            allow_empty=False,
        )

        if (
            pre_filter_meta["end_date"]
            and post_filter_meta["start_date"]
            and pre_filter_meta["end_date"] >= post_filter_meta["start_date"]
        ):
            raise ValueError("Pre period must end before post period starts.")

        pre_output = Path(tempfile.gettempdir()) / f"rca_pre_{next(tempfile._get_candidate_names())}.xlsx"
        if export_excel:
            output_path = Path(excel_output_path) if excel_output_path else Path(tempfile.gettempdir()) / DEFAULT_EXPORT_NAME
        else:
            output_path = Path(tempfile.gettempdir()) / f"rca_post_{next(tempfile._get_candidate_names())}.xlsx"

        pre_anomaly_df = _run_analyser_on_frame(
            input_df=pre_df,
            output_path=pre_output,
            final_meta=final_meta,
            rca_rules=rca_rules,
            severity_rules=severity_rules,
            anomaly_rules=anomaly_rules,
            export_excel=False,
        )
        post_anomaly_df = _run_analyser_on_frame(
            input_df=post_df,
            output_path=output_path,
            final_meta=final_meta,
            rca_rules=rca_rules,
            severity_rules=severity_rules,
            anomaly_rules=anomaly_rules,
            export_excel=export_excel,
        )

        pre_payload = _build_response(anomaly_df=pre_anomaly_df, vendor=vendor, detail_limit=response_row_limit)
        post_payload = _build_response(anomaly_df=post_anomaly_df, vendor=vendor, detail_limit=response_row_limit)

        pre_rows = pre_payload.get("anomaly_details", [])
        post_rows = post_payload.get("anomaly_details", [])
        combined_rows = [{**row, "Period": "Pre"} for row in pre_rows] + [{**row, "Period": "Post"} for row in post_rows]
        combined_df = pd.DataFrame(combined_rows)
        combined_summary = _build_response(anomaly_df=combined_df, vendor=vendor, detail_limit=response_row_limit) if not combined_df.empty else {
            "summary": {"anomaly_count": 0, "critical_count": 0, "major_count": 0},
            "root_cause_summary": [],
            "top_impacted_cells": [],
            "date_trend": [],
            "anomaly_details": [],
            "anomaly_details_total": 0,
            "anomaly_details_returned": 0,
            "anomaly_details_limited": False,
        }

        response = dict(combined_summary)
        response["period_mode"] = "pre_post"
        response["period_filters"] = {"pre": pre_filter_meta, "post": post_filter_meta}
        response["pre_result"] = pre_payload
        response["post_result"] = post_payload
        response["worsened_recommendations"] = _build_pre_post_worsened_rows(
            pre_rows,
            post_rows,
            pre_filter_meta=pre_filter_meta,
            post_filter_meta=post_filter_meta,
        )
        response["applied_date_filter"] = None

        if pre_output.exists():
            pre_output.unlink(missing_ok=True)
    else:
        normalized_df, date_filter_meta = _apply_date_filter(normalized_df, config)
        output_path = Path(excel_output_path) if excel_output_path else Path(tempfile.gettempdir()) / DEFAULT_EXPORT_NAME
        anomaly_df = _run_analyser_on_frame(
            input_df=normalized_df,
            output_path=output_path,
            final_meta=final_meta,
            rca_rules=rca_rules,
            severity_rules=severity_rules,
            anomaly_rules=anomaly_rules,
            export_excel=export_excel,
        )
        response = _build_response(anomaly_df=anomaly_df, vendor=vendor, detail_limit=response_row_limit)
        response["period_mode"] = "single"
        response["applied_date_filter"] = date_filter_meta
        response["worsened_recommendations"] = []

    response["vendor"] = vendor
    response["missing_kpis"] = sorted(set(missing_kpis))
    response["used_kpis"] = sorted(final_meta.keys())
    response["applied_thresholds"] = {
        "rca_thresholds": rca_rules,
        "severity_thresholds": severity_rules,
        "anomaly_thresholds": anomaly_rules,
    }
    response["exported_excel"] = export_excel
    response["excel_file"] = str(output_path) if export_excel and output_path else None

    if export_excel and output_path:
        _append_recommendation_columns_to_export(output_path=output_path, vendor=vendor)

    if not export_excel and output_path and output_path.exists():
        output_path.unlink(missing_ok=True)

    return response


def _write_json(path: str, payload: Dict[str, Any]) -> None:
    with open(path, "w", encoding="utf-8") as fp:
        json.dump(payload, fp, indent=2, default=_json_default)


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="RCA wrapper entry point for analyser.py")
    sub = parser.add_subparsers(dest="command", required=True)

    run_parser = sub.add_parser("run", help="Run RCA flow")
    run_parser.add_argument("--input", required=True, help="Input Excel/CSV path")
    run_parser.add_argument("--config", required=True, help="Path to config JSON")
    run_parser.add_argument("--json-out", required=False, help="Write response JSON to file")
    run_parser.add_argument("--export-excel", action="store_true", help="Export Excel output")
    run_parser.add_argument("--excel-out", required=False, help="Excel output path")

    lb_parser = sub.add_parser("run-lb-wcf", help="Run load-balancing worst-cell feature engineering and recommendations")
    lb_parser.add_argument("--kpi-input", required=True, help="Input KPI Excel/CSV path")
    lb_parser.add_argument("--ta-input", required=False, help="Input TA Excel/CSV path. Optional if KPI input is already feature-engineered.")
    lb_parser.add_argument("--config", required=True, help="Path to LB config JSON")
    lb_parser.add_argument("--json-out", required=False, help="Write response JSON to file")
    lb_parser.add_argument("--output", required=False, help="Write recommendation output to .xlsx or .csv")

    lb_strategy_parser = sub.add_parser("run-lb-layer-strategy", help="Build load-balancing layer strategy setup output")
    lb_strategy_parser.add_argument("--config", required=True, help="Path to LB layer strategy config JSON")
    lb_strategy_parser.add_argument("--json-out", required=False, help="Write response JSON to file")
    lb_strategy_parser.add_argument("--output", required=False, help="Write layer strategy output to .xlsx or .csv")

    save_parser = sub.add_parser("save-preset", help="Save config preset")
    save_parser.add_argument("--name", required=True, help="Preset name")
    save_parser.add_argument("--config", required=True, help="Config JSON path")

    load_parser = sub.add_parser("load-preset", help="Load preset and print JSON")
    load_parser.add_argument("--name", required=True, help="Preset name")
    load_parser.add_argument("--json-out", required=False, help="Write preset JSON")

    sub.add_parser("list-presets", help="List available preset names")
    return parser.parse_args()


def main() -> int:
    args = _parse_args()
    if args.command == "save-preset":
        config = _read_config(args.config)
        save_preset(name=args.name, config=config)
        print(f"Preset saved: {args.name}")
        return 0

    if args.command == "load-preset":
        payload = get_preset(name=args.name)
        if args.json_out:
            _write_json(args.json_out, payload)
        else:
            print(json.dumps(payload, indent=2, default=_json_default))
        return 0

    if args.command == "list-presets":
        for name in list_presets():
            print(name)
        return 0

    if args.command == "run-lb-wcf":
        config = _read_config(args.config)
        response = lb_service.run_lb_wcf(
            kpi_path=args.kpi_input,
            ta_path=args.ta_input,
            config=config,
            output_path=args.output,
        )
        if args.json_out:
            lb_service.write_json(args.json_out, response)
        else:
            print(json.dumps(response, indent=2, default=_json_default))
        return 0

    if args.command == "run-lb-layer-strategy":
        config = _read_config(args.config)
        response = lb_service.build_layer_strategy(config=config, output_path=args.output)
        if args.json_out:
            lb_service.write_json(args.json_out, response)
        else:
            print(json.dumps(response, indent=2, default=_json_default))
        return 0

    if args.command == "run":
        config = _read_config(args.config)
        preset_name = config.get("preset")
        if preset_name is not None:
            preset_name = str(preset_name).strip()
            if preset_name:
                try:
                    preset_payload = get_preset(preset_name)
                except KeyError as exc:
                    raise ValueError(str(exc)) from exc
                merged = dict(preset_payload)
                merged.update({k: v for k, v in config.items() if k != "preset"})
                config = merged
            else:
                config.pop("preset", None)

        response = run_rca(
            input_path=args.input,
            config=config,
            export_excel=bool(args.export_excel),
            excel_output_path=args.excel_out,
        )
        if args.json_out:
            _write_json(args.json_out, response)
        else:
            print(json.dumps(response, indent=2, default=_json_default))
        return 0

    raise ValueError("Unknown command")


if __name__ == "__main__":
    raise SystemExit(main())
