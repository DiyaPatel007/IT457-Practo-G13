"""Turn raw experiment files in results/ into summary tables (CSV) and charts (PNG).

Every number in a chart comes from a file in results/; nothing is typed in by hand.

  python analysis/charts.py coldstart results/exp1-coldstart
  python analysis/charts.py latency   results/exp2-latency
  python analysis/charts.py breakdown results/exp2-latency/vus-10/run1      (needs cloudwatch_export output)
  python analysis/charts.py scaling   results/exp3-scaling/ramp/run1        (k6.csv.gz + metrics.csv)
  python analysis/charts.py race      results/exp4-race
  python analysis/charts.py cost      results                               (every cost.json below it)

Outputs go next to the inputs, in a "summary" folder.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import pandas as pd  # noqa: E402

WARMUP_SECONDS = 30
COLORS = ["#2a6fdb", "#e07a1f", "#2f9e44", "#c92a2a", "#7048e8", "#868e96"]


def out_dir(base: Path) -> Path:
    d = base / "summary"
    d.mkdir(parents=True, exist_ok=True)
    return d


def save(fig, path: Path) -> None:
    fig.tight_layout()
    fig.savefig(path, dpi=160)
    plt.close(fig)
    print(f"wrote {path}")


def read_k6(path: Path) -> pd.DataFrame:
    """k6 --out csv rows for http_req_duration, without login calls."""
    df = pd.read_csv(path, low_memory=False)
    df = df[(df["metric_name"] == "http_req_duration") & (df["name"] != "cognito-login")].copy()
    df["timestamp"] = pd.to_numeric(df["timestamp"])
    df["metric_value"] = pd.to_numeric(df["metric_value"])
    df["status"] = pd.to_numeric(df["status"], errors="coerce")
    return df


# --------------------------------------------------------------------------- Experiment 1

# --------------------------------------------------------------------------- Experiment 2
def latency(base: Path) -> None:
    rows = []
    for path in sorted(base.glob("vus-*/run*/k6.csv*")):
        vus = int(path.parent.parent.name.split("-")[1])
        run = path.parent.name
        df = read_k6(path)
        df = df[df["timestamp"] >= df["timestamp"].min() + WARMUP_SECONDS]
        seconds = max(1, df["timestamp"].max() - df["timestamp"].min())
        for route, g in df.groupby("name"):
            rows.append({"vus": vus, "run": run, "route": route, "requests": len(g),
                         "throughput_rps": len(g) / seconds,
                         "p50": g["metric_value"].quantile(.5), "p95": g["metric_value"].quantile(.95),
                         "p99": g["metric_value"].quantile(.99),
                         "error_pct": 100 * (~g["status"].isin([200, 201, 409])).mean()})
    if not rows:
        sys.exit(f"no vus-*/run*/k6.csv(.gz) under {base}")
    per_run = pd.DataFrame(rows)
    o = out_dir(base)
    per_run.to_csv(o / "latency_per_run.csv", index=False)
    agg = per_run.groupby(["route", "vus"]).agg(
        runs=("run", "nunique"), p50_mean=("p50", "mean"), p95_mean=("p95", "mean"), p95_min=("p95", "min"),
        p95_max=("p95", "max"), p99_mean=("p99", "mean"), throughput_rps=("throughput_rps", "mean"),
        error_pct=("error_pct", "mean")).reset_index()
    agg.to_csv(o / "latency_summary.csv", index=False)
    print(agg.round(1).to_string(index=False))

    fig, ax = plt.subplots(figsize=(9, 5))
    for i, (route, g) in enumerate(agg.groupby("route")):
        g = g.sort_values("vus")
        ax.errorbar(g["vus"], g["p95_mean"], yerr=[g["p95_mean"] - g["p95_min"], g["p95_max"] - g["p95_mean"]],
                    marker="o", capsize=3, label=route, color=COLORS[i % len(COLORS)])
    ax.set_xlabel("concurrent virtual users")
    ax.set_ylabel("p95 latency (ms), mean of runs, bars = min-max")
    ax.set_title("Experiment 2: p95 latency vs concurrency")
    ax.legend()
    save(fig, o / "latency_p95_vs_vus.png")


def breakdown(run: Path) -> None:
    """Stacked bar per route: API Gateway overhead, Java code time, DynamoDB time (medians)."""
    req = pd.read_csv(run / "request_metrics.csv")
    acc = pd.read_csv(run / "api_access.csv")
    for c in ("total_ms", "dynamo_ms"):
        req[c] = pd.to_numeric(req[c], errors="coerce")
    for c in ("responseLatency", "integrationLatency"):
        acc[c] = pd.to_numeric(acc[c], errors="coerce")
    acc["gateway_ms"] = acc["responseLatency"] - acc["integrationLatency"]
    acc["route"] = acc["routeKey"].str.replace(r"\{[^}]+\}", "{id}", regex=True)
    req["route"] = req["route"].str.replace(r"\{[^}]+\}", "{id}", regex=True)
    rows = []
    for route, g in req.groupby("route"):
        if route.startswith("GET /internal") or "UNMATCHED" in route:
            continue
        a = acc[acc["route"] == route]
        rows.append({"route": route, "gateway_ms": a["gateway_ms"].median() if len(a) else 0,
                     "code_ms": (g["total_ms"] - g["dynamo_ms"]).median(), "dynamodb_ms": g["dynamo_ms"].median()})
    df = pd.DataFrame(rows).set_index("route")
    o = out_dir(run)
    df.to_csv(o / "latency_breakdown.csv")
    print(df.round(2).to_string())
    fig, ax = plt.subplots(figsize=(9, 5))
    df.plot(kind="barh", stacked=True, ax=ax, color=COLORS[:3])
    ax.set_xlabel("median time per request (ms)")
    ax.set_title("Where the time goes: API Gateway, Java code, DynamoDB")
    save(fig, o / "latency_breakdown.png")


# --------------------------------------------------------------------------- Experiment 3
def scaling(run: Path) -> None:
    k6 = read_k6(next(run.glob("k6.csv*")))
    k6["second"] = k6["timestamp"].astype(int)
    t0 = k6["second"].min()
    per_sec = k6.groupby("second").agg(rate=("metric_value", "size"),
                                       errors=("status", lambda s: (s != 200).sum()))
    per_10 = k6.assign(bucket=(k6["second"] - t0) // 10).groupby("bucket")["metric_value"].quantile(.95)
    metrics = pd.read_csv(run / "metrics.csv", parse_dates=["timestamp"])
    lam = metrics[(metrics["source"] == "lambda")]
    conc = lam[lam["metric"] == "ConcurrentExecutions"].groupby("timestamp")["value"].sum()
    thr = lam[lam["metric"] == "Throttles"].groupby("timestamp")["value"].sum()
    start = pd.Timestamp(t0, unit="s", tz="UTC")

    fig, axes = plt.subplots(4, 1, figsize=(10, 10), sharex=True)
    axes[0].plot(per_sec.index - t0, per_sec["rate"], color=COLORS[0])
    axes[0].set_ylabel("requests/s sent")
    axes[1].plot(per_10.index * 10, per_10.values, color=COLORS[1])
    axes[1].set_ylabel("p95 latency (ms)\n10 s windows")
    axes[2].step((conc.index - start).total_seconds(), conc.values, where="post", color=COLORS[2])
    axes[2].set_ylabel("Lambda concurrent\nexecutions (max/min)")
    axes[3].plot(per_sec.index - t0, per_sec["errors"], color=COLORS[3], label="non-200 responses/s (k6)")
    axes[3].step((thr.index - start).total_seconds(), thr.values / 60, where="post", color=COLORS[4],
                 label="Lambda throttles/s (CloudWatch)")
    axes[3].set_ylabel("errors")
    axes[3].legend()
    axes[3].set_xlabel("seconds since test start")
    fig.suptitle(f"Experiment 3: scaling timeline ({'/'.join(run.parts[-2:])})")
    o = out_dir(run)
    save(fig, o / "scaling_timeline.png")

    steps = k6.assign(step=(k6["second"] - t0) // 130).groupby("step").agg(
        rate=("second", lambda s: len(s) / max(1, s.max() - s.min())),
        p95=("metric_value", lambda v: v.quantile(.95)),
        error_pct=("status", lambda s: 100 * (s != 200).mean()))
    steps.to_csv(o / "scaling_steps.csv")
    print(steps.round(1).to_string())


# --------------------------------------------------------------------------- Experiment 4

# --------------------------------------------------------------------------- Experiment 5


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("chart", choices=["latency", "breakdown", "scaling"])
    parser.add_argument("path", type=Path)
    args = parser.parse_args()
    globals()[args.chart](args.path)


if __name__ == "__main__":
    main()
