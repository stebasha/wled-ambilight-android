#!/usr/bin/env python3
"""Tiny WLED HTTP API stub for UI demos and screenshot capture.

This is a development helper only: it emulates `/json/info` and `/json/state` so the app can be
exercised without a controller. It does NOT emulate LED output or realtime UDP.

Usage:
    python scripts/fake_wled_server.py --port 8099

On an Android emulator, expose it with `adb reverse tcp:8099 tcp:8099` and add 127.0.0.1:8099
in the app. On a phone/TV in the same LAN use the host's IP address and allow the port in the
firewall.
"""

from __future__ import annotations

import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

STATE = {
    "on": True,
    "bri": 128,
    "live": False,
    "seg": [{"id": 0, "on": True, "start": 0, "stop": 96, "col": [[255, 176, 32]], "fx": 0}],
}


class Handler(BaseHTTPRequestHandler):
    server_version = "FakeWLED/0.1"

    def _send(self, payload: dict, status: int = 200) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        if self.path.split("?")[0] == "/json/info":
            self._send(
                {
                    "name": "Demo WLED",
                    "ver": "0.14.4",
                    "arch": "esp32",
                    "leds": {"count": 96, "fps": 42, "pwr": 120},
                    "wifi": {"signal": 82, "rssi": -55, "channel": 6},
                    "udpport": 21324,
                    "live": STATE["live"],
                    "uptime": 3600,
                }
            )
        elif self.path.split("?")[0] == "/json/state":
            self._send(STATE)
        else:
            self._send({"error": "not found"}, status=404)

    def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length else b"{}"
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError:
            self._send({"error": "bad json"}, status=400)
            return
        if self.path.split("?")[0] != "/json/state":
            self._send({"error": "not found"}, status=404)
            return
        if "on" in payload:
            STATE["on"] = bool(payload["on"])
        if "bri" in payload:
            STATE["bri"] = int(payload["bri"])
        print(f"POST /json/state -> {payload}", flush=True)
        self._send({})

    def log_message(self, fmt: str, *args) -> None:  # keep output readable
        print(f"{self.address_string()} {fmt % args}", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8099)
    parser.add_argument("--host", default="0.0.0.0")
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"Fake WLED API listening on http://{args.host}:{args.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
