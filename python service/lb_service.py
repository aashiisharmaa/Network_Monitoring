import json
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple

import numpy as np
import pandas as pd


DISTANCE_COLUMNS = [
    "Till 0.08 Km",
    "0.08Km-0.23Km",
    "0.23Km-0.63Km",
    "0.63Km-1.01Km",
    "1.01Km-1.64Km",
    "1.64Km-2.42Km",
    "2.42Km-3.36Km",
    "3.36Km-4.45Km",
    "4.45Km-5.7Km",
    "5.7Km-7.18Km",
    "7.18Km-8.74Km",
    "8.74Km-10.5Km",
    "10.5Km-12.5Km",
    "12.5Km-14.6Km",
    "14.6Km-16.9Km",
]

LB_OUTPUT_COLUMNS = [
    "ERBS",
    "Cell",
    "Site",
    "Band",
    "Sector_x",
    "Groups_x",
    "PRB DL Utilization %",
    "Total Traffic(GB)",
    "DL_throughput",
    "Avg_number_of_RRC_users",
    "Total Traffic(MB)_share%",
    "%coverage_upto_2km",
    "%coverage_2_6km",
    "%coverage_6km_plus",
    "Target",
    "Band_Unbalanced",
    "ML_Cluster",
    "ML_Probability",
    "ML_Balanced_Prediction",
    "ML_Recommendations",
    "ML_Probabilities",
    "Recommendations",
    "Probabilities",
]


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


def _read_frame(path: str, sheet_name: Optional[str] = None) -> pd.DataFrame:
    source = Path(path)
    if not source.exists():
        raise FileNotFoundError(f"Input file not found: {path}")
    suffix = source.suffix.lower()
    if suffix == ".csv":
        return pd.read_csv(source)
    if suffix in {".xlsx", ".xlsm", ".xls"}:
        return pd.read_excel(source, sheet_name=sheet_name) if sheet_name else pd.read_excel(source)
    raise ValueError("Unsupported input format. Use .csv, .xlsx, .xlsm, or .xls")


def _write_frame(df: pd.DataFrame, path: str) -> None:
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.suffix.lower() == ".csv":
        df.to_csv(target, index=False)
    else:
        df.to_excel(target, index=False)


def _safe_numeric(series: pd.Series) -> pd.Series:
    return pd.to_numeric(series, errors="coerce").fillna(0)


def _float_value(value: Any, fallback: float) -> float:
    try:
        parsed = pd.to_numeric(pd.Series([value]), errors="coerce").iloc[0]
        if pd.isna(parsed):
            return fallback
        return float(parsed)
    except Exception:
        return fallback


def _first_column_containing(columns: Iterable[str], token: str) -> Optional[str]:
    token = token.lower()
    for col in columns:
        if token in str(col).lower():
            return col
    return None


def _require_columns(df: pd.DataFrame, required: List[str], label: str) -> None:
    missing = [col for col in required if col not in df.columns]
    if missing:
        raise ValueError(f"{label} is missing required columns: {', '.join(missing)}")


def calculate_traffic_share(df: pd.DataFrame) -> pd.DataFrame:
    out = df.copy()
    traffic_column = _first_column_containing(out.columns, "Traffic")
    if traffic_column is None:
        return out

    _require_columns(out, ["Site", "Date", "Sector"], "KPI input")
    share_col = f"{traffic_column}_share%"
    out[traffic_column] = _safe_numeric(out[traffic_column])
    totals = out.groupby(["Site", "Date", "Sector"])[traffic_column].transform("sum")
    out[share_col] = np.where(totals > 0, out[traffic_column] / totals * 100, 0)
    return out


def _coverage_category(row: pd.Series) -> str:
    near = float(row.get("coverage_upto_2km", 0) or 0)
    mid = float(row.get("coverage_2_6km", 0) or 0)
    far = float(row.get("coverage_6km_plus", 0) or 0)
    if far >= max(near, mid):
        return "Overshooting"
    if near < 50 and mid >= near:
        return "Under-coverage"
    return "Normal coverage"


def _add_coverage_cluster(df: pd.DataFrame) -> pd.DataFrame:
    out = df.copy()
    features = out[["coverage_upto_2km", "coverage_2_6km", "coverage_6km_plus"]].fillna(0)
    if len(out) >= 3 and features.drop_duplicates().shape[0] >= 3:
        try:
            from sklearn.cluster import KMeans

            model = KMeans(n_clusters=3, n_init="auto", random_state=42).fit(features)
            labels = model.labels_
            centers = pd.DataFrame(
                model.cluster_centers_,
                columns=features.columns,
            )
            ranked = centers.assign(
                dominant=centers[["coverage_upto_2km", "coverage_2_6km", "coverage_6km_plus"]].idxmax(axis=1)
            )
            label_map = {}
            for idx, row in ranked.iterrows():
                dominant = row["dominant"]
                if dominant == "coverage_6km_plus":
                    label_map[idx] = "Overshooting"
                elif dominant == "coverage_upto_2km":
                    label_map[idx] = "Normal coverage"
                else:
                    label_map[idx] = "Under-coverage"
            out["coverage_category"] = pd.Series(labels, index=out.index).map(label_map)
            return out
        except Exception:
            pass
    out["coverage_category"] = out.apply(_coverage_category, axis=1)
    return out


def perform_feature_engineering(kpi_df: pd.DataFrame, ta_df: pd.DataFrame) -> pd.DataFrame:
    kpi_df = calculate_traffic_share(kpi_df)
    _require_columns(kpi_df, ["Cell", "Site", "Band"], "KPI input")
    _require_columns(ta_df, ["Cell", "Site", "Band"], "TA input")
    _require_columns(ta_df, DISTANCE_COLUMNS, "TA input")

    kpi_df = kpi_df.copy()
    ta_df = ta_df.copy()
    for key in ["Cell", "Site", "Band"]:
        kpi_df[key] = kpi_df[key].astype(str).str.strip()
        ta_df[key] = ta_df[key].astype(str).str.strip()

    merged = pd.merge(kpi_df, ta_df, how="inner", on=["Cell", "Site", "Band"])
    if merged.empty:
        raise ValueError("No matching KPI/TA rows found on Cell, Site, and Band.")

    for col in DISTANCE_COLUMNS:
        merged[col] = _safe_numeric(merged[col])

    merged["coverage_upto_2km"] = merged[
        ["Till 0.08 Km", "0.08Km-0.23Km", "0.23Km-0.63Km", "0.63Km-1.01Km", "1.01Km-1.64Km"]
    ].sum(axis=1)
    merged["coverage_2_6km"] = merged[
        ["1.64Km-2.42Km", "2.42Km-3.36Km", "3.36Km-4.45Km", "4.45Km-5.7Km"]
    ].sum(axis=1)
    merged["coverage_6km_plus"] = merged[
        ["5.7Km-7.18Km", "7.18Km-8.74Km", "8.74Km-10.5Km", "10.5Km-12.5Km", "12.5Km-14.6Km", "14.6Km-16.9Km"]
    ].sum(axis=1)
    total = merged[DISTANCE_COLUMNS].sum(axis=1).replace(0, np.nan)
    merged["%coverage_upto_2km"] = (merged["coverage_upto_2km"] / total * 100).fillna(0)
    merged["%coverage_2_6km"] = (merged["coverage_2_6km"] / total * 100).fillna(0)
    merged["%coverage_6km_plus"] = (merged["coverage_6km_plus"] / total * 100).fillna(0)

    merged = _add_coverage_cluster(merged)
    cumulative = merged[DISTANCE_COLUMNS].cumsum(axis=1).div(total, axis=0)
    merged["90%_coverage_distance"] = cumulative.ge(0.90).idxmax(axis=1)
    merged["95%_coverage_distance"] = cumulative.ge(0.95).idxmax(axis=1)
    merged["90th_percentile_distance_22"] = merged[DISTANCE_COLUMNS].apply(lambda row: np.percentile(row.values, 90), axis=1)
    merged["95th_percentile_distance_22"] = merged[DISTANCE_COLUMNS].apply(lambda row: np.percentile(row.values, 95), axis=1)

    sector_col = "Sector_x" if "Sector_x" in merged.columns else "Sector"
    if sector_col in merged.columns:
        merged["coverage_rank"] = merged.groupby(sector_col)["90%_coverage_distance"].rank(method="min")
        merged["percentile_90_rank_22"] = merged.groupby(sector_col)["90th_percentile_distance_22"].rank(method="min")
        merged["percentile_95_rank_22"] = merged.groupby(sector_col)["95th_percentile_distance_22"].rank(method="min")

    if "User_DL_Averg_THP_Mbps" in merged.columns:
        merged["User_DL_Averg_THP_Mbps"] = _safe_numeric(merged["User_DL_Averg_THP_Mbps"])
        group_cols = ["Site", sector_col] if sector_col in merged.columns else ["Site"]
        group_mean = merged.groupby(group_cols)["User_DL_Averg_THP_Mbps"].transform("mean")
        group_sd = merged.groupby(group_cols)["User_DL_Averg_THP_Mbps"].transform("std").replace(0, np.nan)
        merged["% Deviation from SD"] = ((merged["User_DL_Averg_THP_Mbps"] - group_sd).abs() / group_sd * 100).fillna(0)
        merged["% Deviation from Mean"] = ((merged["User_DL_Averg_THP_Mbps"] - group_mean).abs() / group_mean.replace(0, np.nan) * 100).fillna(0)
        merged["Deviation in SD"] = ((merged["User_DL_Averg_THP_Mbps"] - group_mean) / group_sd).fillna(0)

    merged = merged.rename(
        columns={
            "User_DL_Averg_THP_Mbps": "DL_throughput",
            "Inter_freq HOSR%": "Inter_freq_HOSR%",
            "RRC SR%": "RRC_SR%",
        }
    )
    return merged


def prepare_kpi_only_input(kpi_df: pd.DataFrame) -> pd.DataFrame:
    return kpi_df.rename(
        columns={
            "User_DL_Averg_THP_Mbps": "DL_throughput",
            "Inter_freq HOSR%": "Inter_freq_HOSR%",
            "RRC SR%": "RRC_SR%",
        }
    )


def _rule_based_recommendations(row: pd.Series, throughput_threshold: float) -> Tuple[List[str], List[float]]:
    recommendations: List[str] = []
    probabilities: List[float] = []

    if row.get("Band_Unbalanced") == "Balanced":
        return recommendations, probabilities

    if _float_value(row.get("DL_throughput"), 0) < throughput_threshold:
        recommendations.extend(["Reduce threshXLow", "Lower a5Threshold1Rsrp"])
        probabilities.extend([0.85, 0.80])
    if _float_value(row.get("Inter_freq_HOSR%"), 100) < 95:
        recommendations.extend(["Increase threshXHigh", "Fine-tune a5Threshold2Rsrp"])
        probabilities.extend([0.75, 0.78])
    if _float_value(row.get("RRC_SR%"), 100) < 98:
        recommendations.extend(["Adjust threshXLow", "Enable mobilityLoadBalancing"])
        probabilities.extend([0.70, 0.80])
    if _float_value(row.get("coverage_upto_2km"), 100) < 80:
        recommendations.extend(["Lower a5Threshold1Rsrp", "Optimize antenna tilt"])
        probabilities.extend([0.85, 0.90])
    return recommendations, probabilities


def _apply_rule_based(df: pd.DataFrame, quantile: float) -> pd.DataFrame:
    out = df.copy()
    _require_columns(out, ["DL_throughput"], "LB analysis input")
    out["DL_throughput"] = _safe_numeric(out["DL_throughput"])
    threshold = float(out["DL_throughput"].quantile(quantile))
    out["DL_throughput_threshold"] = threshold
    out["Band_Unbalanced"] = np.where(out["DL_throughput"] >= threshold, "Balanced", "Not Balanced")
    recs = out.apply(lambda row: _rule_based_recommendations(row, threshold), axis=1)
    out["Recommendations"] = recs.map(lambda item: item[0])
    out["Probabilities"] = recs.map(lambda item: item[1])
    return out


def _ml_recommendations(row: pd.Series, mode: int) -> Tuple[List[str], List[float]]:
    recommendations: List[str] = []
    probabilities: List[float] = []
    if int(_float_value(row.get("ML_Cluster"), 0)) != 1 and int(_float_value(row.get("ML_Balanced_Prediction"), 0)) != 1:
        return recommendations, probabilities
    if mode in {1, 3} and _float_value(row.get("DL_throughput"), 0) < _float_value(row.get("DL_Threshold"), 0):
        recommendations.extend(["Reduce threshXLow", "Lower a5Threshold1Rsrp"])
        probabilities.extend([0.85, 0.80])
    if mode in {2, 3} and _float_value(row.get("PRB DL Utilization %"), 0) < _float_value(row.get("PRB_Threshold"), 0):
        recommendations.extend(["Optimize PRB allocation", "Adjust schedulers"])
        probabilities.extend([0.80, 0.75])
    if _float_value(row.get("Inter_freq_HOSR%"), 100) < 95:
        recommendations.extend(["Increase threshXHigh", "Fine-tune a5Threshold2Rsrp"])
        probabilities.extend([0.75, 0.78])
    if _float_value(row.get("RRC_SR%"), 100) < 98:
        recommendations.extend(["Adjust hysteresis", "Enable mobilityLoadBalancing"])
        probabilities.extend([0.70, 0.80])
    if _float_value(row.get("coverage_upto_2km"), 100) < 80:
        recommendations.append("Optimize antenna tilt")
        probabilities.append(0.90)
    return recommendations, probabilities


def _apply_ml_based(df: pd.DataFrame, mode: int) -> pd.DataFrame:
    out = df.copy()
    _require_columns(out, ["Site", "DL_throughput"], "LB analysis input")
    if mode in {2, 3}:
        _require_columns(out, ["PRB DL Utilization %"], "LB analysis input")
    sector_col = "Sector_x" if "Sector_x" in out.columns else "Sector"
    group_cols = ["Site", sector_col] if sector_col in out.columns else ["Site"]

    out["DL_throughput"] = _safe_numeric(out["DL_throughput"])
    if "PRB DL Utilization %" in out.columns:
        out["PRB DL Utilization %"] = _safe_numeric(out["PRB DL Utilization %"])
    if mode in {1, 3}:
        out["DL_Threshold"] = out.groupby(group_cols)["DL_throughput"].transform("median")
    if mode in {2, 3}:
        out["PRB_Threshold"] = out.groupby(group_cols)["PRB DL Utilization %"].transform("median")

    if mode == 1:
        out["Target"] = (out["DL_throughput"] < out["DL_Threshold"]).astype(int)
    elif mode == 2:
        out["Target"] = (out["PRB DL Utilization %"] < out["PRB_Threshold"]).astype(int)
    else:
        out["Target"] = ((out["DL_throughput"] < out["DL_Threshold"]) | (out["PRB DL Utilization %"] < out["PRB_Threshold"])).astype(int)
    out["Band_Unbalanced"] = np.where(out["Target"] == 1, "Not Balanced", "Balanced")

    numeric = out.select_dtypes(include=[np.number]).replace([np.inf, -np.inf], np.nan)
    numeric = numeric.fillna(numeric.median(numeric_only=True)).fillna(0)
    if len(out) < 4 or out["Target"].nunique() < 2:
        out["ML_Cluster"] = out["Target"]
        out["ML_Probability"] = out["Target"].astype(float)
        out["ML_Balanced_Prediction"] = out["Target"]
    else:
        try:
            from sklearn.cluster import KMeans
            from sklearn.linear_model import LogisticRegression
            from sklearn.model_selection import train_test_split
            from sklearn.preprocessing import StandardScaler

            scaled = StandardScaler().fit_transform(numeric)
            out["ML_Cluster"] = KMeans(n_clusters=2, n_init="auto", random_state=42).fit_predict(scaled)
            test_size = 0.2 if len(out) >= 10 else 0.34
            x_train, _, y_train, _ = train_test_split(scaled, out["Target"], test_size=test_size, random_state=42, stratify=out["Target"])
            model = LogisticRegression(max_iter=1000)
            model.fit(x_train, y_train)
            out["ML_Probability"] = model.predict_proba(scaled)[:, 1]
            out["ML_Balanced_Prediction"] = model.predict(scaled)
        except Exception:
            out["ML_Cluster"] = out["Target"]
            out["ML_Probability"] = out["Target"].astype(float)
            out["ML_Balanced_Prediction"] = out["Target"]

    recs = out.apply(lambda row: _ml_recommendations(row, mode), axis=1)
    out["ML_Recommendations"] = recs.map(lambda item: item[0])
    out["ML_Probabilities"] = recs.map(lambda item: item[1])
    return out


def analyze_worst_cells(df: pd.DataFrame, config: Dict[str, Any]) -> pd.DataFrame:
    method = str(config.get("method", "both")).strip().lower()
    quantile = float(config.get("quantile", 0.10))
    quantile = min(max(quantile, 0.0), 1.0)
    mode = int(config.get("ml_mode", config.get("user_choice", 3)))
    if mode not in {1, 2, 3}:
        mode = 3

    out = df.copy()
    if method in {"rule", "rule-based", "both"}:
        out = _apply_rule_based(out, quantile)
    if method in {"ml", "ml-based", "both"}:
        out = _apply_ml_based(out, mode)

    columns = [col for col in LB_OUTPUT_COLUMNS if col in out.columns and not out[col].isna().all()]
    return out[columns] if columns else out


def run_lb_wcf(kpi_path: str, ta_path: Optional[str], config: Dict[str, Any], output_path: Optional[str] = None) -> Dict[str, Any]:
    input_sheet = config.get("sheet_name")
    kpi_df = _read_frame(kpi_path, input_sheet)
    warnings: List[str] = []
    ta_input_used = False
    if ta_path:
        ta_df = _read_frame(ta_path, config.get("ta_sheet_name"))
        missing_ta_columns = [col for col in ["Cell", "Site", "Band", *DISTANCE_COLUMNS] if col not in ta_df.columns]
        if missing_ta_columns:
            warnings.append(
                "TA distance columns were not found, so LB/WCF ran with KPI data only. "
                "Coverage features were skipped."
            )
            engineered = prepare_kpi_only_input(kpi_df)
        else:
            engineered = perform_feature_engineering(kpi_df, ta_df)
            ta_input_used = True
    else:
        warnings.append("No TA file was provided, so LB/WCF ran with KPI data only. Coverage features were skipped.")
        engineered = prepare_kpi_only_input(kpi_df)
    result_df = analyze_worst_cells(engineered, config)
    if output_path:
        _write_frame(result_df, output_path)

    unbalanced_count = int((result_df.get("Band_Unbalanced", pd.Series(dtype=str)) == "Not Balanced").sum())
    response = {
        "summary": {
            "rows": int(len(result_df)),
            "unbalanced_count": unbalanced_count,
            "balanced_count": int(len(result_df) - unbalanced_count),
            "method": str(config.get("method", "both")),
            "ta_input_used": ta_input_used,
            "warnings": warnings,
        },
        "records": _to_records(result_df),
        "exported_file": str(output_path) if output_path else None,
    }
    return response


def build_layer_strategy(config: Dict[str, Any], output_path: Optional[str] = None) -> Dict[str, Any]:
    layers = config.get("layers") or []
    rules = config.get("mobility_rules") or config.get("mobilityRules") or []
    rows: List[Dict[str, Any]] = []
    max_len = max(len(layers), len(rules))

    for idx in range(max_len):
        layer = layers[idx] if idx < len(layers) and isinstance(layers[idx], dict) else {}
        rule = rules[idx] if idx < len(rules) and isinstance(rules[idx], dict) else {}
        rows.append(
            {
                "LTE Layers": layer.get("name", layer.get("layer", "")),
                "Bandwidth (MHz)": layer.get("bandwidth_mhz", layer.get("bandwidth", "")),
                "Layer Priority": layer.get("priority", ""),
                "UE State": rule.get("ue_state", rule.get("ueState", "")),
                "Layer Mobility": rule.get("layer_mobility", rule.get("layerMobility", "")),
                "Source Layer": rule.get("source_layer", rule.get("sourceLayer", "")),
                "Source Level Low Priority": rule.get("source_level_low_priority", rule.get("sourceLevelLowPriority", "")),
                "Source Level High Priority": rule.get("source_level_high_priority", rule.get("sourceLevelHighPriority", "")),
                "Target Layer": rule.get("target_layer", rule.get("targetLayer", "")),
                "Target Level": rule.get("target_level", rule.get("targetLevel", "")),
            }
        )

    df = pd.DataFrame(rows)
    if output_path:
        _write_frame(df, output_path)
    return {
        "summary": {"rows": int(len(df)), "layers": int(len(layers)), "mobility_rules": int(len(rules))},
        "records": _to_records(df),
        "exported_file": str(output_path) if output_path else None,
    }


def write_json(path: str, payload: Dict[str, Any]) -> None:
    with open(path, "w", encoding="utf-8") as fp:
        json.dump(payload, fp, indent=2, default=_json_default)
