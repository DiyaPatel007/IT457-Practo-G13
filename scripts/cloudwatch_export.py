"""Export everything CloudWatch measured during one experiment run into CSV files.

Writes into --out:
  metrics.csv          1-minute Lambda / API Gateway / DynamoDB / Logs metrics (for timelines and cost)
  report_lines.csv     every Lambda REPORT line (duration, billed, init/restore, memory) -> cost and cold starts
  request_metrics.csv  our JSON request log lines (route, status, total_ms, dynamo_ms, cold)
  api_access.csv       API Gateway access log lines (responseLatency, integrationLatency, status)
  summary.json         per-function and per-route percentiles computed from the above

Usage (times in UTC, as printed by k6 or noted in notes.txt):
  python scripts/cloudwatch_export.py --stack practo-lite --start 2026-11-20T10:00:00Z --end 2026-11-20T10:20:00Z \
      --out results/exp3-scaling/ramp-run1
  python scripts/cloudwatch_export.py --stack practo-lite --last-minutes 20 --out results/...
"""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import json
import re
import time
from pathlib import Path

import boto3

from practo_common import REGION, stack_outputs

RESTORE_RE = re.compile(r"Restore Duration: ([\d.]+) ms")
BILLED_RESTORE_RE = re.compile(r"Billed Restore Duration: ([\d.]+) ms")


def parse_time(s: str) -> dt.datetime:
    return dt.datetime.fromisoformat(s.replace("Z", "+00:00"))


def insights(logs, groups: list[str], query: str, start: dt.datetime, end: dt.datetime) -> list[dict]:
    """Runs a Logs Insights query in 1-hour slices (max 10,000 rows per query)."""
    rows = []
    t = start
    while t < end:
        t_end = min(t + dt.timedelta(hours=1), end)
        qid = logs.start_query(logGroupNames=groups, startTime=int(t.timestamp()), endTime=int(t_end.timestamp()),
                               queryString=query + " | limit 10000")["queryId"]
        while True:
            res = logs.get_query_results(queryId=qid)
            if res["status"] in ("Complete", "Failed", "Cancelled", "Timeout"):
                break
            time.sleep(2)
        if len(res.get("results", [])) >= 10000:
            print(f"WARNING: 10,000-row limit reached for {t}..{t_end}; use a shorter window")
        rows += [{c["field"]: c["value"] for c in r} for r in res.get("results", [])]
        t = t_end
    return rows


def metric_queries(out: dict) -> list[dict]:
    q = []

    def add(namespace, metric, dims, stat, label):
        q.append({"Id": f"m{len(q)}", "Label": label, "ReturnData": True, "MetricStat": {
            "Metric": {"Namespace": namespace, "MetricName": metric,
                       "Dimensions": [{"Name": k, "Value": v} for k, v in dims.items()]},
            "Period": 60, "Stat": stat}})

    for fn_key in ("UsersFunctionName", "DoctorsFunctionName", "AppointmentsFunctionName"):
        fn = out[fn_key]
        for metric, stat in (("Invocations", "Sum"), ("ConcurrentExecutions", "Maximum"), ("Throttles", "Sum"),
                             ("Errors", "Sum"), ("Duration", "p95")):
            add("AWS/Lambda", metric, {"FunctionName": fn}, stat, f"lambda|{fn}|{metric}|{stat}")
    api_id = out["ApiUrl"].split("//")[1].split(".")[0]
    for metric, stat in (("Count", "Sum"), ("4xx", "Sum"), ("5xx", "Sum"), ("Latency", "p95"),
                         ("IntegrationLatency", "p95")):
        add("AWS/ApiGateway", metric, {"ApiId": api_id, "Stage": "$default"}, stat, f"api|{api_id}|{metric}|{stat}")
    for t_key in ("UsersTableName", "DoctorsTableName", "SlotsTableName", "AppointmentsTableName"):
        table = out[t_key]
        for metric in ("ConsumedReadCapacityUnits", "ConsumedWriteCapacityUnits", "ThrottledRequests"):
            add("AWS/DynamoDB", metric, {"TableName": table}, "Sum", f"dynamodb|{table}|{metric}|Sum")
    for table, index in ((out["DoctorsTableName"], "search-index"), (out["AppointmentsTableName"], "patient-index"),
                         (out["AppointmentsTableName"], "doctor-index")):
        for metric in ("ConsumedReadCapacityUnits", "ConsumedWriteCapacityUnits"):
            add("AWS/DynamoDB", metric, {"TableName": table, "GlobalSecondaryIndexName": index}, "Sum",
                f"dynamodb|{table}/{index}|{metric}|Sum")
    for fn_key in ("UsersFunctionName", "DoctorsFunctionName", "AppointmentsFunctionName"):
        group = f"/aws/lambda/{out[fn_key]}"
        add("AWS/Logs", "IncomingBytes", {"LogGroupName": group}, "Sum", f"logs|{group}|IncomingBytes|Sum")
    return q


def pct(values: list[float], p: float) -> float | None:
    if not values:
        return None
    s = sorted(values)
    k = max(0, min(len(s) - 1, int(round(p / 100 * len(s) + 0.5)) - 1))
    return round(s[k], 2)


def write_csv(path: Path, rows: list[dict]) -> None:
    if not rows:
        path.write_text("", encoding="utf-8")
        return
    fields = sorted({k for r in rows for k in r})
    with path.open("w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=fields)
        w.writeheader()
        w.writerows(rows)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--stack", default="practo-lite")
    parser.add_argument("--start")
    parser.add_argument("--end")
    parser.add_argument("--last-minutes", type=int)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    now = dt.datetime.now(dt.timezone.utc)
    if args.last_minutes:
        start, end = now - dt.timedelta(minutes=args.last_minutes), now
    elif args.start and args.end:
        start, end = parse_time(args.start), parse_time(args.end)
    else:
        parser.error("give --start and --end, or --last-minutes")
    args.out.mkdir(parents=True, exist_ok=True)

    out = stack_outputs(args.stack)
    cw = boto3.client("cloudwatch", region_name=REGION)
    logs = boto3.client("logs", region_name=REGION)

    # 1. Metrics
    metric_rows = []
    queries = metric_queries(out)
    for i in range(0, len(queries), 400):
        token = None
        while True:
            kw = {"MetricDataQueries": queries[i:i + 400], "StartTime": start, "EndTime": end}
            if token:
                kw["NextToken"] = token
            res = cw.get_metric_data(**kw)
            for r in res["MetricDataResults"]:
                source, resource, metric, stat = r["Label"].split("|")
                for ts, v in zip(r["Timestamps"], r["Values"]):
                    metric_rows.append({"timestamp": ts.isoformat(), "source": source, "resource": resource,
                                        "metric": metric, "stat": stat, "value": v})
            token = res.get("NextToken")
            if not token:
                break
    metric_rows.sort(key=lambda r: (r["source"], r["resource"], r["metric"], r["timestamp"]))
    write_csv(args.out / "metrics.csv", metric_rows)

    # 2. Lambda REPORT lines
    fn_groups = [f"/aws/lambda/{out[k]}" for k in ("UsersFunctionName", "DoctorsFunctionName",
                                                    "AppointmentsFunctionName")]
    reports = []
    for row in insights(logs, fn_groups, 'fields @timestamp, @log, @requestId, @duration, @billedDuration, '
                        '@initDuration, @memorySize, @maxMemoryUsed, @message | filter @type = "REPORT"', start, end):
        msg = row.get("@message", "")
        r = RESTORE_RE.search(msg)
        br = BILLED_RESTORE_RE.search(msg)
        reports.append({"timestamp": row.get("@timestamp"), "function": row.get("@log", "").split("/")[-1],
                        "request_id": row.get("@requestId"), "duration_ms": row.get("@duration"),
                        "billed_ms": row.get("@billedDuration"), "init_ms": row.get("@initDuration", ""),
                        "restore_ms": r.group(1) if r else "", "billed_restore_ms": br.group(1) if br else "",
                        "memory_mb": int(float(row["@memorySize"]) / 1_000_000) if row.get("@memorySize") else "",
                        "max_memory_used_mb": int(float(row["@maxMemoryUsed"]) / 1_000_000)
                        if row.get("@maxMemoryUsed") else ""})
    write_csv(args.out / "report_lines.csv", reports)

    # 3. Our request_metrics JSON lines
    req = insights(logs, fn_groups, 'fields @timestamp, route, status, total_ms, dynamo_ms, dynamo_calls, cold, '
                   'fn_version, memory_mb | filter type = "request_metrics"', start, end)
    write_csv(args.out / "request_metrics.csv", req)

    # 4. API Gateway access logs
    access_group = f"/practo-lite/{args.stack}/api-access"
    acc = insights(logs, [access_group], 'fields @timestamp, routeKey, status, responseLatency, integrationLatency, '
                   'integrationStatus', start, end)
    write_csv(args.out / "api_access.csv", acc)

    # 5. Summary
    summary = {"stack": args.stack, "start": start.isoformat(), "end": end.isoformat(), "functions": {}, "routes": {}}
    for fn in sorted({r["function"] for r in reports}):
        rs = [r for r in reports if r["function"] == fn]
        d = [float(r["duration_ms"]) for r in rs if r["duration_ms"]]
        summary["functions"][fn] = {
            "invocations": len(rs), "cold_starts": sum(1 for r in rs if r["init_ms"] or r["restore_ms"]),
            "duration_p50": pct(d, 50), "duration_p95": pct(d, 95), "duration_p99": pct(d, 99),
            "billed_ms_total": round(sum(float(r["billed_ms"]) for r in rs if r["billed_ms"]), 1)}
    for route in sorted({r.get("route") for r in req if r.get("route")}):
        rr = [r for r in req if r.get("route") == route]
        tot = [float(r["total_ms"]) for r in rr if r.get("total_ms")]
        dyn = [float(r["dynamo_ms"]) for r in rr if r.get("dynamo_ms")]
        summary["routes"][route] = {"count": len(rr), "total_p50": pct(tot, 50), "total_p95": pct(tot, 95),
                                    "dynamo_p50": pct(dyn, 50), "dynamo_p95": pct(dyn, 95)}
    (args.out / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps(summary, indent=2))
    print(f"Exported {len(metric_rows)} metric points, {len(reports)} REPORT lines, {len(req)} request lines, "
          f"{len(acc)} access log lines to {args.out}")


if __name__ == "__main__":
    main()
