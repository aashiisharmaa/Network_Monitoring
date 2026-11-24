"""Generate clearly labelled synthetic datasets; never upload or change customer files."""
import csv
import argparse
import datetime as dt
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'reports/performance/full-test-2026-11-19/test-data'


def main():
    global OUT
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument('--output', type=Path, default=OUT)
    OUT = cli.parse_args().output.resolve()
    OUT.mkdir(parents=True, exist_ok=True)
    records = []
    fields = ['date', 'cell_name', 'site', 'band', 'tech', 'sector_id', 'dl_prb_utilization_rate',
              'rrc_establishment_success_rate', 'e_rab_drop_rate', 'mvpuschsinr', 'mveutranaveragecqi']
    with (OUT / 'SYNTHETIC_kpi_hourly.csv').open('w', encoding='utf-8', newline='') as f:
        writer = csv.writer(f); writer.writerow(fields)
        for day in range(8):
            date = dt.date(2026, 11, 12) + dt.timedelta(days=day)
            for hour in range(24):
                for cell in range(6):
                    degraded = day == 7 and cell < 2
                    metrics = {'dlprbutilizationrate': 96 if degraded else 45 + cell,
                               'rrcestablishmentsuccessrate': 88 if degraded else 99.5,
                               'erabdroprate': 7 if degraded else .2,
                               'mvpuschsinr': -4 if degraded else 12,
                               'mveutranaveragecqi': 2 if degraded else 11}
                    name = f'DEMO_SITE_{cell//2+1}_L18_{"AB"[cell%2]}'
                    site = f'DEMO_SITE_{cell//2+1}'
                    writer.writerow([f'{date} {hour:02d}:00:00', name, site, 'L18', 'LTE', 'AB'[cell%2], *metrics.values()])
                    records.append({'date': str(date), 'cellName': name, 'site': site, 'tech': 'LTE', 'band': 'L18',
                                    'dimensions': {'hour': hour}, 'metrics': metrics})
    with (OUT / 'SYNTHETIC_sites.csv').open('w', encoding='utf-8', newline='') as f:
        writer = csv.writer(f)
        writer.writerow(['siteid', 'cell_name', 'latitude', 'longitude', 'pci', 'tac', 'earfcn', 'azimuth'])
        for cell in range(6):
            writer.writerow([f'DEMO_SITE_{cell//2+1}', f'DEMO_SITE_{cell//2+1}_L18_{"AB"[cell%2]}',
                             -1.95 + cell//2*.01, 30.05 + cell//2*.01, 10+cell, 43000, 1650, cell%2*180])
    for day in [18, 19]:
        with (OUT / f'SYNTHETIC_oss_2026-11-{day}.csv').open('w', encoding='utf-8', newline='') as f:
            writer = csv.writer(f);writer.writerow(['Circle', 'Resource', 'Alarm Number', 'Alarm Text', 'Severity', 'Alarm Time'])
            writer.writerow(['DEMO_CIRCLE', 'DEMO_NODE_1', '100', 'Synthetic transport failure', 'Critical', '2026-11-17 06:00:00'])
            if day == 18: writer.writerow(['DEMO_CIRCLE', 'DEMO_NODE_2', '200', 'Synthetic power alarm', 'Major', '2026-11-18 05:00:00'])
            else: writer.writerow(['DEMO_CIRCLE', 'DEMO_NODE_3', '300', 'Synthetic new alarm', 'Minor', '2026-11-19 05:00:00'])
    (OUT/'500.json').write_text(json.dumps({'synthetic': True, 'records': records}), encoding='utf-8')
    config = {'fileId': 500, 'cacheFile': str((OUT/'500.json').resolve()), 'request': {
        'vendor': 'nokia', 'columnMapping': {'Short name': 'cellname', 'Date': 'date', "Cell I'd": 'cellname', "Site I'd": 'site', 'Tech': 'tech'},
        'kpiMapping': {'MV_PUSCH SINR': 'mvpuschsinr', 'MV_E-UTRAN Average CQI': 'mveutranaveragecqi'},
        'selectedKpis': ['MV_PUSCH SINR', 'MV_E-UTRAN Average CQI']}}
    (OUT/'rca-benchmark-config.json').write_text(json.dumps(config, indent=2), encoding='utf-8')
    (OUT/'SYNTHETIC_invalid_coordinates.csv').write_text('siteid,cell_name,latitude,longitude\nBAD,BAD_L18_A,999,999\n', encoding='utf-8')
    (OUT/'SYNTHETIC_empty.csv').write_text('date,cell_name,rrc_establishment_success_rate\n', encoding='utf-8')
    (OUT/'SYNTHETIC_corrupt.xlsx').write_bytes(b'This is intentionally not an XLSX workbook.')
    manifest = {'synthetic': True, 'customerDataModified': False, 'rows': len(records), 'sites': 3, 'cells': 6,
                'dateRange': ['2026-11-12', '2026-11-19'], 'expectedDegradedCellsOnFinalDay': 2,
                'notes': ['Demonstration values, not customer-approved targets.', 'Missing OSS observations must not be assumed cleared.',
                          'Negative input files are deliberately invalid. Files are not automatically uploaded.']}
    (OUT/'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    print(json.dumps(manifest))


if __name__ == '__main__':
    main()
