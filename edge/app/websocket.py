"""WebSocket message handler for the /ws endpoint.

Envelope validation is Task 1's protocol.validate_envelope — never
reimplemented here. ACK means receipt only; the verdict for a COMMAND
always arrives in the subsequent COMMAND_STATUS.
"""

from __future__ import annotations

import json
import time

from fastapi import WebSocket, WebSocketDisconnect

from edge.app import commands, config
from edge.app.protocol import new_envelope, validate_envelope
from edge.app.state import CommandRegistry

# Verdicts a CANCEL must not rewrite. CANCELLED itself is left out so a
# repeated CANCEL is an idempotent no-op that re-reports CANCELLED.
_UNCANCELLABLE = frozenset({"REJECTED", "COMPLETED", "FAILED"})


async def _send(ws: WebSocket, type_: str, payload: dict) -> None:
    await ws.send_text(json.dumps(new_envelope(type_, payload)))


async def _error(ws: WebSocket, reason: str) -> None:
    await _send(ws, "ERROR", {"reason": reason})


async def handle_client(
    ws: WebSocket, registry: CommandRegistry, start_time: float
) -> None:
    """Serve one client connection until it disconnects."""
    await ws.accept()
    try:
        while True:
            message = await ws.receive()
            if message["type"] == "websocket.disconnect":
                break
            raw = message.get("text")
            if raw is None:  # binary frame; the protocol is JSON text only
                await _error(ws, "binary frames not supported")
                continue
            try:
                msg = json.loads(raw)
            except (ValueError, RecursionError, TypeError):
                # ValueError covers JSONDecodeError and the int digit limit;
                # RecursionError comes from deeply nested arrays/objects.
                await _error(ws, "malformed JSON")
                continue
            ok, reason = validate_envelope(msg)
            if not ok:
                await _error(ws, reason)
                continue
            await _dispatch(ws, msg, registry, start_time)
    except WebSocketDisconnect:
        pass


async def _dispatch(
    ws: WebSocket, msg: dict, registry: CommandRegistry, start_time: float
) -> None:
    type_ = msg["type"]
    message_id = msg["message_id"]
    payload = msg["payload"]

    if type_ == "PING":
        await _send(ws, "PONG", {})
    elif type_ == "COMMAND":
        if registry.register(message_id) == "DUP":
            status = registry.get_status(message_id)
            status_payload = {"status": status}
        else:
            try:
                status, status_payload = commands.route(payload)
            except Exception:
                # Fail closed: a registered ID must never stay at RECEIVED.
                status = "REJECTED"
                status_payload = {"status": status, "reason": "invalid payload"}
            registry.set_status(message_id, status)
        # ACK = receipt only; verdict follows in COMMAND_STATUS.
        await _send(ws, "ACK", {"request_id": message_id, "status": "RECEIVED"})
        await _send(
            ws, "COMMAND_STATUS", {"request_id": message_id, **status_payload}
        )
    elif type_ == "STATUS_REQUEST":
        await _send(
            ws,
            "STATUS",
            {
                "edge": config.EDGE_READY,
                "version": config.PROTOCOL_VERSION,
                "uptime_ms": int((time.monotonic() - start_time) * 1000),
                "ai_status": config.AI_STATUS,
            },
        )
    elif type_ == "CANCEL":
        target = payload.get("request_id")
        current = registry.get_status(target) if isinstance(target, str) else None
        if current is None:
            await _error(ws, f"unknown request_id: {target!r}")
        elif current in _UNCANCELLABLE:
            await _error(ws, f"cannot cancel request_id {target!r}: already {current}")
        else:
            registry.set_status(target, "CANCELLED")
            await _send(
                ws,
                "COMMAND_STATUS",
                {"request_id": target, "status": "CANCELLED"},
            )
    else:
        await _error(ws, f"unsupported type: {type_!r}")
