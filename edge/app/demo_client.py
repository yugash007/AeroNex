"""Typed-text end-to-end demo: intent -> envelope -> WebSocket -> ACK -> STATUS.

No microphone needed. Parses typed text with Task 3 ``parse_transcript``,
wraps the intent in a Task 1 envelope via ``new_envelope``, and sends it to
the Task 2 edge server over ``/ws``.

Two paths share the same builder:

- ``run_live``: ``websockets`` client against a running server
  (``uvicorn edge.app.server:app --port 8000``). Used by the CLI.
- ``run_in_process``: Starlette ``TestClient`` against ``create_app()``.
  Offline helper for tests and CI (no socket, no server process).

CLI::

    python -m edge.app.demo_client --host 127.0.0.1 --text "move forward 10 meters" --lang en
    python -m edge.app.demo_client --host 127.0.0.1 --text "10 మీటర్లు ముందుకు వెళ్ళు" --lang te
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
import uuid

import websockets

from edge.app import config
from edge.app.intent import parse_transcript
from edge.app.protocol import new_envelope
from edge.app.server import create_app

try:  # Windows consoles default to cp1252, which cannot print te/hi text.
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:  # pragma: no cover - best effort only.
    pass


def build_command_envelope(
    text: str, lang: str, message_id: str | None = None
) -> dict:
    """Parse typed text and wrap the intent in a COMMAND envelope."""
    intent = parse_transcript(text, lang)
    return new_envelope(
        "COMMAND", intent, message_id=message_id or f"demo-{uuid.uuid4().hex[:8]}"
    )


def run_in_process(
    text: str, lang: str, message_id: str = "demo-e2e-1"
) -> dict[str, dict]:
    """Send one typed command via TestClient; return ack/status exchanges."""
    # Imported here: TestClient needs httpx, which the live CLI path does not.
    from fastapi.testclient import TestClient

    envelope = build_command_envelope(text, lang, message_id=message_id)
    client = TestClient(create_app())
    with client.websocket_connect(config.WS_PATH) as ws:
        ws.send_text(json.dumps(envelope))
        ack = json.loads(ws.receive_text())
        cmd_status = json.loads(ws.receive_text())
        ws.send_text(
            json.dumps(new_envelope("STATUS_REQUEST", {}, message_id="demo-status-1"))
        )
        status = json.loads(ws.receive_text())
    return {"envelope": envelope, "ack": ack, "command_status": cmd_status,
            "status": status}


async def run_live(
    host: str, text: str, lang: str, port: int = 8000,
    message_id: str | None = None,
) -> dict[str, dict]:
    """Send one typed command to a live edge server; print each hop."""
    envelope = build_command_envelope(text, lang, message_id=message_id)
    print(f"text   : {text!r} ({lang})")
    print(f"intent : {json.dumps(envelope['payload'], ensure_ascii=False)}")
    print(f"send   : COMMAND id={envelope['message_id']}")

    uri = f"ws://{host}:{port}{config.WS_PATH}"
    async with websockets.connect(uri) as ws:
        await ws.send(json.dumps(envelope))
        ack = json.loads(await ws.recv())
        print(f"recv   : ACK request_id={ack['payload'].get('request_id')}")
        cmd_status = json.loads(await ws.recv())
        print(f"recv   : COMMAND_STATUS "
              f"request_id={cmd_status['payload'].get('request_id')} "
              f"status={cmd_status['payload'].get('status')}")
        await ws.send(
            json.dumps(new_envelope("STATUS_REQUEST", {}, message_id="demo-status-1"))
        )
        status = json.loads(await ws.recv())
        print(f"recv   : STATUS edge={status['payload'].get('edge')} "
              f"ai_status={status['payload'].get('ai_status')}")
    return {"envelope": envelope, "ack": ack, "command_status": cmd_status,
            "status": status}


def main(argv: list[str] | None = None) -> dict[str, dict]:
    """CLI entry point: ``--host --text --lang`` (+ optional ``--port``)."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1",
                        help="Edge server host (default: 127.0.0.1)")
    parser.add_argument("--port", type=int, default=config.PORT,
                        help=f"Edge server port (default: {config.PORT})")
    parser.add_argument("--text", default="move forward 10 meters",
                        help="Typed command text (stand-in for STT output)")
    parser.add_argument("--lang", default="en",
                        help="Spoken language code: en | hi | te")
    args = parser.parse_args(argv)
    return asyncio.run(
        run_live(args.host, args.text, args.lang, port=args.port)
    )


if __name__ == "__main__":
    main()
