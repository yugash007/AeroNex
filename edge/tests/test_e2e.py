"""Task 5: typed-text end-to-end test (TDD RED first).

Full loop without a microphone: Telugu transcript -> intent ->
envelope -> WebSocket -> ACK (request_id echo) -> COMMAND_STATUS,
plus STATUS proving the edge reports READY.
"""

import json
import subprocess
import sys
from pathlib import Path

from fastapi.testclient import TestClient

from edge.app import config
from edge.app.demo_client import build_command_envelope, run_in_process
from edge.app.intent import parse_transcript
from edge.app.protocol import new_envelope
from edge.app.server import create_app

TE_TEXT = "10 మీటర్లు ముందుకు వెళ్ళు"


def test_typed_telugu_command_reaches_edge_and_acks():
    intent = parse_transcript(TE_TEXT, "te")
    assert intent == {
        "command": "MOVE",
        "parameters": {"direction": "FORWARD", "distance_m": 10},
        "confidence": 0.9,
    }

    envelope = build_command_envelope(TE_TEXT, "te", message_id="e2e-te-1")
    assert envelope["type"] == "COMMAND"
    assert envelope["message_id"] == "e2e-te-1"
    assert envelope["payload"] == intent

    client = TestClient(create_app())
    with client.websocket_connect(config.WS_PATH) as ws:
        ws.send_text(json.dumps(envelope))

        ack = json.loads(ws.receive_text())
        assert ack["type"] == "ACK"
        assert ack["payload"]["request_id"] == "e2e-te-1"

        cmd_status = json.loads(ws.receive_text())
        assert cmd_status["type"] == "COMMAND_STATUS"
        assert cmd_status["payload"]["request_id"] == "e2e-te-1"

        ws.send_text(
            json.dumps(new_envelope("STATUS_REQUEST", {}, message_id="e2e-status-1"))
        )
        status = json.loads(ws.receive_text())
        assert status["type"] == "STATUS"
        assert status["payload"]["edge"] == config.EDGE_READY


def test_demo_client_imports_without_httpx():
    # The live CLI path must not need TestClient (httpx is test-only).
    repo_root = Path(__file__).resolve().parents[2]
    result = subprocess.run(
        [
            sys.executable,
            "-c",
            "import sys; sys.modules['httpx'] = None; import edge.app.demo_client",
        ],
        cwd=repo_root,
        capture_output=True,
        text=True,
    )
    assert result.returncode == 0, result.stderr


def test_run_in_process_still_round_trips():
    result = run_in_process("move forward 10 meters", "en", message_id="e2e-en-1")
    assert result["ack"]["payload"]["request_id"] == "e2e-en-1"
    assert result["command_status"]["type"] == "COMMAND_STATUS"
    assert result["status"]["payload"]["edge"] == config.EDGE_READY
