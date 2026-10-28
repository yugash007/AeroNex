"""Command routing: validate a COMMAND payload, return lifecycle transition.

route(payload) -> (status, status_payload). Unknown commands or invalid
MOVE / TAKEOFF params yield ("REJECTED", {...reason...}). No MAVLink/hardware
here. The limits are the app's safety envelope (README "Safety model").
"""

from __future__ import annotations

VALID_COMMANDS = frozenset({"MOVE", "TAKEOFF", "LAND", "HOVER", "STOP"})

DIRECTIONS = frozenset({"FORWARD", "BACKWARD", "LEFT", "RIGHT", "UP", "DOWN"})

MAX_DISTANCE_M = 10
MIN_TAKEOFF_ALTITUDE_M = 1
MAX_TAKEOFF_ALTITUDE_M = 10


def _is_number(value) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def _rejected(command, reason: str) -> tuple[str, dict]:
    return "REJECTED", {"command": command, "status": "REJECTED", "reason": reason}


def route(payload: dict) -> tuple[str, dict]:
    """Validate payload, return (lifecycle status, status payload)."""
    command = payload.get("command")
    # Type check first: an unhashable JSON value (list/object) would make the
    # frozenset membership test raise instead of returning REJECTED.
    if not isinstance(command, str) or command not in VALID_COMMANDS:
        return "REJECTED", {
            "command": command,
            "status": "REJECTED",
            "reason": f"unknown command: {command!r}",
        }
    if command == "MOVE":
        params = payload.get("parameters")
        if not isinstance(params, dict):
            return "REJECTED", {
                "command": command,
                "status": "REJECTED",
                "reason": "MOVE requires parameters object",
            }
        direction = params.get("direction")
        if not isinstance(direction, str) or direction not in DIRECTIONS:
            return "REJECTED", {
                "command": command,
                "status": "REJECTED",
                "reason": f"invalid direction: {direction!r}",
            }
        distance = params.get("distance_m")
        if (
            not isinstance(distance, (int, float))
            or isinstance(distance, bool)
            or not 0 < distance <= MAX_DISTANCE_M
        ):
            return "REJECTED", {
                "command": command,
                "status": "REJECTED",
                "reason": f"distance_m must be a number with 0 < d <= {MAX_DISTANCE_M}",
            }
    if command == "TAKEOFF":
        # The altitude is optional (the edge's default height); when given it
        # must be a number within the envelope, never dropped or clamped.
        params = payload.get("parameters", {})
        if params is None:
            params = {}
        if not isinstance(params, dict):
            return _rejected(command, "TAKEOFF parameters must be an object")
        if "altitude_m" in params:
            altitude = params["altitude_m"]
            if not _is_number(altitude) or not MIN_TAKEOFF_ALTITUDE_M <= altitude <= MAX_TAKEOFF_ALTITUDE_M:
                return _rejected(
                    command,
                    f"altitude_m must be a number with {MIN_TAKEOFF_ALTITUDE_M} <= h <= {MAX_TAKEOFF_ALTITUDE_M}",
                )
    status = "EXECUTING"
    return status, {"command": command, "status": status}
