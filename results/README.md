# results/

Raw data of experiments E1–E5. **Everything here comes from real runs on AWS** (ap-south-1, November 2026).
There are no example or synthetic numbers. Every table and chart in the final report is rebuilt from these
files, so nothing in the report is typed in by hand.

## Layout

```
results/
├── exp1-coldstart/                       E1 cold start (20 cycles x 100 requests per variant)
│   ├── snapstart-off | snapstart-on | snapstart-primed/
│   │   ├── probe.csv                     one row per request: client time, cold or warm, Init/Restore, billed time
│   │   ├── notes.json                    settings, Lambda versions published, time window
│   │   └── export/                       CloudWatch export of the run + cost.json / cost.csv (E5)
│   └── summary/                          coldstart_summary.csv (first vs 100th request) + charts
├── exp2-latency/                         E2 latency, 1 / 2 / 5 / 8 users, 3 runs each
│   ├── vus-<N>/run<K>/                   k6.csv.gz, CloudWatch export, notes.txt, cost.json / cost.csv
│   └── summary/                          latency_summary.csv, latency_per_run.csv + chart
├── exp3-scaling/                         E3 scaling: ramp, spike, capped (reserved concurrency 5), 3 runs each
│   └── <profile>/run<K>/                 k6.csv.gz, CloudWatch export, notes.txt, cost files,
│                                         summary/ (scaling_steps.csv, littles_law.csv, timeline chart)
├── exp4-race/                            E4 double booking
│   ├── transactional | naive/run<K>/     k6.csv.gz, consistency.json (table check), notes.txt
│   ├── cancel/                           cancel-versus-rebook race
│   └── summary/                          race_per_run.csv, race_per_slot.csv + chart
└── summary/                              E5: cost_summary.csv (per run), cost_by_config.csv, cost_per_1000.png
```

## Files in a run folder

| File | From | Contents |
|---|---|---|
| `k6.csv.gz` | k6 | every request: time, endpoint, status, latency seen by the client |
| `report_lines.csv` | CloudWatch Logs | Lambda `REPORT` lines: duration, billed duration, memory, Init / Restore Duration |
| `request_metrics.csv` | CloudWatch Logs | our timing line per request: total, DynamoDB time, cold flag |
| `api_access.csv` | CloudWatch Logs | API Gateway access log: latency and integration latency |
| `metrics.csv` | CloudWatch metrics | invocations, concurrent executions, throttles, errors, DynamoDB units |
| `summary.json`, `export.log` | `cloudwatch_export.py` | row counts and a log of the export |
| `notes.txt` / `notes.json` | run script | UTC time window, DeployLabel, Lambda version, memory, SnapStart, priming, booking mode, client (EC2 t3.micro in ap-south-1) |
| `cost.json`, `cost.csv` | `cost_calc.py` | E5: usage × prices from `experiments/prices.json`, cost per 1,000 requests, without the free tier |

## Rebuilding the summaries

```
python analysis/charts.py coldstart results/exp1-coldstart
python analysis/charts.py latency   results/exp2-latency
python analysis/charts.py scaling   results/exp3-scaling/<profile>/run<K>
python analysis/charts.py race      results/exp4-race
python analysis/charts.py cost      results
```

Each command writes CSV summaries and PNG charts into a `summary/` folder. The report's
`make_report_assets.py` reads those summaries and writes the report tables.
