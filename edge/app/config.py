"""Edge server configuration (single source of truth for host/port/identity)."""

from __future__ import annotations

import os

from edge.app.protocol import VERSION as PROTOCOL_VERSION

# Loopback by default: anything that can reach this port can submit commands.
# To serve phones on the LAN, bind 0.0.0.0 AND set TOKEN_ENV (see README).
HOST = "127.0.0.1"
PORT = 8000
WS_PATH = "/ws"

# A shared secret every client must present (``?token=`` or
# ``Authorization: Bearer``) once it is set. Without it only loopback and
# in-process peers are served.
TOKEN_ENV = "AERONEX_EDGE_TOKEN"
# Comma-separated browser origins allowed to connect. A native client sends no
# Origin header; a browser always does, so by default every page is refused.
ORIGINS_ENV = "AERONEX_EDGE_ORIGINS"

# Peers served without a token: loopback, plus the Starlette TestClient's
# in-process peer name (never a network address).
LOCAL_PEERS = frozenset({"127.0.0.1", "::1", "localhost", "testclient"})

# A text frame larger than this is refused before it is parsed, so one client
# cannot stall the event loop for every other (uvicorn allows 16 MiB).
MAX_MESSAGE_CHARS = 64 * 1024

# mDNS service type reserved for discovery.py (constants only: nothing
# advertises or browses it yet, so connect by IP).
SERVICE_TYPE = "_aeronex-edge._tcp.local."

# Onboard-AI status reported in STATUS replies. No AI engine exists yet,
# so the edge honestly reports a stub value.
AI_STATUS = "STUB"

EDGE_READY = "READY"


def token_from_env() -> str | None:
    """The configured client token, or None when unset or blank."""
    value = os.environ.get(TOKEN_ENV, "").strip()
    return value or None


def origins_from_env() -> frozenset[str]:
    """The allow-listed browser origins (none by default)."""
    return frozenset(o.strip() for o in os.environ.get(ORIGINS_ENV, "").split(",") if o.strip())
