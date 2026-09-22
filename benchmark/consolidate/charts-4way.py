#!/usr/bin/env python3
"""
Generates the polished chart set for report/RESULTS_4WAY.md (mirroring the
chart set already embedded in report/RESULTS.md), from the raw 4-way
benchmark results. Separate from consolidate-4way.py (which writes the
plain summary table/CSV) and from report/images/*.png (the original
REST-vs-gRPC charts, untouched).

Usage: python3 charts-4way.py [results_dir] [out_dir]
  results_dir default: benchmark/results
  out_dir default: report/images
"""
import glob
import json
import os
import re
import sys

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

RESULT_FILENAME_RE = re.compile(r"^(rest|resth2|grpc|h3)-(medium|large)-([a-z-]+)\.json$")
PROTOCOLS = ["rest", "resth2", "grpc", "h3"]
LABELS = {
    "rest": "HTTP/1.1 REST",
    "resth2": "HTTP/2 REST",
    "grpc": "gRPC",
    "h3": "HTTP/3+MsgPack",
}
COLORS = {
    "rest": "#4C72B0",
    "resth2": "#DD8452",
    "grpc": "#55A868",
    "h3": "#C44E52",
}
PROFILES = ["baseline", "same-az", "cross-az", "cross-region"]


def parse_k6(path):
    with open(path) as f:
        data = json.load(f)
    metrics = data.get("metrics", {})
    dur = metrics.get("http_req_duration", {}).get("values", {})
    reqs = metrics.get("http_reqs", {}).get("values", {})
    return {"p99_ms": dur.get("p(99)"), "throughput_rps": reqs.get("rate")}


def parse_ghz(path):
    with open(path) as f:
        data = json.load(f)
    latency_dist = {item["percentage"]: item["latency"] for item in data.get("latencyDistribution", [])}
    return {"p99_ms": latency_dist.get(99, 0) / 1_000_000, "throughput_rps": data.get("rps")}


def parse_h3(path):
    with open(path) as f:
        data = json.load(f)
    latency = data.get("latency_ms", {})
    return {"p99_ms": latency.get("p99"), "throughput_rps": data.get("throughput_rps")}


def collect(results_dir):
    rows = {}
    for path in glob.glob(os.path.join(results_dir, "*.json")):
        name = os.path.basename(path)
        match = RESULT_FILENAME_RE.match(name)
        if not match:
            continue
        protocol, scenario, profile = match.groups()
        parsed = parse_h3(path) if protocol == "h3" else (parse_ghz(path) if protocol == "grpc" else parse_k6(path))
        rows[(protocol, scenario, profile)] = parsed
    return rows


def grouped_bar_chart(rows, scenario, out_path):
    fig, axes = plt.subplots(1, 2, figsize=(12, 4.5))

    profiles_present = [p for p in PROFILES if any((proto, scenario, p) in rows for proto in PROTOCOLS)]
    x = range(len(profiles_present))
    width = 0.2

    ax = axes[0]
    for i, proto in enumerate(PROTOCOLS):
        values = [rows.get((proto, scenario, p), {}).get("p99_ms") or 0 for p in profiles_present]
        ax.bar([xi + i * width for xi in x], values, width, label=LABELS[proto], color=COLORS[proto])
    ax.set_xticks([xi + 1.5 * width for xi in x])
    ax.set_xticklabels(profiles_present)
    ax.set_ylabel("p99 latency (ms)")
    ax.set_title(f"p99 latency — {scenario} payload")
    ax.legend(fontsize=8)

    ax = axes[1]
    for i, proto in enumerate(PROTOCOLS):
        values = [rows.get((proto, scenario, p), {}).get("throughput_rps") or 0 for p in profiles_present]
        ax.bar([xi + i * width for xi in x], values, width, label=LABELS[proto], color=COLORS[proto])
    ax.set_xticks([xi + 1.5 * width for xi in x])
    ax.set_xticklabels(profiles_present)
    ax.set_ylabel("throughput (req/s)")
    ax.set_title(f"Throughput — {scenario} payload")
    ax.legend(fontsize=8)

    fig.tight_layout()
    fig.savefig(out_path, dpi=130)
    plt.close(fig)
    print(f"wrote {out_path}")


def payload_size_chart(payload_bytes, out_path):
    protocols = [p for p in PROTOCOLS if p in payload_bytes]
    sizes_mb = [payload_bytes[p] / 1_000_000 for p in protocols]
    fig, ax = plt.subplots(figsize=(6, 4))
    ax.bar([LABELS[p] for p in protocols], sizes_mb, color=[COLORS[p] for p in protocols])
    ax.set_ylabel("MB per response (12-month scenario)")
    ax.set_title("Payload size by protocol")
    for i, v in enumerate(sizes_mb):
        ax.text(i, v + 0.2, f"{v:.1f} MB", ha="center", fontsize=9)
    fig.tight_layout()
    fig.savefig(out_path, dpi=130)
    plt.close(fig)
    print(f"wrote {out_path}")


def cost_projection_chart(payload_bytes, out_path, stores=4000, cost_per_gb=0.09):
    protocols = [p for p in PROTOCOLS if p in payload_bytes]
    monthly_gb = [payload_bytes[p] * stores * 30 / 1e9 for p in protocols]
    monthly_cost = [gb * cost_per_gb for gb in monthly_gb]
    fig, ax = plt.subplots(figsize=(6, 4))
    ax.bar([LABELS[p] for p in protocols], monthly_cost, color=[COLORS[p] for p in protocols])
    ax.set_ylabel("Estimated monthly egress cost (US$)")
    ax.set_title(f"Cost projection — {stores} stores/day")
    for i, v in enumerate(monthly_cost):
        ax.text(i, v + max(monthly_cost) * 0.01, f"${v:,.0f}", ha="center", fontsize=9)
    fig.tight_layout()
    fig.savefig(out_path, dpi=130)
    plt.close(fig)
    print(f"wrote {out_path}")


def scale_charts(rows, pods_dir, cost_dir, base_pods=5, pod_monthly_cost=32.80):
    # Throughput-based pod requirement relative to HTTP/1.1 REST, using the
    # medium/cross-region profile (not baseline): baseline throughput in this
    # run is skewed by host contention (8 services sharing one Docker Desktop
    # VM), which narrows or reverses gRPC's advantage in a way that doesn't
    # hold under real network latency - see the report's methodology note.
    # Rounding happens only on the final per-N-endpoints total, not per
    # reference-pod-count step, so small throughput differences (e.g.
    # REST vs gRPC) aren't swallowed by rounding at N=1 and only show up
    # at higher N - that would make two visibly different protocols render
    # as identical bars.
    profile = "cross-region"
    baseline_rps = rows.get(("rest", "medium", profile), {}).get("throughput_rps") or 1
    endpoints = [1, 5, 10, 15, 20]

    protocols = PROTOCOLS
    pods_by_protocol = {}
    cost_by_protocol = {}
    for proto in protocols:
        rps = rows.get((proto, "medium", profile), {}).get("throughput_rps") or baseline_rps
        ratio = baseline_rps / rps if rps else 1
        pods_by_protocol[proto] = [max(1, round(base_pods * ratio * n)) for n in endpoints]
        cost_by_protocol[proto] = [p * pod_monthly_cost * 12 for p in pods_by_protocol[proto]]

    fig, ax = plt.subplots(figsize=(7, 4.5))
    width = 0.2
    x = range(len(endpoints))
    for i, proto in enumerate(protocols):
        ax.bar([xi + i * width for xi in x], pods_by_protocol[proto], width, label=LABELS[proto], color=COLORS[proto])
    ax.set_xticks([xi + 1.5 * width for xi in x])
    ax.set_xticklabels([str(e) for e in endpoints])
    ax.set_xlabel("Endpoints migrated")
    ax.set_ylabel("Pods required")
    ax.set_title("Infrastructure needed at scale")
    ax.legend(fontsize=8)
    fig.tight_layout()
    fig.savefig(pods_dir, dpi=130)
    plt.close(fig)
    print(f"wrote {pods_dir}")

    fig, ax = plt.subplots(figsize=(7, 4.5))
    for i, proto in enumerate(protocols):
        ax.bar([xi + i * width for xi in x], cost_by_protocol[proto], width, label=LABELS[proto], color=COLORS[proto])
    ax.set_xticks([xi + 1.5 * width for xi in x])
    ax.set_xticklabels([str(e) for e in endpoints])
    ax.set_xlabel("Endpoints migrated")
    ax.set_ylabel("Estimated compute cost/year (US$)")
    ax.set_title("Projected cost at scale")
    ax.legend(fontsize=8)
    fig.tight_layout()
    fig.savefig(cost_dir, dpi=130)
    plt.close(fig)
    print(f"wrote {cost_dir}")


def final_summary_chart(rows, payload_bytes, out_path):
    # Both p99 and throughput use the same medium/cross-region profile as
    # the report's "quick summary" table - mixing a baseline throughput
    # panel with a cross-region p99 panel would show REST ahead of gRPC on
    # throughput while the surrounding text/table (cross-region) show the
    # opposite, which is confusing even though both numbers are individually
    # correct for their own profile.
    metrics = ["p99 latency\n(cross-region, ms)", "Throughput\n(cross-region, req/s)", "Payload\n(MB)"]
    fig, axes = plt.subplots(1, 3, figsize=(13, 4.5))

    ax = axes[0]
    values = [rows.get((p, "medium", "cross-region"), {}).get("p99_ms") or 0 for p in PROTOCOLS]
    ax.bar([LABELS[p] for p in PROTOCOLS], values, color=[COLORS[p] for p in PROTOCOLS])
    ax.set_title(metrics[0])
    plt.setp(ax.get_xticklabels(), rotation=20, ha="right")

    ax = axes[1]
    values = [rows.get((p, "medium", "cross-region"), {}).get("throughput_rps") or 0 for p in PROTOCOLS]
    ax.bar([LABELS[p] for p in PROTOCOLS], values, color=[COLORS[p] for p in PROTOCOLS])
    ax.set_title(metrics[1])
    plt.setp(ax.get_xticklabels(), rotation=20, ha="right")

    ax = axes[2]
    values = [payload_bytes.get(p, 0) / 1_000_000 for p in PROTOCOLS]
    ax.bar([LABELS[p] for p in PROTOCOLS], values, color=[COLORS[p] for p in PROTOCOLS])
    ax.set_title(metrics[2])
    plt.setp(ax.get_xticklabels(), rotation=20, ha="right")

    fig.suptitle("4-way protocol comparison — summary")
    fig.tight_layout()
    fig.savefig(out_path, dpi=130)
    plt.close(fig)
    print(f"wrote {out_path}")


def main():
    results_dir = sys.argv[1] if len(sys.argv) > 1 else "benchmark/results"
    out_dir = sys.argv[2] if len(sys.argv) > 2 else "report/images"
    os.makedirs(out_dir, exist_ok=True)

    rows = collect(results_dir)

    payload_path = os.path.join(results_dir, "payload-sizes-4way.json")
    payload_bytes = {}
    if os.path.exists(payload_path):
        with open(payload_path) as f:
            payload_bytes = json.load(f)

    grouped_bar_chart(rows, "medium", os.path.join(out_dir, "comparison-medium-4way.png"))
    grouped_bar_chart(rows, "large", os.path.join(out_dir, "comparison-large-4way.png"))
    payload_size_chart(payload_bytes, os.path.join(out_dir, "payload-size-4way.png"))
    cost_projection_chart(payload_bytes, os.path.join(out_dir, "cost-projection-4000-stores-4way.png"))
    scale_charts(
        rows,
        os.path.join(out_dir, "scale-pods-4way.png"),
        os.path.join(out_dir, "scale-cost-4way.png"),
    )
    final_summary_chart(rows, payload_bytes, os.path.join(out_dir, "final-summary-4way.png"))


if __name__ == "__main__":
    main()
