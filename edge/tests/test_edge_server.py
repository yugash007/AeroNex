"""Task 2: edge WebSocket server tests (TDD RED first).

Drives the FastAPI app in edge.app.server via Starlette TestClient.
"""

import json
import time

from fastapi.testclient import TestClient

from edge.app.commands import route
from edge.app.protocol import MAX_MESSAGE_ID_LEN, new_envelope
from edge.app.server import app, create_app
from edge.app.state import CommandRegistry


def _send(ws, type_, payload, message_id=None):
    ws.send_text(json.dumps(new_envelope(type_, payload, message_id=message_id)))


def _recv(ws):
    return json.loads(ws.receive_text())


def test_ping_pong_under_100ms():
    client = TestClient(app)
    with client.websocket_connect("/ws") as ws:
        start = time.perf_counter()
        _send(ws, "PING", {})
        reply = _recv(ws)
        elapsed_ms = (time.perf_counter() - start) * 1000
        assert reply["type"] == "PONG"
        assert elapsed_ms < 100


def test_command_gets_ack_with_request_id():
    client = TestClient(app)
    with client.websocket_connect("/ws") as ws:
        _send(
            ws,
            "COMMAND",
            {"command": "MOVE", "parameters": {"direction": "FORWARD", "distance_m": 10}},
            message_id="cmd-ack-1",
        )
        ack = _recv(ws)
        assert ack["type"] == "ACK"
        assert ack["payload"]["request_id"] == "cmd-ack-1"
        status = _recv(ws)  # drain trailing COMMAND_STATUS
        assert status["type"] == "COMMAND_STATUS"


def test_duplicate_command_returns_existing_state():
    client = TestClient(app)
    with client.websocket_connect("/ws") as ws:
        payload = {
            "command": "MOVE",
            "parameters": {"direction": "FORWARD", "distance_m": 10},
        }
        _send(ws, "COMMAND", payload, message_id="cmd-dup-1")
        _recv(ws)  # ACK
        first = _recv(ws)  # COMMAND_STATUS
        assert first["type"] == "COMMAND_STATUS"
        _send(ws, "COMMAND", payload, message_id="cmd-dup-1")
        _recv(ws)  # ACK again (receipt only)
        second = _recv(ws)
        assert second["type"] == "COMMAND_STATUS"
        assert second["payload"]["status"] == first["payload"]["status"]
        assert second["payload"]["request_id"] == "cmd-dup-1"


def test_invalid_message_gets_error():
    client = TestClient(app)
    with client.websocket_connect("/ws") as ws:
        ws.send_text("this is not json")
        reply = _recv(ws)
        assert reply["type"] == "ERROR"


def test_version_mismatch_gets_error():
    client = TestClient(app)
    with client.websocket_connect("/ws") as ws:
        msg = new_envelope("PING", {}, message_id="ping-v2")
        msg["version"] = 2
        ws.send_text(json.dumps(msg))
        reply = _recv(ws)
        assert reply["type"] == "ERROR"


def test_status_request_returns_ready():
    client = TestClient(app)
    with client.websocket_connect("/ws") as ws:
        _send(ws, "STATUS_REQUEST", {}, message_id="status-1")
        reply = _recv(ws)
        assert reply["type"] == "STATUS"
        assert reply["payload"]["edge"] == "READY"
        assert reply["payload"]["version"] == 1
        assert isinstance(reply["payload"]["uptime_ms"], int)
        assert "ai_status" in reply["payload"]


def test_cancel_moves_to_cancelled():
    client = TestClient(app)
    with client.websocket_connect("/ws") as ws:
        _send(ws, "COMMAND", {"command": "HOVER"}, message_id="cmd-cancel-1")
        _recv(ws)  # ACK
        _recv(ws)  # COMMAND_STATUS
        _send(ws, "CANCEL", {"request_id": "cmd-cancel-1"}, message_id="cancel-1")
        reply = _recv(ws)
        assert reply["type"] == "COMMAND_STATUS"
        assert reply["payload"]["status"] == "CANCELLED"
        assert reply["payload"]["request_id"] == "cmd-cancel-1"


def test_reconnect_after_server_restart():
    with TestClient(create_app()).websocket_connect("/ws") as ws:
        _send(ws, "PING", {})
        assert _recv(ws)["type"] == "PONG"
    # Simulated restart: brand-new app instance, fresh state.
    with TestClient(create_app()).websocket_connect("/ws") as ws:
        _send(ws, "PING", {})
        assert _recv(ws)["type"] == "PONG"


def _assert_still_serving(ws):
    _send(ws, "PING", {})
    assert _recv(ws)["type"] == "PONG"


def test_route_rejects_non_string_command_and_direction():
    for payload in (
        {"command": []},
        {"command": {"a": 1}},
        {"command": "MOVE", "parameters": {"direction": {}, "distance_m": 5}},
        {"command": "MOVE", "parameters": {"direction": ["UP"], "distance_m": 5}},
    ):
        status, status_payload = route(payload)
        assert status == "REJECTED"
        assert status_payload["status"] == "REJECTED"


def test_non_string_command_is_rejected_not_stuck_received():
    client = TestClient(create_app())
    cases = {
        "cmd-bad-1": {"command": []},
        "cmd-bad-2": {
            "command": "MOVE",
            "parameters": {"direction": {}, "distance_m": 5},
        },
    }
    with client.websocket_connect("/ws") as ws:
        for message_id, payload in cases.items():
            _send(ws, "COMMAND", payload, message_id=message_id)
            assert _recv(ws)["type"] == "ACK"
            status = _recv(ws)
            assert status["type"] == "COMMAND_STATUS"
            assert status["payload"]["status"] == "REJECTED"
        _assert_still_serving(ws)
    # A retry on a new connection reports the verdict, not RECEIVED.
    with client.websocket_connect("/ws") as ws:
        for message_id in cases:
            _send(ws, "COMMAND", {"command": "LAND"}, message_id=message_id)
            _recv(ws)  # ACK
            assert _recv(ws)["payload"]["status"] == "REJECTED"


def test_routing_failure_records_rejected_not_received(monkeypatch):
    def broken_route(payload):
        raise RuntimeError("router bug")

    monkeypatch.setattr("edge.app.commands.route", broken_route)
    client = TestClient(create_app())
    with client.websocket_connect("/ws") as ws:
        _send(ws, "COMMAND", {"command": "LAND"}, message_id="cmd-boom-1")
        assert _recv(ws)["type"] == "ACK"
        assert _recv(ws)["payload"]["status"] == "REJECTED"
        _send(ws, "COMMAND", {"command": "LAND"}, message_id="cmd-boom-1")
        _recv(ws)  # ACK
        assert _recv(ws)["payload"]["status"] == "REJECTED"
        _assert_still_serving(ws)

def test_non_string_type_gets_error():
    client = TestClient(app)
    with client.websocket_connect("/ws") as ws:
        for bad in ([], {}, ["PING"]):
            msg = new_envelope("PING", {}, message_id="type-bad-1")
            msg["type"] = bad
            ws.send_text(json.dumps(msg))
            assert _recv(ws)["type"] == "ERROR"
        _assert_still_serving(ws)


def test_bool_version_gets_error():
    client = TestClient(app)
    with client.websocket_connect("/ws") as ws:
        msg = new_envelope("PING", {}, message_id="ping-vbool")
        msg["version"] = True
        ws.send_text(json.dumps(msg))
        assert _recv(ws)["type"] == "ERROR"


def test_binary_frame_gets_error():
    client = TestClient(app)
    with client.websocket_connect("/ws") as ws:
        ws.send_bytes(json.dumps(new_envelope("PING", {})).encode())
        assert _recv(ws)["type"] == "ERROR"
        _assert_still_serving(ws)


def test_deeply_nested_json_gets_error():
    client = TestClient(app)
    with client.websocket_connect("/ws") as ws:
        ws.send_text("[" * 100000 + "]" * 100000)
        reply = _recv(ws)
        assert reply["type"] == "ERROR"
        assert reply["payload"]["reason"] == "malformed JSON"
        _assert_still_serving(ws)


def test_huge_integer_literal_gets_error():
    client = TestClient(app)
    with client.websocket_connect("/ws") as ws:
        raw = json.dumps(new_envelope("PING", {}, message_id="ping-bigint"))
        ws.send_text(raw.replace('"timestamp": ', '"timestamp": ' + "9" * 5000, 1))
        reply = _recv(ws)
        assert reply["type"] == "ERROR"
        assert reply["payload"]["reason"] == "malformed JSON"
        _assert_still_serving(ws)


def test_cancel_does_not_overwrite_rejected():
    client = TestClient(create_app())
    with client.websocket_connect("/ws") as ws:
        payload = {
            "command": "MOVE",
            "parameters": {"direction": "FORWARD", "distance_m": 999},
        }
        _send(ws, "COMMAND", payload, message_id="cmd-rej-1")
        _recv(ws)  # ACK
        assert _recv(ws)["payload"]["status"] == "REJECTED"
        _send(ws, "CANCEL", {"request_id": "cmd-rej-1"}, message_id="cancel-rej-1")
        assert _recv(ws)["type"] == "ERROR"
        # The stored verdict is unchanged: a retry still reports REJECTED.
        _send(ws, "COMMAND", payload, message_id="cmd-rej-1")
        _recv(ws)  # ACK
        assert _recv(ws)["payload"]["status"] == "REJECTED"


def test_oversized_message_id_gets_error_and_is_not_registered():
    app_ = create_app()
    with TestClient(app_).websocket_connect("/ws") as ws:
        long_id = "m" * (MAX_MESSAGE_ID_LEN + 1)
        _send(ws, "COMMAND", {"command": "HOVER"}, message_id=long_id)
        assert _recv(ws)["type"] == "ERROR"
        assert app_.state.registry.get_status(long_id) is None


def test_registry_is_bounded_and_evicts_oldest():
    registry = CommandRegistry(max_entries=3)
    for i in range(5):
        assert registry.register(f"id-{i}") == "NEW"
    assert registry.get_status("id-0") is None
    assert registry.get_status("id-1") is None
    for i in (2, 3, 4):
        assert registry.get_status(f"id-{i}") == "RECEIVED"
    # A retried ID counts as recently used and outlives older entries.
    assert registry.register("id-2") == "DUP"
    registry.register("id-5")
    assert registry.get_status("id-2") == "RECEIVED"
    assert registry.get_status("id-3") is None


def test_default_registry_has_a_finite_cap():
    registry = create_app().state.registry
    cap = registry.max_entries
    assert 0 < cap
    for i in range(cap + 10):
        registry.register(f"flood-{i}")
    assert registry.get_status("flood-0") is None
    assert registry.get_status(f"flood-{cap + 9}") == "RECEIVED"
