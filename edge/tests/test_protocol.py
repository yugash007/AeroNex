"""Task 1: v1 protocol envelope + validator tests (TDD RED first)."""

from edge.app.protocol import (
    COMMAND_EXAMPLE,
    MAX_MESSAGE_ID_LEN,
    new_envelope,
    validate_envelope,
)


def test_accepts_canonical_move():
    msg = {
        "version": 1,
        "message_id": "cmd-001",
        "type": "COMMAND",
        "timestamp": 1791123456789,
        "payload": {
            "command": "MOVE",
            "parameters": {"direction": "FORWARD", "distance_m": 10},
            "confidence": 0.96,
        },
    }
    ok, reason = validate_envelope(msg)
    assert ok, reason
    assert COMMAND_EXAMPLE == msg


def test_rejects_missing_message_id():
    msg = {
        "version": 1,
        "type": "COMMAND",
        "timestamp": 1791123456789,
        "payload": {"command": "MOVE"},
    }
    ok, _ = validate_envelope(msg)
    assert not ok


def test_rejects_unknown_type():
    msg = new_envelope("FLY_TO_MOON", {})
    ok, _ = validate_envelope(msg)
    assert not ok


def test_rejects_version_2():
    msg = new_envelope("PING", {})
    msg["version"] = 2
    ok, _ = validate_envelope(msg)
    assert not ok


def test_rejects_bool_version():
    # bool is an int subclass (True == 1); schema const 1 and Dart reject it.
    msg = new_envelope("PING", {})
    msg["version"] = True
    assert validate_envelope(msg) == (False, "version must be 1")


def test_rejects_float_version():
    # 1.0 == 1 in Python; the schema's integer type and the Dart validator reject it.
    msg = new_envelope("PING", {})
    msg["version"] = 1.0
    assert validate_envelope(msg) == (False, "version must be 1")


def test_rejects_non_string_type_without_raising():
    for bad in (["PING"], {"a": 1}):
        msg = new_envelope("PING", {})
        msg["type"] = bad
        ok, reason = validate_envelope(msg)
        assert not ok
        assert reason.startswith("unknown type:")


def test_message_id_length_is_capped():
    msg = new_envelope("PING", {}, message_id="m" * MAX_MESSAGE_ID_LEN)
    ok, reason = validate_envelope(msg)
    assert ok, reason
    msg["message_id"] = "m" * (MAX_MESSAGE_ID_LEN + 1)
    ok, _ = validate_envelope(msg)
    assert not ok


def test_roundtrip_new_envelope():
    msg = new_envelope("COMMAND", {"command": "MOVE"}, message_id="cmd-001")
    assert set(msg.keys()) == {"version", "message_id", "type", "timestamp", "payload"}
    assert msg["version"] == 1
    assert msg["message_id"] == "cmd-001"
    assert msg["type"] == "COMMAND"
    assert isinstance(msg["timestamp"], int)
    assert msg["payload"] == {"command": "MOVE"}
    ok, reason = validate_envelope(msg)
    assert ok, reason
