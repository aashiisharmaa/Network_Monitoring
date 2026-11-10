"""Read-only sequential HTTP baseline; never stores tokens or response bodies."""
import argparse
import json
import math
import os
import platform
import statistics
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen


def measure(url, token, timeout):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    started = time.perf_counter()
    status, size, error = None, None, None
    try:
        with urlopen(Request(url, headers=headers), timeout=timeout) as response:
            body = response.read()
            status, size = response.status, len(body)
            try:
                payload = json.loads(body)
                if isinstance(payload, dict) and payload.get("success") is False:
                    error = "application_error"
            except (ValueError, UnicodeDecodeError):
                error = "invalid_json"
    except HTTPError as ex:
        status, error = ex.code, "http_error"
        ex.close()
    except (URLError, TimeoutError, OSError):
        error = "connection_or_timeout"
    return {"elapsed_ms": round((time.perf_counter() - started) * 1000, 3),
            "status": status, "bytes": size, "error": error}


def summarize(samples):
    successful = [sample for sample in samples
                  if sample["error"] is None and sample["status"] == 200]
    elapsed = sorted(sample["elapsed_ms"] for sample in successful)
    return {
        "requests": len(samples), "successful": len(successful),
        "failed": len(samples) - len(successful),
        "median_ms": round(statistics.median(elapsed), 3) if elapsed else None,
        "p95_ms": elapsed[math.ceil(len(elapsed) * 0.95) - 1] if elapsed else None,
        "max_ms": max(elapsed) if elapsed else None,
        "mean_response_bytes": round(statistics.mean(s["bytes"] for s in successful)) if successful else None,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:3001")
    parser.add_argument("--file-id", type=int, help="Optional existing KPI file ID for dashboard scope")
    parser.add_argument("--samples", type=int, default=10)
    parser.add_argument("--warmups", type=int, default=1)
    parser.add_argument("--timeout", type=float, default=60)
    parser.add_argument("--fixture", default="unspecified", help="Dataset label for reproducible comparisons")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    parsed = urlsplit(args.base_url)
    if (parsed.scheme not in ("http", "https") or not parsed.netloc
            or parsed.username or parsed.password or parsed.query or parsed.fragment
            or parsed.path not in ("", "/")):
        parser.error("base-url must be an HTTP(S) origin without credentials, query or path")
    if not 1 <= args.samples <= 1000 or not 0 <= args.warmups <= 20 or args.timeout <= 0:
        parser.error("samples must be 1..1000, warmups 0..20, timeout positive")
    if args.file_id is not None and args.file_id <= 0:
        parser.error("file-id must be positive")
    now = datetime.now(timezone.utc)
    output = args.output or Path(__file__).resolve().parents[1] / "reports" / "performance" / f"baseline-{now:%Y%m%dT%H%M%S%fZ}.json"
    report = {
        "timestamp_utc": now.isoformat(),
        "timestamp_ist": now.astimezone(timezone(timedelta(hours=5, minutes=30))).isoformat(),
        "base_url": args.base_url, "fixture": args.fixture, "file_id": args.file_id,
        "python": platform.python_version(), "mode": "sequential-read-only",
        "note": "First request is separate; not a guaranteed cold cache. Warmup failures also fail the run. Not a load test.",
        "endpoints": [],
    }
    suffix = f"?fileId={args.file_id}" if args.file_id else ""
    token = os.environ.get("KPI_BENCHMARK_TOKEN", "")
    failed = False
    for path in ("/health", "/api/v1/dashboard/stats" + suffix):
        url = args.base_url.rstrip("/") + path
        first = measure(url, token, args.timeout)
        warmups = [measure(url, token, args.timeout) for _ in range(args.warmups)]
        samples = [measure(url, token, args.timeout) for _ in range(args.samples)]
        summary = summarize(samples)
        failed = failed or any(s["error"] or s["status"] != 200 for s in [first, *warmups, *samples])
        report["endpoints"].append({"path": path, "first_request": first,
                                    "warmups": warmups, "summary": summary, "samples": samples})
        print(f"{path}: successful={summary['successful']}/{summary['requests']} "
              f"median={summary['median_ms']}ms p95={summary['p95_ms']}ms", flush=True)
    report["passed"] = not failed
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(f"Baseline saved: {output}")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
