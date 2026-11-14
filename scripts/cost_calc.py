"""Experiment 5: cost per 1,000 requests, computed from MEASURED usage and PUBLISHED prices.

The free tier hides the real price, so this calculates what the run would cost without it.

Inputs:
  --run      folder written by cloudwatch_export.py (report_lines.csv, metrics.csv)
  --prices   experiments/prices.json, filled in by you from the AWS pricing pages for ap-south-1
             (the script refuses to run while any price is null: it never guesses a price)

Formulas:
  Lambda   = invocations x request_price  +  sum(memory_GB x billed_seconds) x GB_second_price
             (billed time includes INIT on cold starts: AWS bills INIT since 1 Aug 2025; the script checks
              each REPORT line and adds Init Duration only if Billed Duration does not already contain it)
  API      = API requests x HTTP API price per request
  DynamoDB = write request units x WRU price + read request units x RRU price   (on-demand; transactional
             writes already appear doubled in ConsumedWriteCapacityUnits)
  Logs     = GB ingested x ingestion price

Usage:
  python scripts/cost_calc.py --run results/exp2-latency/vus-10/run1 --prices experiments/prices.json
Writes cost.csv and cost.json into the run folder.
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import sys
from pathlib import Path


def load_prices(path: Path) -> dict:
    prices = json.loads(path.read_text(encoding="utf-8"))
    missing = [k for k, v in prices["prices_usd"].items() if v is None]
    if missing:
        sys.exit(f"Fill these prices in {path} from the AWS pricing pages first: {', '.join(missing)}")
    return prices


def lambda_usage(report_csv: Path, architecture_note: str) -> dict:
    invocations, gb_seconds, cold, init_added = 0, 0.0, 0, 0
    with report_csv.open(encoding="utf-8") as fh:
        for r in csv.DictReader(fh):
            if not r.get("billed_ms"):
                continue
            invocations += 1
            memory_gb = float(r["memory_mb"]) / 1024
            billed = float(r["billed_ms"])
            duration = float(r["duration_ms"] or 0)
            init = float(r["init_ms"]) if r.get("init_ms") else 0.0
            restore = float(r["billed_restore_ms"]) if r.get("billed_restore_ms") else 0.0
            if init or r.get("restore_ms"):
                cold += 1
            if init and billed + 1 < math.ceil(duration + init):
                billed += math.ceil(init)       # INIT not inside Billed Duration on this line: add it
                init_added += 1
            gb_seconds += memory_gb * (billed + restore) / 1000
    return {"invocations": invocations, "gb_seconds": gb_seconds, "cold_starts": cold,
            "lines_with_init_added": init_added, "architecture": architecture_note}


def metric_sum(metrics_csv: Path, source: str, metric: str, resource_contains: str = "") -> float:
    total = 0.0
    with metrics_csv.open(encoding="utf-8") as fh:
        for r in csv.DictReader(fh):
            if r["source"] == source and r["metric"] == metric and resource_contains in r["resource"]:
                total += float(r["value"])
    return total


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--run", type=Path, required=True)
    parser.add_argument("--prices", type=Path, default=Path("experiments/prices.json"))
    parser.add_argument("--architecture", default="x86_64", choices=["x86_64", "arm64"])
    parser.add_argument("--bookings", type=int, help="number of successful bookings in the run (for cost/1000 bookings)")
    args = parser.parse_args()

    prices = load_prices(args.prices)
    p = prices["prices_usd"]
    lam = lambda_usage(args.run / "report_lines.csv", args.architecture)
    metrics = args.run / "metrics.csv"
    api_requests = metric_sum(metrics, "api", "Count")
    wru = metric_sum(metrics, "dynamodb", "ConsumedWriteCapacityUnits")
    rru = metric_sum(metrics, "dynamodb", "ConsumedReadCapacityUnits")
    log_gb = metric_sum(metrics, "logs", "IncomingBytes") / 1024 ** 3

    gb_s_price = p["lambda_gb_second_x86"] if args.architecture == "x86_64" else p["lambda_gb_second_arm"]
    costs = {
        "lambda_requests": lam["invocations"] * p["lambda_per_request"],
        "lambda_compute": lam["gb_seconds"] * gb_s_price,
        "api_gateway": api_requests * p["http_api_per_request"],
        "dynamodb_writes": wru * p["dynamodb_per_write_request_unit"],
        "dynamodb_reads": rru * p["dynamodb_per_read_request_unit"],
        "cloudwatch_logs": log_gb * p["cloudwatch_logs_ingest_per_gb"],
    }
    total = sum(costs.values())
    requests = api_requests or lam["invocations"]
    result = {
        "run": str(args.run), "prices_read_on": prices.get("read_on"), "region": prices.get("region"),
        "usage": {**lam, "api_requests": api_requests, "dynamodb_wru": wru, "dynamodb_rru": rru,
                  "log_gb_ingested": round(log_gb, 6)},
        "cost_usd": {k: round(v, 8) for k, v in costs.items()},
        "total_usd": round(total, 8),
        "cost_per_1000_requests_usd": round(1000 * total / requests, 6) if requests else None,
        "cost_per_1m_requests_usd": round(1_000_000 * total / requests, 4) if requests else None,
        "cost_per_1000_bookings_usd": round(1000 * total / args.bookings, 6) if args.bookings else None,
        "note": "Computed without free tier from measured usage. Not an AWS bill.",
    }
    (args.run / "cost.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    with (args.run / "cost.csv").open("w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["component", "usd", "usd_per_1000_requests"])
        for k, v in costs.items():
            w.writerow([k, round(v, 8), round(1000 * v / requests, 8) if requests else ""])
        w.writerow(["TOTAL", round(total, 8), result["cost_per_1000_requests_usd"]])
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
