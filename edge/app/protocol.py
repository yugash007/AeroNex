"""v1 message envelope: constructors + validator.

Envelope keys (exact): version / message_id / type / timestamp / payload.
Spec: SRS sections 18-21.
"""

from __future__ import annotations

import time
import uuid

VERSION = 1

MOBILE_TO_EDGE = frozenset({"PING", "COMMAND", "STATUS_REQUEST", "CANCEL"})
EDGE_TO_MOBILE = frozenset({"PONG", "ACK", "COMMAND_STATUS", "STATUS", "ERROR"})
MESSAGE_TYPES = MOBILE_TO_EDGE | EDGE_TO_MOBILE

ENVELOPE_KEYS = frozenset({"version", "message_id", "type", "timestamp", "payload"})

# Bounds what a peer can make the duplicate registry retain per ID.
MAX_MESSAGE_ID_LEN = 128

COMMAND_EXAMPLE = {
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


def new_envelope(type: str, payload: dict, message_id: str | None = None) -> dict:
    """Build a v1 envelope. Generates a unique message_id when not given."""
    return {
        "version": VERSION,
        "message_id": message_id if message_id is not None else uuid.uuid4().hex,
        "type": type,
        "timestamp": int(time.time() * 1000),
        "payload": payload,
    }


def validate_envelope(msg: dict) -> tuple[bool, str]:
    """Validate a v1 envelope. Returns (True, "") or (False, reason)."""
    if not isinstance(msg, dict):
        return False, "envelope must be an object"
    if set(msg.keys()) != ENVELOPE_KEYS:
        return False, f"envelope keys must be exactly {sorted(ENVELOPE_KEYS)}"
    # Integer 1 only: bool is an int subclass (True == 1) and 1.0 == 1, but the
    # schema and the Dart validator reject both.
    version = msg["version"]
    if not isinstance(version, int) or isinstance(version, bool) or version != 1:
        return False, "version must be 1"
    if not isinstance(msg["message_id"], str) or not msg["message_id"]:
        return False, "message_id must be a non-empty string"
    if len(msg["message_id"]) > MAX_MESSAGE_ID_LEN:
        return False, f"message_id must be at most {MAX_MESSAGE_ID_LEN} chars"
    if not isinstance(msg["type"], str) or msg["type"] not in MESSAGE_TYPES:
        return False, f"unknown type: {msg['type']!r}"
    ts = msg["timestamp"]
    if not isinstance(ts, int) or isinstance(ts, bool):
        return False, "timestamp must be int ms epoch"
    if not isinstance(msg["payload"], dict):
        return False, "payload must be an object"
    return True, ""
