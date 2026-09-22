"""
HTTP/3 load-test tool for the MessagePack service, via edge-h3-sim.

No mature k6/ghz-equivalent tool has real HTTP/3 (QUIC) support, so this
script fills that gap using aioquic directly. It must run as a container
attached to the compose network (`edge-h3-sim` resolved by service name),
not from the Docker host: Docker Desktop for macOS does not reliably
forward the UDP packets a QUIC handshake needs across the host/VM boundary,
even though the same handshake succeeds instantly container-to-container.
See benchmark/h3/README.md.

Each concurrent "worker" holds one persistent QUIC connection (matching how
a real HTTP/3 client reuses a connection across requests) and issues GET
requests against random stores back-to-back until the configured duration
elapses. Output is a JSON summary, written in the same spirit as the k6/ghz
result files (percentile latency, throughput, success rate) so it can be
folded into a wider comparison later.
"""
import argparse
import asyncio
import json
import os
import random
import ssl
import statistics
import time
from collections import deque
from urllib.parse import urlparse

from aioquic.asyncio.client import connect
from aioquic.asyncio.protocol import QuicConnectionProtocol
from aioquic.h3.connection import H3_ALPN, H3Connection
from aioquic.h3.events import DataReceived, H3Event, HeadersReceived
from aioquic.quic.configuration import QuicConfiguration


class H3Client(QuicConnectionProtocol):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._http = H3Connection(self._quic)
        self._events: dict[int, deque] = {}
        self._waiters: dict[int, asyncio.Future] = {}

    def quic_event_received(self, event):
        for http_event in self._http.handle_event(event):
            self.http_event_received(http_event)

    def http_event_received(self, event: H3Event):
        if isinstance(event, (HeadersReceived, DataReceived)):
            stream_id = event.stream_id
            if stream_id in self._events:
                self._events[stream_id].append(event)
                if event.stream_ended:
                    waiter = self._waiters.pop(stream_id)
                    waiter.set_result(self._events.pop(stream_id))

    async def get(self, authority: str, path: str) -> deque:
        stream_id = self._quic.get_next_available_stream_id()
        self._http.send_headers(
            stream_id=stream_id,
            headers=[
                (b":method", b"GET"),
                (b":scheme", b"https"),
                (b":authority", authority.encode()),
                (b":path", path.encode()),
                (b"user-agent", b"benchmark-h3-load-test"),
            ],
            end_stream=True,
        )
        waiter = self._loop.create_future()
        self._events[stream_id] = deque()
        self._waiters[stream_id] = waiter
        self.transmit()
        return await asyncio.shield(waiter)


def status_of(events: deque) -> int:
    for event in events:
        if isinstance(event, HeadersReceived):
            for name, value in event.headers:
                if name == b":status":
                    return int(value.decode())
    return 0


def bytes_of(events: deque) -> int:
    return sum(len(e.data) for e in events if isinstance(e, DataReceived))


async def worker(worker_id, host, port, authority, path_template, store_count, deadline, results):
    configuration = QuicConfiguration(is_client=True, alpn_protocols=H3_ALPN)
    ca_certs = os.environ.get("CACERT")
    if ca_certs:
        configuration.load_verify_locations(ca_certs)

    async with connect(host, port, configuration=configuration, create_protocol=H3Client) as client:
        while time.monotonic() < deadline:
            store_id = random.randint(1, store_count)
            path = path_template.format(store_id=store_id)
            start = time.monotonic()
            try:
                events = await asyncio.wait_for(client.get(authority, path), timeout=90)
                elapsed = time.monotonic() - start
                status = status_of(events)
                results.append({
                    "worker": worker_id,
                    "latency_s": elapsed,
                    "status": status,
                    "bytes": bytes_of(events),
                    "ok": status == 200,
                })
            except Exception as exc:  # noqa: BLE001 - record any transport/protocol failure as a failed request
                elapsed = time.monotonic() - start
                results.append({
                    "worker": worker_id,
                    "latency_s": elapsed,
                    "status": 0,
                    "bytes": 0,
                    "ok": False,
                    "error": str(exc),
                })


def percentile(sorted_values, pct):
    if not sorted_values:
        return None
    k = (len(sorted_values) - 1) * (pct / 100)
    f = int(k)
    c = min(f + 1, len(sorted_values) - 1)
    if f == c:
        return sorted_values[f]
    return sorted_values[f] + (sorted_values[c] - sorted_values[f]) * (k - f)


async def main():
    parser = argparse.ArgumentParser(description="HTTP/3 load test against edge-h3-sim")
    parser.add_argument("--url", default=os.environ.get("BASE_URL", "https://edge-h3-sim:8443"))
    parser.add_argument("--concurrency", type=int, default=int(os.environ.get("CONCURRENCY", "20")))
    parser.add_argument("--duration", type=int, default=int(os.environ.get("DURATION_S", "30")))
    parser.add_argument("--store-count", type=int, default=int(os.environ.get("STORE_COUNT", "50")))
    parser.add_argument("--scenario", default=os.environ.get("SCENARIO", "large"))
    parser.add_argument("--profile", default=os.environ.get("PROFILE", "baseline"))
    parser.add_argument("--start-date", default=os.environ.get("START_DATE"))
    parser.add_argument("--end-date", default=os.environ.get("END_DATE"))
    parser.add_argument("--out-dir", default=os.environ.get("OUT_DIR", "/results"))
    args = parser.parse_args()

    parsed = urlparse(args.url)
    host = parsed.hostname
    port = parsed.port or 443
    authority = f"{host}:{port}"
    path_template = (
        "/stores/{{store_id}}/annual-history?startDate={start}&endDate={end}".format(
            start=args.start_date, end=args.end_date
        )
    )

    results: list[dict] = []
    deadline = time.monotonic() + args.duration
    wall_start = time.time()

    await asyncio.gather(*[
        worker(i, host, port, authority, path_template, args.store_count, deadline, results)
        for i in range(args.concurrency)
    ])

    wall_elapsed = time.time() - wall_start
    latencies_ms = sorted(r["latency_s"] * 1000 for r in results)
    ok_results = [r for r in results if r["ok"]]

    summary = {
        "protocol": "http3-msgpack",
        "scenario": args.scenario,
        "profile": args.profile,
        "concurrency": args.concurrency,
        "duration_s": args.duration,
        "wall_elapsed_s": wall_elapsed,
        "total_requests": len(results),
        "success_count": len(ok_results),
        "success_rate": (len(ok_results) / len(results)) if results else 0.0,
        "throughput_rps": (len(results) / wall_elapsed) if wall_elapsed else 0.0,
        "latency_ms": {
            "min": latencies_ms[0] if latencies_ms else None,
            "avg": statistics.fmean(latencies_ms) if latencies_ms else None,
            "med": percentile(latencies_ms, 50),
            "p90": percentile(latencies_ms, 90),
            "p95": percentile(latencies_ms, 95),
            "p99": percentile(latencies_ms, 99),
            "max": latencies_ms[-1] if latencies_ms else None,
        },
        "bytes_received_total": sum(r["bytes"] for r in ok_results),
        "avg_response_bytes": (sum(r["bytes"] for r in ok_results) / len(ok_results)) if ok_results else None,
    }

    os.makedirs(args.out_dir, exist_ok=True)
    out_path = os.path.join(args.out_dir, f"h3-{args.scenario}-{args.profile}.json")
    with open(out_path, "w") as f:
        json.dump(summary, f, indent=2)

    print(json.dumps(summary, indent=2))
    print(f"Results written to {out_path}")


if __name__ == "__main__":
    asyncio.run(main())
