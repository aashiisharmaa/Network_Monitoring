"""Tests run against a temporary HTTP server, without the application or database."""
import importlib.util
import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

spec = importlib.util.spec_from_file_location("benchmark_backend", Path(__file__).with_name("benchmark-backend.py"))
benchmark = importlib.util.module_from_spec(spec)
spec.loader.exec_module(benchmark)


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        status, body = {
            "/ok": (200, b'{"success":true,"private":"do-not-store"}'),
            "/app-error": (200, b'{"success":false}'),
            "/unauthorized": (401, b'{"message":"no access"}'),
            "/html": (200, b'<html>not an API response</html>'),
        }[self.path]
        self.send_response(status)
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


class BenchmarkTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.origin = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=2)

    def test_success_keeps_measurements_without_body_or_token(self):
        sample = benchmark.measure(self.origin + "/ok", "secret-test-token", 2)
        self.assertEqual(sample["status"], 200)
        self.assertIsNone(sample["error"])
        self.assertGreater(sample["bytes"], 0)
        self.assertGreaterEqual(sample["elapsed_ms"], 0)
        self.assertNotIn("secret-test-token", json.dumps(sample))
        self.assertNotIn("do-not-store", json.dumps(sample))

    def test_http_200_application_error_is_failure(self):
        sample = benchmark.measure(self.origin + "/app-error", "", 2)
        self.assertEqual(sample["error"], "application_error")
        self.assertEqual(benchmark.summarize([sample])["successful"], 0)

    def test_http_error_and_invalid_json_are_not_timing_successes(self):
        unauthorized = benchmark.measure(self.origin + "/unauthorized", "", 2)
        html = benchmark.measure(self.origin + "/html", "", 2)
        self.assertEqual(unauthorized["status"], 401)
        self.assertEqual(html["error"], "invalid_json")
        summary = benchmark.summarize([unauthorized, html])
        self.assertEqual(summary["failed"], 2)
        self.assertIsNone(summary["p95_ms"])

    def test_percentile_uses_only_successful_requests(self):
        samples = [{"elapsed_ms": n, "status": 200, "error": None, "bytes": 100}
                   for n in range(1, 21)]
        samples.append({"elapsed_ms": 1000, "status": 500, "error": "http_error", "bytes": None})
        summary = benchmark.summarize(samples)
        self.assertEqual(summary["median_ms"], 10.5)
        self.assertEqual(summary["p95_ms"], 19)
        self.assertEqual(summary["failed"], 1)


if __name__ == "__main__":
    unittest.main()
