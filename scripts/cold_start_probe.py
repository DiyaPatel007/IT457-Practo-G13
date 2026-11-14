"""Experiment 1: cold start vs warm execution ("first request vs 100th consecutive request").

For each cycle:
  1. change the COLD_MARKER environment variable and publish a NEW function version
     (a new version can only run in new execution environments, so its first request is a cold start;
     with SnapStart, publishing also creates the snapshot);
  2. point the "live" alias (which API Gateway calls) at that version;
  3. send N sequential requests (default 100, 200 ms apart) to GET /doctors/{id} and record the
     client-side latency plus the X-Cold-Start / X-Lambda-Request-Id response headers.
Afterwards it pulls the Lambda REPORT lines from CloudWatch Logs Insights and joins them by request id,
adding Init Duration (classic cold start), Restore Duration (SnapStart) and Billed Duration.

A request is classified as cold from the REPORT line (Init or Restore Duration present), never from its position.

Usage (run in AWS CloudShell, ap-south-1, so home-network delay is not measured):
  python scripts/cold_start_probe.py --stack practo-lite --function doctors --cycles 20 --label snapstart-off
Outputs: results/exp1-coldstart/<label>/probe.csv  and  notes.json
NOTE: the alias is moved outside CloudFormation. Run "sam deploy" afterwards to return to the template state.
"""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import json
import os
import re
import time
import uuid
from pathlib import Path

import boto3

from practo_common import REGION, ROOT, Api, read_seed, stack_outputs

RESTORE_RE = re.compile(r"Restore Duration: ([\d.]+) ms")
BILLED_RESTORE_RE = re.compile(r"Billed Restore Duration: ([\d.]+) ms")


def new_version(lam, function: str) -> str:
    cfg = lam.get_function_configuration(FunctionName=function)
    env = cfg.get("Environment", {}).get("Variables", {})
    env["COLD_MARKER"] = uuid.uuid4().hex
    lam.update_function_configuration(FunctionName=function, Environment={"Variables": env})
    lam.get_waiter("function_updated_v2").wait(FunctionName=function)
    version = lam.publish_version(FunctionName=function)["Version"]
    lam.get_waiter("published_version_active").wait(FunctionName=function, Qualifier=version,
                                                   WaiterConfig={"Delay": 5, "MaxAttempts": 120})
    lam.update_alias(FunctionName=function, Name="live", FunctionVersion=version)
    return version


def report_lines(logs, log_group: str, start: float, end: float) -> dict[str, dict]:
    query = ('fields @requestId, @duration, @billedDuration, @initDuration, @maxMemoryUsed, @memorySize, @message '
             '| filter @type = "REPORT" | limit 10000')
    qid = logs.start_query(logGroupName=log_group, startTime=int(start) - 60, endTime=int(end) + 60,
                           queryString=query)["queryId"]
    while True:
        res = logs.get_query_results(queryId=qid)
        if res["status"] in ("Complete", "Failed", "Cancelled", "Timeout"):
            break
        time.sleep(2)
    out = {}
    for row in res.get("results", []):
        f = {c["field"]: c["value"] for c in row}
        msg = f.get("@message", "")
        restore = RESTORE_RE.search(msg)
        billed_restore = BILLED_RESTORE_RE.search(msg)
        out[f["@requestId"]] = {
            "duration_ms": f.get("@duration"),
            "billed_ms": f.get("@billedDuration"),
            "init_ms": f.get("@initDuration", ""),
            "restore_ms": restore.group(1) if restore else "",
            "billed_restore_ms": billed_restore.group(1) if billed_restore else "",
            "memory_mb": int(float(f.get("@memorySize", 0)) / 1_000_000) if f.get("@memorySize") else "",
            "max_memory_used_mb": int(float(f.get("@maxMemoryUsed", 0)) / 1_000_000) if f.get("@maxMemoryUsed") else "",
        }
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--stack", default="practo-lite")
    parser.add_argument("--function", choices=["users", "doctors", "appointments"], default="doctors")
    parser.add_argument("--cycles", type=int, default=20)
    parser.add_argument("--requests", type=int, default=100)
    parser.add_argument("--gap-ms", type=int, default=200)
    parser.add_argument("--label", required=True, help="e.g. snapstart-off, snapstart-on, snapstart-primed")
    parser.add_argument("--logs-wait", type=int, default=90, help="seconds to wait for logs before querying")
    args = parser.parse_args()

    out = stack_outputs(args.stack)
    seed = read_seed()
    function = out[f"{args.function.capitalize()}FunctionName"]
    api = Api(out["ApiUrl"])
    doctor_id = seed["doctors"][0]["doctorId"]
    path = f"/doctors/{doctor_id}"   # public, read-only: consumes no slots

    lam = boto3.client("lambda", region_name=REGION)
    logs = boto3.client("logs", region_name=REGION)
    cfg = lam.get_function_configuration(FunctionName=function)
    folder = ROOT / "results" / "exp1-coldstart" / args.label
    folder.mkdir(parents=True, exist_ok=True)

    rows, versions = [], []
    started = time.time()
    for cycle in range(1, args.cycles + 1):
        version = new_version(lam, function)
        versions.append(version)
        time.sleep(5)  # let the alias update propagate
        for i in range(1, args.requests + 1):
            t0 = time.perf_counter()
            status, _, headers = api.call("GET", path)
            elapsed = (time.perf_counter() - t0) * 1000
            rows.append({"label": args.label, "cycle": cycle, "version": version, "request_index": i,
                         "status": status, "client_ms": round(elapsed, 2),
                         "x_cold_start": headers.get("X-Cold-Start", headers.get("x-cold-start", "")),
                         "lambda_request_id": headers.get("X-Lambda-Request-Id",
                                                          headers.get("x-lambda-request-id", "")),
                         "timestamp": dt.datetime.now(dt.timezone.utc).isoformat()})
            time.sleep(args.gap_ms / 1000)
        first = rows[-args.requests]
        print(f"cycle {cycle}/{args.cycles} v{version}: first {first['client_ms']} ms, "
              f"last {rows[-1]['client_ms']} ms, cold header {first['x_cold_start']}")

    print(f"Waiting {args.logs_wait}s for CloudWatch Logs ingestion ...")
    time.sleep(args.logs_wait)
    reports = report_lines(logs, f"/aws/lambda/{function}", started, time.time())
    missing = 0
    for r in rows:
        rep = reports.get(r["lambda_request_id"], {})
        missing += 0 if rep else 1
        r.update({k: rep.get(k, "") for k in ("duration_ms", "billed_ms", "init_ms", "restore_ms",
                                               "billed_restore_ms", "memory_mb", "max_memory_used_mb")})
        r["cold_by_report"] = bool(rep.get("init_ms") or rep.get("restore_ms")) if rep else ""

    with (folder / "probe.csv").open("w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)
    notes = {"label": args.label, "function": function, "cycles": args.cycles, "requests": args.requests,
             "gap_ms": args.gap_ms, "path": "GET /doctors/{id}", "memory_mb": cfg["MemorySize"],
             "architecture": cfg.get("Architectures", ["?"])[0],
             "snapstart": cfg.get("SnapStart", {}).get("ApplyOn", "None"),
             "priming": cfg.get("Environment", {}).get("Variables", {}).get("PRIMING_ENABLED"),
             "started_utc": dt.datetime.fromtimestamp(started, dt.timezone.utc).isoformat(),
             "client": os.environ.get("CLIENT_LOCATION", "unknown (set CLIENT_LOCATION, e.g. cloudshell-ap-south-1)"),
             "report_lines_missing": missing, "versions_published": versions}
    (folder / "notes.json").write_text(json.dumps(notes, indent=2), encoding="utf-8")
    print(f"Saved {len(rows)} rows to {folder / 'probe.csv'} ({missing} without a REPORT line)")
    print("Run 'sam deploy' afterwards to restore the alias to the CloudFormation-managed version.")


if __name__ == "__main__":
    main()
