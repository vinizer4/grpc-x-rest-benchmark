#!/usr/bin/env python3
"""
Consolidates the 4-way benchmark results (HTTP/1.1 REST, HTTP/2 REST, gRPC,
HTTP/3 + MessagePack) into a single comparison table (CSV + Markdown), plus
bar charts if matplotlib is installed. Separate from benchmark/consolidate/
consolidate.py, which produces the original REST-vs-gRPC report and is left
untouched.

Usage: python3 consolidate-4way.py [results_dir]  (default: benchmark/results)
"""
import glob
import json
import os
import re
import sys

RESULT_FILENAME_RE = re.compile(r"^(rest|resth2|grpc|h3)-(medium|large)-([a-z-]+)\.json$")

LABELS = {
    "rest": "HTTP/1.1 REST (JSON)",
    "resth2": "HTTP/2 REST (JSON)",
    "grpc": "gRPC (HTTP/2, Protobuf)",
    "h3": "HTTP/3 (MessagePack)",
}


def parse_k6(path):
    with open(path) as f:
        data = json.load(f)
    metrics = data.get("metrics", {})
    dur = metrics.get("http_req_duration", {}).get("values", {})
    reqs = metrics.get("http_reqs", {}).get("values", {})
    checks = metrics.get("checks", {}).get("values", {})
    return {
        "p50_ms": dur.get("med"),
        "p95_ms": dur.get("p(95)"),
        "p99_ms": dur.get("p(99)"),
        "throughput_rps": reqs.get("rate"),
        "total_requests": reqs.get("count"),
        "success_rate": checks.get("rate"),
    }


def parse_ghz(path):
    with open(path) as f:
        data = json.load(f)
    latency_dist = {item["percentage"]: item["latency"] for item in data.get("latencyDistribution", [])}
    status = data.get("statusCodeDistribution", {})
    total = sum(status.values()) or data.get("count", 0)
    ok = status.get("OK", total)
    return {
        "p50_ms": latency_dist.get(50, 0) / 1_000_000,
        "p95_ms": latency_dist.get(95, 0) / 1_000_000,
        "p99_ms": latency_dist.get(99, 0) / 1_000_000,
        "throughput_rps": data.get("rps"),
        "total_requests": data.get("count"),
        "success_rate": (ok / total) if total else None,
    }


def parse_h3(path):
    with open(path) as f:
        data = json.load(f)
    latency = data.get("latency_ms", {})
    return {
        "p50_ms": latency.get("med"),
        "p95_ms": latency.get("p95"),
        "p99_ms": latency.get("p99"),
        "throughput_rps": data.get("throughput_rps"),
        "total_requests": data.get("total_requests"),
        "success_rate": data.get("success_rate"),
    }


def collect_latency_throughput(results_dir):
    rows = []
    for path in sorted(glob.glob(os.path.join(results_dir, "*.json"))):
        name = os.path.basename(path)
        match = RESULT_FILENAME_RE.match(name)
        if not match:
            continue
        protocol, scenario, profile = match.groups()
        if protocol == "h3":
            parsed = parse_h3(path)
        elif protocol == "grpc":
            parsed = parse_ghz(path)
        else:
            parsed = parse_k6(path)
        rows.append({"protocol": protocol, "label": LABELS[protocol], "scenario": scenario, "profile": profile, **parsed})
    return rows


def write_csv(rows, path):
    import csv
    fieldnames = ["protocol", "label", "scenario", "profile", "p50_ms", "p95_ms", "p99_ms", "throughput_rps", "total_requests", "success_rate"]
    with open(path, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def format_num(value, digits=1):
    if value is None:
        return "-"
    return f"{value:.{digits}f}"


def write_markdown(rows, payload_bytes, path):
    lines = ["# 4-way protocol benchmark summary", "", "HTTP/1.1 REST, HTTP/2 REST, gRPC, and HTTP/3 + MessagePack.", ""]

    lines.append("## Latency and throughput")
    lines.append("")
    lines.append("| Protocol | Scenario | Network profile | p50 (ms) | p95 (ms) | p99 (ms) | Throughput (req/s) | Success |")
    lines.append("|---|---|---|---|---|---|---|---|")
    for row in rows:
        lines.append(
            f"| {row['label']} | {row['scenario']} | {row['profile']} "
            f"| {format_num(row['p50_ms'])} | {format_num(row['p95_ms'])} | {format_num(row['p99_ms'])} "
            f"| {format_num(row['throughput_rps'], 2)} | {format_num((row['success_rate'] or 0) * 100)}% |"
        )

    if payload_bytes:
        lines.append("")
        lines.append("## Payload size (on-wire bytes, same query, same data)")
        lines.append("")
        lines.append("| Protocol | Bytes | Reduction vs JSON |")
        lines.append("|---|---|---|")
        json_bytes = payload_bytes.get("rest")
        for protocol, bytes_ in payload_bytes.items():
            reduction = f"{(1 - bytes_ / json_bytes) * 100:.1f}%" if json_bytes else "-"
            lines.append(f"| {LABELS[protocol]} | {bytes_:,} | {reduction} |")

    with open(path, "w") as f:
        f.write("\n".join(lines) + "\n")


def maybe_write_charts(rows, payload_bytes, charts_dir):
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        print("matplotlib not installed - skipping chart generation (tables/CSV were already written).")
        return

    os.makedirs(charts_dir, exist_ok=True)

    labels = [r["label"] for r in rows]
    p95_values = [r["p95_ms"] or 0 for r in rows]
    fig, ax = plt.subplots(figsize=(8, 4))
    ax.bar(labels, p95_values)
    ax.set_ylabel("p95 latency (ms)")
    ax.set_title("p95 latency by protocol")
    plt.xticks(rotation=20, ha="right")
    fig.tight_layout()
    fig.savefig(os.path.join(charts_dir, "latency-p95.png"))
    plt.close(fig)

    throughput_values = [r["throughput_rps"] or 0 for r in rows]
    fig, ax = plt.subplots(figsize=(8, 4))
    ax.bar(labels, throughput_values)
    ax.set_ylabel("throughput (req/s)")
    ax.set_title("Throughput by protocol")
    plt.xticks(rotation=20, ha="right")
    fig.tight_layout()
    fig.savefig(os.path.join(charts_dir, "throughput.png"))
    plt.close(fig)

    if payload_bytes:
        protocols = list(payload_bytes.keys())
        sizes = [payload_bytes[p] for p in protocols]
        fig, ax = plt.subplots(figsize=(6, 4))
        ax.bar([LABELS[p] for p in protocols], sizes)
        ax.set_ylabel("bytes")
        ax.set_title("Payload size by protocol")
        plt.xticks(rotation=20, ha="right")
        fig.tight_layout()
        fig.savefig(os.path.join(charts_dir, "payload-size.png"))
        plt.close(fig)

    print(f"Charts written to {charts_dir}")


def main():
    results_dir = sys.argv[1] if len(sys.argv) > 1 else "benchmark/results"

    rows = collect_latency_throughput(results_dir)
    if not rows:
        print(f"No 4-way results found in {results_dir}.")
        return

    payload_path = os.path.join(results_dir, "payload-sizes-4way.json")
    payload_bytes = {}
    if os.path.exists(payload_path):
        with open(payload_path) as f:
            payload_bytes = json.load(f)

    write_csv(rows, os.path.join(results_dir, "summary-4way.csv"))
    write_markdown(rows, payload_bytes, os.path.join(results_dir, "summary-4way.md"))
    maybe_write_charts(rows, payload_bytes, os.path.join(results_dir, "charts-4way"))

    print(f"Summary written to {os.path.join(results_dir, 'summary-4way.md')} and summary-4way.csv")


if __name__ == "__main__":
    main()
