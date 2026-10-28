"""Edge server configuration (single source of truth for host/port/identity)."""

from edge.app.protocol import VERSION as PROTOCOL_VERSION

HOST = "0.0.0.0"
PORT = 8000
WS_PATH = "/ws"

# mDNS service type advertised by discovery.py (stub: no network bind).
SERVICE_TYPE = "_aeronex-edge._tcp.local."

# Onboard-AI status reported in STATUS replies. No AI engine exists yet,
# so the edge honestly reports a stub value.
AI_STATUS = "STUB"

EDGE_READY = "READY"
