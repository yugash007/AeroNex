"""mDNS discovery advertisement (stub: constants only, no network bind).

Advertises the edge under SERVICE_TYPE. If mDNS does not resolve on the
phone (some Android/congested subnet cases), fall back to a manual-IP
connect — see edge/README.md.
"""

from edge.app import config

SERVICE_TYPE = config.SERVICE_TYPE
SERVICE_NAME = "aeronex-edge"


def get_service_info(host_ip: str, port: int = config.PORT) -> dict:
    """Describe the advertised service without touching the network."""
    return {
        "type": SERVICE_TYPE,
        "name": SERVICE_NAME,
        "host": host_ip,
        "port": port,
    }
