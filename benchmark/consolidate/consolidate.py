#!/usr/bin/env python3
"""
Consolidates benchmark/results/*.json (k6 for REST, ghz for gRPC) and
payload-sizes.csv into a single comparison table (CSV + Markdown), and,
if matplotlib is installed, a couple of bar charts. Uses only the
standard library otherwise, so the tables/CSV always work out of the box;
charts are a bonus if `pip install matplotlib` has been run.

Usage: python3 consolidate.py [results_dir]  (default: benchmark/results)
"""
import csv
import glob
import json
import os
import re
import sys

RESULT_FILENAME_RE = re.compile(r"^(rest|grpc)-(medium|large)-([a-z-]+)\.json$")


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


def collect_latency_throughput(results_dir):
    rows = []
    for path in sorted(glob.glob(os.path.join(results_dir, "*.json"))):
        name = os.path.basename(path)
        match = RESULT_FILENAME_RE.match(name)
        if not match:
            continue
        protocol, scenario, profile = match.groups()
        parsed = parse_k6(path) if protocol == "rest" else parse_ghz(path)
        rows.append({"protocol": protocol.upper(), "scenario": scenario, "profile": profile, **parsed})
    return rows


def collect_payload_sizes(results_dir):
    path = os.path.join(results_dir, "payload-sizes.csv")
    if not os.path.exists(path):
        return []
    with open(path) as f:
        return list(csv.DictReader(f))


def write_csv(rows, path, fieldnames):
    with open(path, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def format_num(value, digits=1):
    if value is None:
        return "-"
    return f"{value:.{digits}f}"


def write_markdown(latency_rows, payload_rows, path):
    lines = ["# Resumo do benchmark REST vs gRPC", ""]

    lines.append("## Latencia e throughput")
    lines.append("")
    lines.append("| Protocolo | Cenario | Perfil de rede | p50 (ms) | p95 (ms) | p99 (ms) | Throughput (req/s) | Sucesso |")
    lines.append("|---|---|---|---|---|---|---|---|")
    for row in latency_rows:
        lines.append(
            f"| {row['protocol']} | {row['scenario']} | {row['profile']} "
            f"| {format_num(row['p50_ms'])} | {format_num(row['p95_ms'])} | {format_num(row['p99_ms'])} "
            f"| {format_num(row['throughput_rps'])} | {format_num((row['success_rate'] or 0) * 100)}% |"
        )

    lines.append("")
    lines.append("## Tamanho de payload (bytes on-wire, JSON vs Protobuf)")
    lines.append("")
    lines.append("| Cenario | Produto | JSON (bytes) | Protobuf (bytes) | Reducao |")
    lines.append("|---|---|---|---|---|")
    for row in payload_rows:
        lines.append(
            f"| {row['scenario']} | {row['produto_id']} | {row['json_bytes']} "
            f"| {row['proto_bytes']} | {row['reducao_pct']}% |"
        )

    with open(path, "w") as f:
        f.write("\n".join(lines) + "\n")


def maybe_write_charts(latency_rows, payload_rows, charts_dir):
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        print("matplotlib nao instalado - pulando geracao de graficos (tabelas/CSV ja foram gerados).")
        print("Para gerar graficos: pip install matplotlib")
        return

    os.makedirs(charts_dir, exist_ok=True)

    by_key = {}
    for row in latency_rows:
        key = (row["scenario"], row["profile"])
        by_key.setdefault(key, {})[row["protocol"]] = row

    for (scenario, profile), by_protocol in by_key.items():
        protocols = list(by_protocol.keys())
        p95_values = [by_protocol[p]["p95_ms"] or 0 for p in protocols]
        fig, ax = plt.subplots()
        ax.bar(protocols, p95_values)
        ax.set_ylabel("p95 latencia (ms)")
        ax.set_title(f"p95 latencia - {scenario} / {profile}")
        fig.savefig(os.path.join(charts_dir, f"latency-p95-{scenario}-{profile}.png"))
        plt.close(fig)

    if payload_rows:
        scenarios = [row["scenario"] for row in payload_rows]
        json_bytes = [int(row["json_bytes"]) for row in payload_rows]
        proto_bytes = [int(row["proto_bytes"]) for row in payload_rows]
        x = range(len(scenarios))
        fig, ax = plt.subplots()
        width = 0.35
        ax.bar([i - width / 2 for i in x], json_bytes, width, label="JSON")
        ax.bar([i + width / 2 for i in x], proto_bytes, width, label="Protobuf")
        ax.set_xticks(list(x))
        ax.set_xticklabels(scenarios)
        ax.set_ylabel("bytes")
        ax.set_title("Tamanho de payload: JSON vs Protobuf")
        ax.legend()
        fig.savefig(os.path.join(charts_dir, "payload-size.png"))
        plt.close(fig)

    print(f"Graficos escritos em {charts_dir}")


def main():
    results_dir = sys.argv[1] if len(sys.argv) > 1 else "benchmark/results"

    latency_rows = collect_latency_throughput(results_dir)
    payload_rows = collect_payload_sizes(results_dir)

    if not latency_rows and not payload_rows:
        print(f"Nenhum resultado encontrado em {results_dir}. Rode os benchmarks (k6/ghz/measure-payload-size.sh) primeiro.")
        return

    write_csv(
        latency_rows,
        os.path.join(results_dir, "summary-latency.csv"),
        fieldnames=["protocol", "scenario", "profile", "p50_ms", "p95_ms", "p99_ms", "throughput_rps", "total_requests", "success_rate"],
    )
    write_markdown(latency_rows, payload_rows, os.path.join(results_dir, "summary.md"))
    maybe_write_charts(latency_rows, payload_rows, os.path.join(results_dir, "charts"))

    print(f"Resumo escrito em {os.path.join(results_dir, 'summary.md')} e summary-latency.csv")


if __name__ == "__main__":
    main()
