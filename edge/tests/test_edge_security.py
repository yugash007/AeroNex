"""Audit regressions (2026-10-28): who may connect, per-client command IDs,
negations hidden by look-alike characters, and the safety envelope."""

import json

import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from edge.app import config
from edge.app.commands import route
from edge.app.intent import IntentError, parse_transcript
from edge.app.protocol import new_envelope
from edge.app.server import create_app
from edge.app.state import CommandRegistry
from edge.app.websocket import AccessPolicy

REMOTE_ONLY = frozenset()  # no peer counts as local: as a LAN client would be seen


def _send(ws, type_, payload, message_id=None):
    ws.send_text(json.dumps(new_envelope(type_, payload, message_id=message_id)))


def _recv(ws):
    return json.loads(ws.receive_text())


def _refused(client, url="/ws", **kwargs):
    with pytest.raises(WebSocketDisconnect) as info:
        with client.websocket_connect(url, **kwargs) as ws:
            ws.receive_text()
    assert info.value.code == 1008


# --- who may connect ------------------------------------------------------------------------------

def test_the_default_bind_is_loopback():
    assert config.HOST == "127.0.0.1"


def test_a_remote_peer_is_refused_without_a_configured_token():
    _refused(TestClient(create_app(AccessPolicy(local_peers=REMOTE_ONLY))))


def test_a_configured_token_is_required_and_checked():
    client = TestClient(create_app(AccessPolicy(token="s3cret", local_peers=REMOTE_ONLY)))
    _refused(client)
    _refused(client, "/ws?token=wrong")
    _refused(client, headers={"authorization": "Bearer wrong"})
    with client.websocket_connect("/ws?token=s3cret") as ws:
        _send(ws, "PING", {})
        assert _recv(ws)["type"] == "PONG"
    with client.websocket_connect("/ws", headers={"authorization": "Bearer s3cret"}) as ws:
        _send(ws, "PING", {})
        assert _recv(ws)["type"] == "PONG"


def test_a_browser_origin_is_refused_unless_allow_listed():
    client = TestClient(create_app(AccessPolicy()))
    _refused(client, headers={"origin": "http://evil.example"})
    allowed = TestClient(create_app(AccessPolicy(origins=frozenset({"http://ops.local"}))))
    with allowed.websocket_connect("/ws", headers={"origin": "http://ops.local"}) as ws:
        _send(ws, "PING", {})
        assert _recv(ws)["type"] == "PONG"


def test_the_policy_reads_the_environment(monkeypatch):
    monkeypatch.setenv(config.TOKEN_ENV, "  envtoken ")
    monkeypatch.setenv(config.ORIGINS_ENV, "http://a, http://b ,")
    assert config.token_from_env() == "envtoken"
    assert config.origins_from_env() == frozenset({"http://a", "http://b"})
    monkeypatch.setenv(config.TOKEN_ENV, "   ")
    assert config.token_from_env() is None


# --- command IDs are per client ----------------------------------------------------------------

def test_another_client_cannot_evict_ids_so_a_cancelled_command_never_reruns():
    registry = CommandRegistry(max_entries=8)
    assert registry.register("victim-1", owner="phone") == "NEW"
    registry.set_status("victim-1", "CANCELLED", owner="phone")
    for i in range(100):
        registry.register(f"flood-{i}", owner="attacker")
    assert registry.register("victim-1", owner="phone") == "DUP"
    assert registry.get_status("victim-1", owner="phone") == "CANCELLED"


def test_set_status_refreshes_an_ids_place_in_the_eviction_order():
    registry = CommandRegistry(max_entries=2)
    registry.register("a")
    registry.register("b")
    registry.set_status("a", "CANCELLED")  # now the most recently used
    registry.register("c")
    assert registry.get_status("a") == "CANCELLED"
    assert registry.get_status("b") is None


def test_the_owner_count_is_bounded():
    registry = CommandRegistry(max_owners=2)
    for owner in ("one", "two", "three"):
        registry.register("x", owner=owner)
    assert registry.get_status("x", owner="one") is None
    assert registry.get_status("x", owner="three") == "RECEIVED"


def test_a_client_cannot_cancel_another_clients_command():
    registry = CommandRegistry()
    registry.register("cmd-1", owner="phone")
    assert registry.get_status("cmd-1", owner="intruder") is None


# --- negations and numbers -----------------------------------------------------------------------

@pytest.mark.parametrize("text", [
    "don´t take off", "don`t take off", "don′t take off", "don’t take off",
    "no​t take off", "do n­ot take off",
])
def test_look_alike_apostrophes_and_invisible_characters_never_hide_a_negation(text):
    with pytest.raises(IntentError):
        parse_transcript(text, "en")


def test_decomposed_indic_text_is_read_like_its_nfc_form():
    import unicodedata
    for text in ("10 ಮುಂದೆ ಹಾರು ಬೇಡ", "ಇಳಿ ಬೇಡ"):
        with pytest.raises(IntentError):
            parse_transcript(unicodedata.normalize("NFD", text), "kn")


def test_takeoff_reads_its_height_and_never_drops_a_quantity():
    assert parse_transcript("take off to three metres", "en")["parameters"] == {"altitude_m": 3}
    assert parse_transcript("take off to 2.5 meters", "en")["parameters"] == {"altitude_m": 2.5}
    assert parse_transcript("take off", "en")["parameters"] == {}
    for text in ("take off to minus 5 meters", "take off to 50 centimeters", "take off to two hundred meters",
                 "take off to 0 meters", "take off to 9999 meters"):
        with pytest.raises(IntentError):
            parse_transcript(text, "en")


def test_land_and_hover_take_no_number_but_stop_always_stops():
    for text in ("land in 5 seconds", "hover at 5 meters", "land 3"):
        with pytest.raises(IntentError):
            parse_transcript(text, "en")
    assert parse_transcript("stop 2", "en")["command"] == "STOP"


def test_digits_glued_to_a_name_are_never_a_distance():
    for text in ("go forward to gate A5", "go forward x2.5 meters", "move forward to A5 3 meters"):
        with pytest.raises(IntentError):
            parse_transcript(text, "en")


# --- the router holds the safety envelope ------------------------------------------------------

def test_the_router_refuses_moves_beyond_ten_metres():
    assert route({"command": "MOVE", "parameters": {"direction": "UP", "distance_m": 10}})[0] == "EXECUTING"
    status, payload = route({"command": "MOVE", "parameters": {"direction": "UP", "distance_m": 500}})
    assert status == "REJECTED"
    assert "10" in payload["reason"]


def test_the_router_checks_an_optional_takeoff_altitude():
    assert route({"command": "TAKEOFF"})[0] == "EXECUTING"
    assert route({"command": "TAKEOFF", "parameters": {}})[0] == "EXECUTING"
    assert route({"command": "TAKEOFF", "parameters": {"altitude_m": 3}})[0] == "EXECUTING"
    for bad in (0.5, 11, 500, -3, True, "3", None):
        assert route({"command": "TAKEOFF", "parameters": {"altitude_m": bad}})[0] == "REJECTED", bad
    assert route({"command": "TAKEOFF", "parameters": [3]})[0] == "REJECTED"
