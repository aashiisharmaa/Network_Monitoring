
import pandas as pd
import numpy as np
import warnings
from openpyxl import Workbook
from openpyxl.styles import Font, PatternFill, Border, Side, Alignment
from openpyxl.utils import get_column_letter
from openpyxl.formatting.rule import ColorScaleRule

warnings.filterwarnings("ignore", category=RuntimeWarning)

INPUT_PATH = "INPUT_FILE.xlsx"
OUTPUT_XLSX = "nokia_kpi_anomaly_rca_output.xlsx"

SELECTED_COLS = [
    'Short name','Date',"Cell I'd","Site I'd",'Tech',
    'MV_PUSCH SINR','MV_E-UTRAN Average CQI','DL PRB Utilisation',
    'Average UE Distance_KM','MV_Packet Loss DL','MV_Packet Loss UL',
    'MV_Radio NW Availability','MV_VoLTE DCR','VoLTE ERAB Setup Success Rate',
    'VoLTE InterF HOSR','VoLTE IntraF HOSR [CBBH]',
    'UL User Throughput_Kbps','PS IntraF HOSR [CDBH]','PS InterF HOSR',
    'RRC Setup Success Rate','MV_ERAB Setup Success Rate',
    'MV_DL User Throughput_Mbps','MV_% RSRP Sample <-110 dBm',
    'MV_PS Drop Call Rate %','MV_VoLTE CSSR%','Avg RSSI PUSCH','Avg RSSI PUCCH',
    'UL NI [RSSI-SINR]','MV_NW Avg Connected User','MV_4G Data Volume_GB',
    'VoLTE Traffic','MV_VoLTE Packet Loss DL','MV_VoLTE Packet Loss UL'
]

KPI_META = {
    'MV_PUSCH SINR': {'direction':'high_good','threshold':6,'weight':1.2,'min_scale':0.8,'number_format':'0.00','friendly':'PUSCH SINR (dB)','rule':'Low SINR indicates UL interference / poor radio quality.'},
    'MV_E-UTRAN Average CQI': {'direction':'high_good','threshold':7,'weight':1.0,'min_scale':0.3,'number_format':'0.00','friendly':'Average CQI','rule':'Low CQI points to poor radio quality / interference.'},
    'DL PRB Utilisation': {'direction':'low_good','threshold':70,'weight':1.0,'min_scale':5.0,'number_format':'0.0','friendly':'DL PRB Utilisation (%)','rule':'High PRB with low throughput indicates congestion or scheduler inefficiency.'},
    'Average UE Distance_KM': {'direction':'low_good','threshold':1.2,'weight':0.7,'min_scale':0.05,'number_format':'0.00','friendly':'Average UE Distance (km)','rule':'High distance with poor RSRP often indicates overshooting / coverage edge.'},
    'MV_Packet Loss DL': {'direction':'low_good','threshold':0.05,'weight':0.8,'min_scale':0.02,'number_format':'0.00','friendly':'Packet Loss DL (%)','rule':'High DL packet loss suggests transport/QoS or severe radio quality issue.'},
    'MV_Packet Loss UL': {'direction':'low_good','threshold':2.0,'weight':0.8,'min_scale':0.2,'number_format':'0.00','friendly':'Packet Loss UL (%)','rule':'High UL packet loss suggests UL quality or transport issue.'},
    'MV_Radio NW Availability': {'direction':'high_good','threshold':97.0,'weight':1.4,'min_scale':0.5,'number_format':'0.00','friendly':'Radio Availability (%)','rule':'Low availability indicates outage, lock, or hardware/backhaul issue.'},
    'MV_VoLTE DCR': {'direction':'low_good','threshold':1.0,'weight':1.0,'min_scale':0.2,'number_format':'0.00','friendly':'VoLTE DCR (%)','rule':'High VoLTE DCR indicates retainability or mobility issue.'},
    'VoLTE ERAB Setup Success Rate': {'direction':'high_good','threshold':98.5,'weight':1.0,'min_scale':0.5,'number_format':'0.00','friendly':'VoLTE ERAB Setup SR (%)','rule':'Low VoLTE ERAB SR points to accessibility issue.'},
    'VoLTE InterF HOSR': {'direction':'high_good','threshold':95,'weight':0.9,'min_scale':1.0,'number_format':'0.00','friendly':'VoLTE InterF HOSR (%)','rule':'Low HO success indicates mobility issue.'},
    'VoLTE IntraF HOSR [CBBH]': {'direction':'high_good','threshold':95,'weight':0.9,'min_scale':1.0,'number_format':'0.00','friendly':'VoLTE IntraF HOSR (%)','rule':'Low HO success indicates mobility issue.'},
    'UL User Throughput_Kbps': {'direction':'high_good','threshold':250,'weight':0.9,'min_scale':80,'number_format':'0.00','friendly':'UL User Throughput (kbps)','rule':'Low UL throughput with low SINR suggests UL interference/coverage issue.'},
    'PS IntraF HOSR [CDBH]': {'direction':'high_good','threshold':95,'weight':0.9,'min_scale':1.0,'number_format':'0.00','friendly':'PS IntraF HOSR (%)','rule':'Low HO success indicates mobility issue.'},
    'PS InterF HOSR': {'direction':'high_good','threshold':95,'weight':0.9,'min_scale':1.0,'number_format':'0.00','friendly':'PS InterF HOSR (%)','rule':'Low HO success indicates mobility issue.'},
    'RRC Setup Success Rate': {'direction':'high_good','threshold':98,'weight':1.2,'min_scale':0.5,'number_format':'0.00','friendly':'RRC Setup SR (%)','rule':'Low RRC SR points to accessibility issue.'},
    'MV_ERAB Setup Success Rate': {'direction':'high_good','threshold':98.5,'weight':1.1,'min_scale':0.4,'number_format':'0.00','friendly':'ERAB Setup SR (%)','rule':'Low ERAB SR points to accessibility issue.'},
    'MV_DL User Throughput_Mbps': {'direction':'high_good','threshold':5,'weight':1.3,'min_scale':1.5,'number_format':'0.00','friendly':'DL User Throughput (Mbps)','rule':'Low DL throughput is the key user-experience KPI.'},
    'MV_% RSRP Sample <-110 dBm': {'direction':'low_good','threshold':35,'weight':1.1,'min_scale':3,'number_format':'0.00','friendly':'Poor RSRP Sample < -110 dBm (%)','rule':'High poor-RSRP sample indicates coverage weakness.'},
    'MV_PS Drop Call Rate %': {'direction':'low_good','threshold':1.0,'weight':1.2,'min_scale':0.2,'number_format':'0.00','friendly':'PS Drop Call Rate (%)','rule':'High drop rate indicates retainability / mobility issue.'},
    'MV_VoLTE CSSR%': {'direction':'high_good','threshold':98.5,'weight':1.0,'min_scale':0.5,'number_format':'0.00','friendly':'VoLTE CSSR (%)','rule':'Low VoLTE CSSR points to accessibility issue.'},
    'Avg RSSI PUSCH': {'direction':'low_good','threshold':-103,'weight':0.8,'min_scale':1.5,'number_format':'0.00','friendly':'Avg RSSI PUSCH (dBm)','rule':'Less negative UL RSSI suggests high noise rise / UL interference.'},
    'Avg RSSI PUCCH': {'direction':'low_good','threshold':-103,'weight':0.7,'min_scale':1.5,'number_format':'0.00','friendly':'Avg RSSI PUCCH (dBm)','rule':'Less negative UL RSSI suggests high noise rise / UL interference.'},
    'UL NI [RSSI-SINR]': {'direction':'low_good','threshold':-113,'weight':0.8,'min_scale':1.0,'number_format':'0.00','friendly':'UL Noise Indicator (dBm)','rule':'Less negative UL NI suggests UL interference / noise rise.'},
    'MV_VoLTE Packet Loss DL': {'direction':'low_good','threshold':0.3,'weight':0.8,'min_scale':0.1,'number_format':'0.00','friendly':'VoLTE Packet Loss DL (%)','rule':'VoLTE packet loss points to IP/QoS or severe radio issue.'},
    'MV_VoLTE Packet Loss UL': {'direction':'low_good','threshold':0.3,'weight':0.8,'min_scale':0.1,'number_format':'0.00','friendly':'VoLTE Packet Loss UL (%)','rule':'VoLTE packet loss points to IP/QoS or severe radio issue.'},
}

FRIENDLY_MAP = {k: v['friendly'] for k, v in KPI_META.items()}

RCA_RULE_DEFAULTS = {
    'coverage_rsrp_poor': 35.0,
    'coverage_ue_distance': 1.0,
    'coverage_pusch_sinr_low': 5.0,
    'coverage_dl_tp_low': 5.0,
    'ul_interference_pusch_sinr_low': 5.0,
    'ul_interference_rssi_pusch_high': -103.0,
    'ul_interference_ul_ni_high': -113.0,
    'ul_interference_packet_loss_ul_high': 3.0,
    'congestion_dl_prb_high': 70.0,
    'congestion_dl_tp_low': 5.0,
    'congestion_pusch_sinr_low': 5.0,
    'congestion_connected_users_high': 40.0,
    'mobility_drop_rate_high': 1.0,
    'mobility_volte_dcr_high': 1.0,
    'mobility_hosr_low': 95.0,
    'accessibility_rrc_setup_low': 98.0,
    'accessibility_erab_setup_low': 98.5,
    'accessibility_volte_erab_setup_low': 98.5,
    'accessibility_volte_cssr_low': 98.5,
    'availability_radio_low': 97.0,
    'transport_packet_loss_dl_high': 0.1,
    'transport_packet_loss_ul_high': 3.0,
    'transport_volte_packet_loss_dl_high': 0.5,
    'transport_volte_packet_loss_ul_high': 0.5,
    'transport_dl_tp_low': 5.0,
    'transport_volte_dcr_high': 1.0,
    'transport_drop_rate_high': 1.0,
    'transport_volte_cssr_low': 98.5,
    'radio_quality_pusch_sinr_low': 6.0,
    'radio_quality_cqi_low': 7.0,
    'throughput_dl_tp_low': 5.0,
}
RCA_RULES = dict(RCA_RULE_DEFAULTS)

SEVERITY_DEFAULTS = {
    'critical_score': 35.0,
    'major_score': 22.0,
    'moderate_score': 15.0,
    'critical_drop_rate': 3.0,
    'critical_volte_dcr': 5.0,
}
SEVERITY_RULES = dict(SEVERITY_DEFAULTS)

ANOMALY_DEFAULTS = {
    'is_anomaly_score_threshold': 15.0,
    'fallback_general_score_threshold': 18.0,
}
ANOMALY_RULES = dict(ANOMALY_DEFAULTS)


def _rule_value(group, key, default):
    value = group.get(key, default)
    try:
        return float(value)
    except (TypeError, ValueError):
        return float(default)

def median_skipna(s):
    return s.median(skipna=True)

def mad_skipna(s):
    med = s.median(skipna=True)
    return float(np.nanmedian(np.abs(s.to_numpy(dtype=float) - med)))

def primary_cause(causes):
    priority = [
        'Availability / outage','Accessibility / setup failure','Congestion / capacity',
        'UL interference / noise rise','Coverage gap / overshooting','Mobility / handover issue',
        'Transport / packet loss','Radio quality degradation','Throughput degradation','General degradation'
    ]
    for item in priority:
        if item in causes:
            return item
    return 'No major anomaly'

def severity_from_row(row):
    critical_score = _rule_value(SEVERITY_RULES, 'critical_score', SEVERITY_DEFAULTS['critical_score'])
    major_score = _rule_value(SEVERITY_RULES, 'major_score', SEVERITY_DEFAULTS['major_score'])
    moderate_score = _rule_value(SEVERITY_RULES, 'moderate_score', SEVERITY_DEFAULTS['moderate_score'])
    critical_drop_rate = _rule_value(SEVERITY_RULES, 'critical_drop_rate', SEVERITY_DEFAULTS['critical_drop_rate'])
    critical_volte_dcr = _rule_value(SEVERITY_RULES, 'critical_volte_dcr', SEVERITY_DEFAULTS['critical_volte_dcr'])
    if row['Primary Cause'] == 'Availability / outage' or row['Anomaly Score'] >= critical_score or (pd.notna(row['MV_PS Drop Call Rate %']) and row['MV_PS Drop Call Rate %'] >= critical_drop_rate) or (pd.notna(row['MV_VoLTE DCR']) and row['MV_VoLTE DCR'] >= critical_volte_dcr):
        return 'Critical'
    if row['Anomaly Score'] >= major_score:
        return 'Major'
    if row['Anomaly Score'] >= moderate_score:
        return 'Moderate'
    return 'Watch'

def build_output(input_path=INPUT_PATH, output_xlsx=OUTPUT_XLSX):
    df = pd.read_excel(input_path, usecols=lambda c: c in SELECTED_COLS)
    df['Date'] = pd.to_datetime(df['Date'])
    df = df.sort_values(['Short name','Date']).reset_index(drop=True)

    extra_medians = {
        'MV_NW Avg Connected User': df.groupby('Short name')['MV_NW Avg Connected User'].transform('median'),
        'MV_4G Data Volume_GB': df.groupby('Short name')['MV_4G Data Volume_GB'].transform('median'),
    }

    score_dict, flag_dict, median_dict = {}, {}, {}
    for kpi, meta in KPI_META.items():
        grp = df.groupby('Short name')[kpi]
        med = grp.transform(median_skipna)
        mad = grp.transform(mad_skipna)
        scale = (mad * 1.4826).fillna(0).clip(lower=meta['min_scale'])
        values = df[kpi]
        if meta['direction'] == 'high_good':
            z = ((med - values) / scale).clip(lower=0, upper=5)
            threshold_breach = values < meta['threshold']
        else:
            z = ((values - med) / scale).clip(lower=0, upper=5)
            threshold_breach = values > meta['threshold']
        score_dict[kpi] = (z + threshold_breach.astype(float) * 1.5) * meta['weight']
        flag_dict[kpi] = (z >= 2) | threshold_breach
        median_dict[kpi] = med

    score_df = pd.DataFrame(score_dict)
    flag_df = pd.DataFrame(flag_dict)
    df['Anomaly Score'] = score_df.sum(axis=1).round(2)
    df['Flag Count'] = flag_df.sum(axis=1)

    def get_root_causes(row):
        coverage_rsrp_poor = _rule_value(RCA_RULES, 'coverage_rsrp_poor', RCA_RULE_DEFAULTS['coverage_rsrp_poor'])
        coverage_ue_distance = _rule_value(RCA_RULES, 'coverage_ue_distance', RCA_RULE_DEFAULTS['coverage_ue_distance'])
        coverage_pusch_sinr_low = _rule_value(RCA_RULES, 'coverage_pusch_sinr_low', RCA_RULE_DEFAULTS['coverage_pusch_sinr_low'])
        coverage_dl_tp_low = _rule_value(RCA_RULES, 'coverage_dl_tp_low', RCA_RULE_DEFAULTS['coverage_dl_tp_low'])
        ul_interference_pusch_sinr_low = _rule_value(RCA_RULES, 'ul_interference_pusch_sinr_low', RCA_RULE_DEFAULTS['ul_interference_pusch_sinr_low'])
        ul_interference_rssi_pusch_high = _rule_value(RCA_RULES, 'ul_interference_rssi_pusch_high', RCA_RULE_DEFAULTS['ul_interference_rssi_pusch_high'])
        ul_interference_ul_ni_high = _rule_value(RCA_RULES, 'ul_interference_ul_ni_high', RCA_RULE_DEFAULTS['ul_interference_ul_ni_high'])
        ul_interference_packet_loss_ul_high = _rule_value(RCA_RULES, 'ul_interference_packet_loss_ul_high', RCA_RULE_DEFAULTS['ul_interference_packet_loss_ul_high'])
        congestion_dl_prb_high = _rule_value(RCA_RULES, 'congestion_dl_prb_high', RCA_RULE_DEFAULTS['congestion_dl_prb_high'])
        congestion_dl_tp_low = _rule_value(RCA_RULES, 'congestion_dl_tp_low', RCA_RULE_DEFAULTS['congestion_dl_tp_low'])
        congestion_pusch_sinr_low = _rule_value(RCA_RULES, 'congestion_pusch_sinr_low', RCA_RULE_DEFAULTS['congestion_pusch_sinr_low'])
        congestion_connected_users_high = _rule_value(RCA_RULES, 'congestion_connected_users_high', RCA_RULE_DEFAULTS['congestion_connected_users_high'])
        mobility_drop_rate_high = _rule_value(RCA_RULES, 'mobility_drop_rate_high', RCA_RULE_DEFAULTS['mobility_drop_rate_high'])
        mobility_volte_dcr_high = _rule_value(RCA_RULES, 'mobility_volte_dcr_high', RCA_RULE_DEFAULTS['mobility_volte_dcr_high'])
        mobility_hosr_low = _rule_value(RCA_RULES, 'mobility_hosr_low', RCA_RULE_DEFAULTS['mobility_hosr_low'])
        accessibility_rrc_setup_low = _rule_value(RCA_RULES, 'accessibility_rrc_setup_low', RCA_RULE_DEFAULTS['accessibility_rrc_setup_low'])
        accessibility_erab_setup_low = _rule_value(RCA_RULES, 'accessibility_erab_setup_low', RCA_RULE_DEFAULTS['accessibility_erab_setup_low'])
        accessibility_volte_erab_setup_low = _rule_value(RCA_RULES, 'accessibility_volte_erab_setup_low', RCA_RULE_DEFAULTS['accessibility_volte_erab_setup_low'])
        accessibility_volte_cssr_low = _rule_value(RCA_RULES, 'accessibility_volte_cssr_low', RCA_RULE_DEFAULTS['accessibility_volte_cssr_low'])
        availability_radio_low = _rule_value(RCA_RULES, 'availability_radio_low', RCA_RULE_DEFAULTS['availability_radio_low'])
        transport_packet_loss_dl_high = _rule_value(RCA_RULES, 'transport_packet_loss_dl_high', RCA_RULE_DEFAULTS['transport_packet_loss_dl_high'])
        transport_packet_loss_ul_high = _rule_value(RCA_RULES, 'transport_packet_loss_ul_high', RCA_RULE_DEFAULTS['transport_packet_loss_ul_high'])
        transport_volte_packet_loss_dl_high = _rule_value(RCA_RULES, 'transport_volte_packet_loss_dl_high', RCA_RULE_DEFAULTS['transport_volte_packet_loss_dl_high'])
        transport_volte_packet_loss_ul_high = _rule_value(RCA_RULES, 'transport_volte_packet_loss_ul_high', RCA_RULE_DEFAULTS['transport_volte_packet_loss_ul_high'])
        transport_dl_tp_low = _rule_value(RCA_RULES, 'transport_dl_tp_low', RCA_RULE_DEFAULTS['transport_dl_tp_low'])
        transport_volte_dcr_high = _rule_value(RCA_RULES, 'transport_volte_dcr_high', RCA_RULE_DEFAULTS['transport_volte_dcr_high'])
        transport_drop_rate_high = _rule_value(RCA_RULES, 'transport_drop_rate_high', RCA_RULE_DEFAULTS['transport_drop_rate_high'])
        transport_volte_cssr_low = _rule_value(RCA_RULES, 'transport_volte_cssr_low', RCA_RULE_DEFAULTS['transport_volte_cssr_low'])
        radio_quality_pusch_sinr_low = _rule_value(RCA_RULES, 'radio_quality_pusch_sinr_low', RCA_RULE_DEFAULTS['radio_quality_pusch_sinr_low'])
        radio_quality_cqi_low = _rule_value(RCA_RULES, 'radio_quality_cqi_low', RCA_RULE_DEFAULTS['radio_quality_cqi_low'])
        throughput_dl_tp_low = _rule_value(RCA_RULES, 'throughput_dl_tp_low', RCA_RULE_DEFAULTS['throughput_dl_tp_low'])
        fallback_general_score_threshold = _rule_value(ANOMALY_RULES, 'fallback_general_score_threshold', ANOMALY_DEFAULTS['fallback_general_score_threshold'])

        coverage = (
            pd.notna(row['MV_% RSRP Sample <-110 dBm']) and row['MV_% RSRP Sample <-110 dBm'] > coverage_rsrp_poor and
            ((pd.notna(row['Average UE Distance_KM']) and row['Average UE Distance_KM'] > coverage_ue_distance) or
             (pd.notna(row['MV_PUSCH SINR']) and row['MV_PUSCH SINR'] < coverage_pusch_sinr_low) or
             (pd.notna(row['MV_DL User Throughput_Mbps']) and row['MV_DL User Throughput_Mbps'] < coverage_dl_tp_low))
        )
        ul_interference = (
            pd.notna(row['MV_PUSCH SINR']) and row['MV_PUSCH SINR'] < ul_interference_pusch_sinr_low and
            (((pd.notna(row['Avg RSSI PUSCH']) and row['Avg RSSI PUSCH'] > ul_interference_rssi_pusch_high)) or
             ((pd.notna(row['UL NI [RSSI-SINR]']) and row['UL NI [RSSI-SINR]'] > ul_interference_ul_ni_high)) or
             ((pd.notna(row['MV_Packet Loss UL']) and row['MV_Packet Loss UL'] > ul_interference_packet_loss_ul_high)))
        )
        congestion = (
            pd.notna(row['DL PRB Utilisation']) and row['DL PRB Utilisation'] > congestion_dl_prb_high and
            (((pd.notna(row['MV_DL User Throughput_Mbps']) and row['MV_DL User Throughput_Mbps'] < congestion_dl_tp_low)) or
             ((pd.notna(row['MV_PUSCH SINR']) and row['MV_PUSCH SINR'] < congestion_pusch_sinr_low)) or
             ((pd.notna(row['MV_NW Avg Connected User']) and row['MV_NW Avg Connected User'] > congestion_connected_users_high)))
        )
        mobility = (
            (((pd.notna(row['MV_PS Drop Call Rate %']) and row['MV_PS Drop Call Rate %'] > mobility_drop_rate_high)) or
             ((pd.notna(row['MV_VoLTE DCR']) and row['MV_VoLTE DCR'] > mobility_volte_dcr_high))) and
            (((pd.notna(row['PS IntraF HOSR [CDBH]']) and row['PS IntraF HOSR [CDBH]'] < mobility_hosr_low)) or
             ((pd.notna(row['PS InterF HOSR']) and row['PS InterF HOSR'] < mobility_hosr_low)) or
             ((pd.notna(row['VoLTE IntraF HOSR [CBBH]']) and row['VoLTE IntraF HOSR [CBBH]'] < mobility_hosr_low)) or
             ((pd.notna(row['VoLTE InterF HOSR']) and row['VoLTE InterF HOSR'] < mobility_hosr_low)))
        )
        accessibility = (
            ((pd.notna(row['RRC Setup Success Rate']) and row['RRC Setup Success Rate'] < accessibility_rrc_setup_low)) or
            ((pd.notna(row['MV_ERAB Setup Success Rate']) and row['MV_ERAB Setup Success Rate'] < accessibility_erab_setup_low)) or
            ((pd.notna(row['VoLTE ERAB Setup Success Rate']) and row['VoLTE ERAB Setup Success Rate'] < accessibility_volte_erab_setup_low)) or
            ((pd.notna(row['MV_VoLTE CSSR%']) and row['MV_VoLTE CSSR%'] < accessibility_volte_cssr_low))
        )
        availability = pd.notna(row['MV_Radio NW Availability']) and row['MV_Radio NW Availability'] < availability_radio_low
        transport = (
            ((((pd.notna(row['MV_Packet Loss DL']) and row['MV_Packet Loss DL'] > transport_packet_loss_dl_high)) or
              ((pd.notna(row['MV_Packet Loss UL']) and row['MV_Packet Loss UL'] > transport_packet_loss_ul_high)) or
              ((pd.notna(row['MV_VoLTE Packet Loss DL']) and row['MV_VoLTE Packet Loss DL'] > transport_volte_packet_loss_dl_high)) or
              ((pd.notna(row['MV_VoLTE Packet Loss UL']) and row['MV_VoLTE Packet Loss UL'] > transport_volte_packet_loss_ul_high))) and
             (((pd.notna(row['MV_DL User Throughput_Mbps']) and row['MV_DL User Throughput_Mbps'] < transport_dl_tp_low)) or
              ((pd.notna(row['MV_VoLTE DCR']) and row['MV_VoLTE DCR'] > transport_volte_dcr_high)) or
              ((pd.notna(row['MV_PS Drop Call Rate %']) and row['MV_PS Drop Call Rate %'] > transport_drop_rate_high)) or
              ((pd.notna(row['MV_VoLTE CSSR%']) and row['MV_VoLTE CSSR%'] < transport_volte_cssr_low)))) and
            not availability
        )

        causes = []
        if availability: causes.append('Availability / outage')
        if congestion: causes.append('Congestion / capacity')
        if coverage: causes.append('Coverage gap / overshooting')
        if ul_interference: causes.append('UL interference / noise rise')
        if mobility: causes.append('Mobility / handover issue')
        if accessibility: causes.append('Accessibility / setup failure')
        if transport: causes.append('Transport / packet loss')
        if not causes and row['Anomaly Score'] >= fallback_general_score_threshold:
            if pd.notna(row['MV_PUSCH SINR']) and pd.notna(row['MV_E-UTRAN Average CQI']) and row['MV_PUSCH SINR'] < radio_quality_pusch_sinr_low and row['MV_E-UTRAN Average CQI'] < radio_quality_cqi_low:
                causes.append('Radio quality degradation')
            elif pd.notna(row['MV_DL User Throughput_Mbps']) and row['MV_DL User Throughput_Mbps'] < throughput_dl_tp_low:
                causes.append('Throughput degradation')
            else:
                causes.append('General degradation')
        return causes

    def top_drivers_for_row(idx, n=4):
        top = score_df.loc[idx].sort_values(ascending=False)
        top = top[top > 0].head(n)
        return ', '.join([f"{FRIENDLY_MAP[k]} ({v:.1f})" for k, v in top.items()])

    def value_fmt(v):
        if pd.isna(v):
            return 'NA'
        v = float(v)
        if abs(v) >= 100:
            return f"{v:.0f}"
        return f"{v:.2f}"

    def evidence_for_row(row):
        parts = []
        def add(kpi, baseline_source='median'):
            actual = row[kpi]
            if pd.isna(actual):
                return
            if baseline_source == 'median':
                baseline = median_dict[kpi].iloc[row.name]
                limit = KPI_META[kpi]['threshold']
                label = FRIENDLY_MAP.get(kpi, kpi)
                parts.append(f"{label}: {value_fmt(actual)} vs median {value_fmt(baseline)} (limit {value_fmt(limit)})")
            elif baseline_source == 'extra':
                baseline = extra_medians[kpi].iloc[row.name]
                parts.append(f"{kpi}: {value_fmt(actual)} vs median {value_fmt(baseline)}")

        cause = row['Primary Cause']
        if cause == 'Availability / outage':
            for k in ['MV_Radio NW Availability','RRC Setup Success Rate','MV_ERAB Setup Success Rate']:
                add(k)
        elif cause == 'Accessibility / setup failure':
            for k in ['RRC Setup Success Rate','MV_ERAB Setup Success Rate','VoLTE ERAB Setup Success Rate','MV_VoLTE CSSR%']:
                add(k)
        elif cause == 'Congestion / capacity':
            for k in ['DL PRB Utilisation','MV_DL User Throughput_Mbps','MV_PUSCH SINR']:
                add(k)
            add('MV_NW Avg Connected User', baseline_source='extra')
        elif cause == 'UL interference / noise rise':
            for k in ['MV_PUSCH SINR','Avg RSSI PUSCH','UL NI [RSSI-SINR]','MV_Packet Loss UL']:
                add(k)
        elif cause == 'Coverage gap / overshooting':
            for k in ['MV_% RSRP Sample <-110 dBm','Average UE Distance_KM','MV_DL User Throughput_Mbps','MV_PUSCH SINR']:
                add(k)
        elif cause == 'Mobility / handover issue':
            for k in ['MV_PS Drop Call Rate %','MV_VoLTE DCR','PS IntraF HOSR [CDBH]','VoLTE IntraF HOSR [CBBH]']:
                add(k)
        elif cause == 'Transport / packet loss':
            for k in ['MV_Packet Loss DL','MV_Packet Loss UL','MV_VoLTE Packet Loss DL','MV_DL User Throughput_Mbps']:
                add(k)
        else:
            for k in score_df.loc[row.name].sort_values(ascending=False).index[:4]:
                if score_df.loc[row.name, k] > 0:
                    add(k)
        return '; '.join(parts[:4])

    reco_map = {
        'Availability / outage': "Nokia RF action: check eNB/BTS alarms, locked cells, RF module/RET status, feeder/VSWR and backhaul health. Restore availability first, then verify RRC/ERAB recovery.",
        'Accessibility / setup failure': "Nokia RF action: audit RRC/ERAB admission failures, S1/MME transport and license/capacity counters. Tune admission control and setup timers only after checking congestion and transport.",
        'Congestion / capacity': "Nokia RF action: optimize SON/MLB and CIO offsets for load balancing, verify scheduler/QCI prioritization, and check MIMO rank utilization. If persistent, offload traffic to adjacent layers/bands or plan capacity expansion.",
        'UL interference / noise rise': "Nokia RF action: run UL interference/noise-rise analysis, check PIM/external interferers, and verify uplink power control (P0/alpha/closed loop). Review overshooting neighbors and PCI environment.",
        'Coverage gap / overshooting': "Nokia RF action: audit antenna tilt/azimuth and overshooting behavior, verify RET execution, feeder health and missing neighbors. For coverage holes, consider reducing electrical down-tilt by 1–2°; for overshooting, increase tilt and correct neighbor dominance.",
        'Mobility / handover issue': "Nokia RF action: optimize A3 offset, hysteresis, TTT and CIO, validate ANR neighbor list, and inspect X2/S1 HO failure counters. Focus on late HO, missing neighbors and ping-pong.",
        'Transport / packet loss': "Nokia RF action: check IP backhaul latency/jitter, QoS mapping, microwave/TX errors and packet discard. Validate whether packet loss is transport-driven or secondary to poor radio quality.",
        'Radio quality degradation': "Nokia RF action: review SINR/CQI deterioration, neighbor interference, PCI planning and UL noise indicators. Apply interference mitigation before throughput optimization.",
        'Throughput degradation': "Nokia RF action: review scheduler behavior, MIMO utilization, bearer/QCI mix and radio quality. Correlate throughput with PRB, SINR and coverage before changing load-balancing thresholds.",
        'General degradation': "Nokia RF action: inspect the top KPI drivers first, verify alarms and recent changes, then tune the relevant RF domain (coverage, interference, mobility or accessibility).",
        'No major anomaly': ''
    }

    df['Root Causes'] = df.apply(get_root_causes, axis=1)
    df['Primary Cause'] = df['Root Causes'].apply(primary_cause)
    df['Secondary Causes'] = df['Root Causes'].apply(lambda x: ', '.join([c for c in x if c != primary_cause(x)]))
    df['Severity'] = df.apply(severity_from_row, axis=1)
    is_anomaly_score_threshold = _rule_value(ANOMALY_RULES, 'is_anomaly_score_threshold', ANOMALY_DEFAULTS['is_anomaly_score_threshold'])
    df['Is Anomaly'] = ((df['Anomaly Score'] >= is_anomaly_score_threshold) | (df['Root Causes'].str.len() > 0))
    df['Top KPI Drivers'] = [top_drivers_for_row(i) for i in df.index]
    df['Evidence'] = df.apply(evidence_for_row, axis=1)
    df['Nokia Recommendation'] = df['Primary Cause'].map(reco_map)

    anomaly_details = df[df['Is Anomaly']].copy().sort_values(['Anomaly Score','Date'], ascending=[False, True]).reset_index(drop=True)

    summary_root = anomaly_details.groupby('Primary Cause').agg(
        Anomaly_Count=('Short name','size'),
        Avg_Score=('Anomaly Score','mean'),
        Critical_Count=('Severity', lambda s: int((s=='Critical').sum())),
        Major_Count=('Severity', lambda s: int((s=='Major').sum())),
    ).reset_index().sort_values('Anomaly_Count', ascending=False)
    summary_root['Avg_Score'] = summary_root['Avg_Score'].round(2)

    summary_cells = anomaly_details.groupby(['Short name',"Site I'd",'Tech']).agg(
        Anomaly_Count=('Short name','size'),
        Max_Score=('Anomaly Score','max'),
        Top_Cause=('Primary Cause', lambda s: s.value_counts().index[0]),
    ).reset_index().sort_values(['Anomaly_Count','Max_Score'], ascending=[False,False]).head(20)
    summary_cells['Max_Score'] = summary_cells['Max_Score'].round(2)

    summary_dates = anomaly_details.groupby('Date').agg(
        Anomaly_Count=('Short name','size'),
        Critical_Count=('Severity', lambda s: int((s=='Critical').sum())),
        Avg_Score=('Anomaly Score','mean'),
    ).reset_index().sort_values('Date')
    summary_dates['Avg_Score'] = summary_dates['Avg_Score'].round(2)

    all_scored = df[[
        'Short name','Date',"Cell I'd","Site I'd",'Tech',
        'Severity','Is Anomaly','Anomaly Score','Flag Count','Primary Cause','Secondary Causes',
        'Top KPI Drivers','Evidence','Nokia Recommendation',
        'MV_DL User Throughput_Mbps','UL User Throughput_Kbps','DL PRB Utilisation',
        'MV_PUSCH SINR','MV_E-UTRAN Average CQI','MV_% RSRP Sample <-110 dBm',
        'Average UE Distance_KM','MV_PS Drop Call Rate %','MV_VoLTE DCR',
        'RRC Setup Success Rate','MV_ERAB Setup Success Rate','VoLTE ERAB Setup Success Rate',
        'PS IntraF HOSR [CDBH]','PS InterF HOSR','VoLTE IntraF HOSR [CBBH]','VoLTE InterF HOSR',
        'MV_Packet Loss DL','MV_Packet Loss UL','MV_Radio NW Availability',
        'Avg RSSI PUSCH','Avg RSSI PUCCH','UL NI [RSSI-SINR]','MV_NW Avg Connected User','MV_4G Data Volume_GB','VoLTE Traffic'
    ]].copy().sort_values(['Is Anomaly','Anomaly Score'], ascending=[False,False])

    details_cols = [
        'Short name','Date',"Cell I'd","Site I'd",'Tech','Severity','Anomaly Score','Flag Count',
        'Primary Cause','Secondary Causes','Top KPI Drivers','Evidence','Nokia Recommendation',
        'MV_DL User Throughput_Mbps','UL User Throughput_Kbps','DL PRB Utilisation',
        'MV_PUSCH SINR','MV_E-UTRAN Average CQI','MV_% RSRP Sample <-110 dBm',
        'Average UE Distance_KM','MV_PS Drop Call Rate %','MV_VoLTE DCR',
        'RRC Setup Success Rate','MV_ERAB Setup Success Rate','VoLTE ERAB Setup Success Rate',
        'PS IntraF HOSR [CDBH]','PS InterF HOSR','VoLTE IntraF HOSR [CBBH]','VoLTE InterF HOSR',
        'MV_Packet Loss DL','MV_Packet Loss UL','MV_Radio NW Availability',
        'Avg RSSI PUSCH','Avg RSSI PUCCH','UL NI [RSSI-SINR]','MV_NW Avg Connected User'
    ]
    anomaly_details = anomaly_details[details_cols]

    rules_df = pd.DataFrame([
        {'KPI':k,'Friendly Name':m['friendly'],'Direction':'Higher is better' if m['direction']=='high_good' else 'Lower is better',
         'Rule Threshold':m['threshold'],'Weight in Score':m['weight'],'Min Daily Scale Used':m['min_scale'],'RCA Hint':m['rule']}
        for k,m in KPI_META.items()
    ])

    wb = Workbook()
    ws_summary = wb.active
    ws_summary.title = 'Summary'
    header_fill = PatternFill('solid', fgColor='1F4E78')
    subheader_fill = PatternFill('solid', fgColor='D9EAF7')
    orange_fill = PatternFill('solid', fgColor='FCE4D6')
    teal_fill = PatternFill('solid', fgColor='DDEBF7')
    green_fill = PatternFill('solid', fgColor='E2F0D9')
    red_fill = PatternFill('solid', fgColor='FDE9E7')
    gray_fill = PatternFill('solid', fgColor='E7E6E6')
    thin_gray = Side(style='thin', color='BFBFBF')
    border = Border(bottom=thin_gray)

    def write_df(ws, start_row, start_col, frame):
        for c_idx, col in enumerate(frame.columns, start=start_col):
            c = ws.cell(start_row, c_idx, col)
            c.font = Font(bold=True, color='FFFFFF')
            c.fill = header_fill
            c.alignment = Alignment(horizontal='center', vertical='center', wrap_text=True)
        for r_idx, row in enumerate(frame.itertuples(index=False), start=start_row+1):
            for c_idx, value in enumerate(row, start=start_col):
                c = ws.cell(r_idx, c_idx, value)
                c.alignment = Alignment(vertical='top', wrap_text=True)
                if r_idx % 2 == 0:
                    c.fill = PatternFill('solid', fgColor='F8FBFF')
        return start_row + len(frame) + 1

    ws_summary['A1'] = 'Nokia KPI Anomaly Detection + RCA Summary'
    ws_summary['A1'].font = Font(bold=True, size=14, color='FFFFFF')
    ws_summary['A1'].fill = header_fill
    ws_summary.merge_cells('A1:F1')
    metrics = [
        f"Input rows analysed: {len(df):,}",
        f"Unique cells: {df['Short name'].nunique():,}",
        f"Date range: {df['Date'].min().date()} to {df['Date'].max().date()}",
        f"Detected anomalies: {len(anomaly_details):,}",
        f"Critical: {(anomaly_details['Severity']=='Critical').sum():,}",
        f"Major: {(anomaly_details['Severity']=='Major').sum():,}",
    ]
    for i, txt in enumerate(metrics, start=1):
        c = ws_summary.cell(2, i, txt)
        c.fill = subheader_fill
        c.font = Font(bold=True)
        c.alignment = Alignment(wrap_text=True)

    start = 4
    ws_summary[f'A{start-1}'] = 'Root Cause Summary'
    ws_summary[f'A{start-1}'].font = Font(bold=True)
    end = write_df(ws_summary, start, 1, summary_root)
    ws_summary[f'J{start-1}'] = 'Top Impacted Cells'
    ws_summary[f'J{start-1}'].font = Font(bold=True)
    write_df(ws_summary, start, 10, summary_cells)
    trend_row = end + 2
    ws_summary[f'A{trend_row-1}'] = 'Date-wise Anomaly Trend'
    ws_summary[f'A{trend_row-1}'].font = Font(bold=True)
    write_df(ws_summary, trend_row, 1, summary_dates)
    note_row = trend_row + len(summary_dates) + 3
    ws_summary[f'A{note_row}'] = 'Interpretation'
    ws_summary[f'A{note_row}'].font = Font(bold=True)
    ws_summary[f'A{note_row+1}'] = (
        "Anomaly score combines per-cell daily deviation (robust median/MAD) and engineering thresholds. "
        "RCA is rule-based with Nokia-style RF recommendations covering coverage, interference, congestion, accessibility, mobility, transport and availability."
    )
    ws_summary.merge_cells(start_row=note_row+1, start_column=1, end_row=note_row+3, end_column=12)
    ws_summary[f'A{note_row+1}'].alignment = Alignment(wrap_text=True, vertical='top')
    ws_summary[f'A{note_row+1}'].fill = gray_fill

    def add_table_sheet(name, frame):
        ws = wb.create_sheet(name)
        headers = list(frame.columns)
        for r_idx, row in enumerate([headers] + frame.values.tolist(), start=1):
            for c_idx, value in enumerate(row, start=1):
                ws.cell(r_idx, c_idx, value)
        for cell in ws[1]:
            cell.font = Font(bold=True, color='FFFFFF')
            cell.fill = header_fill
            cell.alignment = Alignment(horizontal='center', vertical='center', wrap_text=True)
            cell.border = border
        for r in range(2, ws.max_row+1):
            for c in range(1, ws.max_column+1):
                ws.cell(r,c).alignment = Alignment(vertical='top', wrap_text=True)
        return ws

    ws_details = add_table_sheet('Anomaly_Details', anomaly_details)
    ws_all = add_table_sheet('All_Data_Scored', all_scored)
    ws_rules = add_table_sheet('Rules_Thresholds', rules_df)

    severity_col = details_cols.index('Severity') + 1
    score_col = details_cols.index('Anomaly Score') + 1
    for r in range(2, ws_details.max_row+1):
        sev = ws_details.cell(r, severity_col).value
        fill = red_fill if sev == 'Critical' else orange_fill if sev == 'Major' else teal_fill if sev == 'Moderate' else green_fill
        for c in [1,2,6,7,8,9]:
            ws_details.cell(r,c).fill = fill

    ws_details.conditional_formatting.add(
        f"{get_column_letter(score_col)}2:{get_column_letter(score_col)}{ws_details.max_row}",
        ColorScaleRule(start_type='min', start_color='63BE7B', mid_type='percentile', mid_value=50, mid_color='FFEB84', end_type='max', end_color='F8696B')
    )

    widths = {
        'Summary': {1:26,2:18,3:22,4:18,5:18,6:18,10:28,11:14,12:12,13:26},
        'Anomaly_Details': {1:24,2:12,3:12,4:10,5:8,6:12,7:12,8:10,9:26,10:28,11:45,12:55,13:65,14:12,15:14,16:12,17:12,18:10,19:16,20:12,21:12,22:12,23:12,24:14,25:14,26:12,27:12,28:14,29:12,30:12,31:12,32:14,33:12,34:12,35:12,36:12},
        'All_Data_Scored': {1:24,2:12,3:12,4:10,5:8,6:12,7:10,8:12,9:10,10:26,11:26,12:45,13:55,14:65},
        'Rules_Thresholds': {1:28,2:28,3:18,4:14,5:14,6:16,7:60},
    }
    for ws in wb.worksheets:
        ws.freeze_panes = 'A2'
        if ws.title != 'Summary':
            ws.auto_filter.ref = f"A1:{get_column_letter(ws.max_column)}{ws.max_row}"
        for col_idx in range(1, ws.max_column+1):
            ws.column_dimensions[get_column_letter(col_idx)].width = widths.get(ws.title, {}).get(col_idx, 14)

    for ws_name in ['Anomaly_Details','All_Data_Scored']:
        ws = wb[ws_name]
        headers = [c.value for c in ws[1]]
        if 'Date' in headers:
            ci = headers.index('Date') + 1
            for r in range(2, ws.max_row+1):
                ws.cell(r, ci).number_format = 'yyyy-mm-dd'
        if 'Anomaly Score' in headers:
            ci = headers.index('Anomaly Score') + 1
            for r in range(2, ws.max_row+1):
                ws.cell(r, ci).number_format = '0.00'
        for kpi, meta in KPI_META.items():
            if kpi in headers:
                ci = headers.index(kpi) + 1
                for r in range(2, ws.max_row+1):
                    ws.cell(r, ci).number_format = meta['number_format']

    wb.save(output_xlsx)
    print(f"Saved workbook: {output_xlsx}")
    print(f"Detected anomalies: {len(anomaly_details)}")

if __name__ == "__main__":
    build_output()



# ----------------------------------------------------------------------
# Added in v2: Nokia parameter / feature recommendation mapping
# ----------------------------------------------------------------------

def build_nokia_parameter_recommendation(primary_cause, secondary_causes, evidence, top_kpi_drivers):
    """
    Returns:
        parameter_names_text,
        suggested_values_text,
        implementation_note_text
    Notes:
    - These are initial optimization actions for Nokia LTE troubleshooting.
    - Exact MO/parameter names can vary by software release and market template.
    - Use them only after counter, alarm, neighbour and transport validation.
    """
    text = " | ".join([
        "" if pd.isna(primary_cause) else str(primary_cause),
        "" if pd.isna(secondary_causes) else str(secondary_causes),
        "" if pd.isna(evidence) else str(evidence),
        "" if pd.isna(top_kpi_drivers) else str(top_kpi_drivers),
    ]).lower()

    params, values, notes = [], [], []

    def add(p, v, n=""):
        if p not in params:
            params.append(p); values.append(v); notes.append(n)

    if "mobility" in text or "handover" in text or "hosr" in text:
        add("a3Offset / a3TriggerQuantityHO", "Trial 2–3 dB", "Lower only when late HO or HOF is high")
        add("a3TimeToTrigger", "Trial 320–480 ms", "Increase for ping-pong; reduce for late HO")
        add("hysteresisA3", "Trial 1–2 dB", "Tune together with A3 offset")
        add("cellIndividualOffsetEUTRA", "Neighbour-specific ±1 to ±3 dB", "Apply only to dominant neighbours")
        add("ANR neighbour audit", "Add missing / remove wrong neighbours", "Validate before HO tuning")

    if "coverage" in text or "rsrp" in text or "distance" in text or "overshooting" in text:
        add("Electrical tilt (RET)", "Change by ±1.0° to ±2.0°", "First practical RF action")
        add("Azimuth", "Correct by ~5° to 15° after map check", "Use geo evidence before change")
        add("qRxLevMin", "Review only; avoid aggressive increase", "May hurt accessibility")
        add("RS power / ref signal power", "Trial +1 to +2 dB where allowed", "Use only when coverage hole is confirmed")

    if "congestion" in text or "prb" in text or "throughput" in text:
        add("MLB / Load balancing feature", "Enable or verify active", "Check thresholds and target neighbours")
        add("Scheduler profile", "Use fairness/QoS tuned profile", "Protect VoLTE and cell-edge users")
        add("MIMO / CA feature check", "Verify active and healthy", "Check capability and rank utilisation")
        add("Admission control thresholds", "Relax only after transport/licence validation", "Avoid masking root cause")

    if "accessibility" in text or "rrc setup" in text or "erab setup" in text or "cssr" in text:
        add("Admission control thresholds", "Review RRC/ERAB admission settings", "Check licence, CE, CPU and transport first")
        add("RRC / ERAB setup timers", "Use vendor baseline; retune only after failure cause check", "Do not change blindly")
        add("S1 / transport health check", "Packet loss near 0%; latency/jitter within baseline", "Escalate transport/core if unstable")
        add("VoLTE bearer/QCI mapping", "Verify QCI1/QCI5 template and ARP", "Needed when VoLTE setup is poor")

    if "drop" in text or "dcr" in text or "retainability" in text:
        add("a3Offset / hysteresisA3 / a3TimeToTrigger", "Retune per late HO vs ping-pong pattern", "Use HOF/RLF split")
        add("RLF / T310 / N310 review", "Keep near cluster baseline", "Use only when radio failure is confirmed")
        add("Neighbour list / ANR audit", "Clean missing/wrong neighbours", "Common cause of drops")
        add("Coverage optimisation", "Tilt/azimuth/RS power review", "Especially with poor RSRP")

    if "interference" in text or "sinr" in text or "cqi" in text or "ul ni" in text or "rssi" in text:
        add("p0NominalPUSCH", "Trial −1 to −2 dB step", "Monitor UL BLER and SINR")
        add("p0NominalPUCCH", "Trial −1 dB step", "Apply carefully")
        add("Interference-aware UL scheduling", "Enable / verify active", "Useful when UL RSSI/NI is high")
        add("PCI / collision-confusion audit", "Resolve conflicts on dominant neighbours", "Good RSRP + poor SINR pattern")
        add("UL power control audit", "Review alpha and closed-loop behaviour", "Check with layer/load context")

    if "packet loss" in text or "transport" in text:
        add("Transport / backhaul KPI check", "Target near-zero packet loss", "Not an RF-only issue")
        add("QoS / queue / shaping audit", "Prioritise VoLTE and signalling", "Check burst congestion")

    if "availability" in text or "outage" in text:
        add("Cell availability / lock / HW alarms", "100% expected", "Check TX path, board, sync and feeder")

    if not params:
        add("Cluster baseline audit", "Compare with same band/site top cells", "Use counters + alarms + neighbours before parameter change")

    return (
        "\\n".join(f"{i+1}. {p}" for i, p in enumerate(params)),
        "\\n".join(f"{i+1}. {v}" for i, v in enumerate(values)),
        "\\n".join(f"{i+1}. {n}" for i, n in enumerate(notes) if n),
    )



# ----------------------------------------------------------------------
# Added in v3: Ericsson recommendation / parameter / note mapping
# ----------------------------------------------------------------------

def build_ericsson_parameter_recommendation(primary_cause, secondary_causes, evidence, top_kpi_drivers):
    """
    Returns:
        ericsson_recommendation_text,
        ericsson_recommended_parameter_values_text,
        ericsson_implementation_note_text
    Notes:
    - These are initial Ericsson LTE optimization actions for anomaly RCA.
    - Exact MO/parameter names can vary by software release and market template.
    - Use them only after counter, alarm, neighbour and transport validation.
    """
    text = " | ".join([
        "" if pd.isna(primary_cause) else str(primary_cause),
        "" if pd.isna(secondary_causes) else str(secondary_causes),
        "" if pd.isna(evidence) else str(evidence),
        "" if pd.isna(top_kpi_drivers) else str(top_kpi_drivers),
    ]).lower()

    params, values, notes = [], [], []

    def add(p, v, n=""):
        if p not in params:
            params.append(p); values.append(v); notes.append(n)

    if "mobility" in text or "handover" in text or "hosr" in text:
        add("A3Offset", "Trial 2–3 dB", "Use after checking late HO, HOF and ping-pong split")
        add("TimeToTriggerA3", "Trial 320–480 ms", "Reduce for late HO; increase for ping-pong control")
        add("QHyst / Hysteresis", "Trial 1–2 dB", "Tune together with A3 offset")
        add("CIO / Qoffset", "Neighbour-specific ±1 to ±3 dB", "Use for dominant neighbour biasing or traffic steering")
        add("ANR / Neighbour relation audit", "Add missing strong neighbours; remove wrong overshoot neighbours", "Validate intra/inter-frequency relations first")

    if "coverage" in text or "rsrp" in text or "distance" in text or "overshooting" in text:
        add("RET / Electrical Tilt", "Change by ±1.0° to ±2.0°", "Most practical first RF action for coverage hole or overshoot")
        add("Azimuth", "Correct by ~5° to 15° after map validation", "Use geo/DT evidence before physical change")
        add("qRxLevMin", "Review only; avoid aggressive increase", "Can hurt accessibility at cell edge")
        add("Reference signal / pilot power", "Trial +1 to +2 dB where allowed", "Use only when coverage hole is confirmed and interference is acceptable")

    if "congestion" in text or "prb" in text or "throughput" in text:
        add("MLB / Mobility Load Balancing", "Enable or verify active", "Check threshold, trigger and target cells")
        add("Scheduler profile / QoS tuning", "Use fairness/QCI tuned profile", "Protect VoLTE and cell-edge users")
        add("CA / MIMO feature audit", "Verify active and healthy", "Check rank utilization and UE capability distribution")
        add("CIO / Qoffset", "Trial +1 to +2 dB toward target neighbour", "Traffic steering before physical changes")
        add("Admission control thresholds", "Relax only after transport/licence validation", "Avoid masking true congestion root cause")

    if "accessibility" in text or "rrc setup" in text or "erab setup" in text or "cssr" in text:
        add("Admission control thresholds", "Review RRC/ERAB admission settings", "Check CE, licence, CPU and transport first")
        add("RRC / ERAB setup timers", "Keep near vendor baseline; retune only after cause check", "Do not tune blindly")
        add("S1 / Transport health check", "Packet loss near 0%; latency/jitter within baseline", "Escalate transport/core if unstable")
        add("VoLTE bearer / QCI / ARP template", "Verify QCI1/QCI5 mapping", "Mis-match can hurt VoLTE setup success")

    if "drop" in text or "dcr" in text or "retainability" in text:
        add("A3Offset / TimeToTriggerA3 / QHyst", "Retune based on late HO vs ping-pong pattern", "Use HOF/RLF split before tuning")
        add("RLF timers / thresholds", "Keep near cluster baseline", "Use only when radio failure pattern is proven")
        add("ANR / Neighbour cleanup", "Clean missing/wrong neighbours", "Common cause of drops and HOF")
        add("Coverage optimization", "RET / azimuth / RS power review", "Especially when drop and poor RSRP coexist")

    if "interference" in text or "sinr" in text or "cqi" in text or "ul ni" in text or "rssi" in text:
        add("P0NominalPUSCH / UL power control", "Trial −1 to −2 dB step", "Monitor UL BLER, SINR and cell-edge coverage")
        add("P0NominalPUCCH", "Trial −1 dB step", "Apply carefully to avoid control channel degradation")
        add("UL interference mitigation feature", "Enable / verify active", "Useful when UL RSSI/NI is high")
        add("PCI / collision-confusion audit", "Resolve dominant neighbour conflicts", "Common for good RSRP but poor SINR")
        add("UL power control audit (alpha / closed loop)", "Review against cluster baseline", "Check with band and load context")

    if "packet loss" in text or "transport" in text:
        add("Transport / backhaul KPI check", "Target near-zero packet loss", "Do not treat as RF-only issue")
        add("QoS / queue / shaping audit", "Prioritise VoLTE and signalling", "Validate burst congestion and discard behavior")

    if "availability" in text or "outage" in text:
        add("Cell availability / HW alarm audit", "100% expected", "Check TX path, radio, sync, feeder and board state")

    if not params:
        add("Cluster baseline audit", "Compare against same band/layer best cells", "Use counters + alarms + neighbours before parameter change")

    return (
        "\\n".join(f"{i+1}. {p}" for i, p in enumerate(params)),
        "\\n".join(f"{i+1}. {v}" for i, v in enumerate(values)),
        "\\n".join(f"{i+1}. {n}" for i, n in enumerate(notes) if n),
    )
