"""FastAPI app exposing the edge WebSocket endpoint at /ws."""

from __future__ import annotations

import time

from fastapi import FastAPI, WebSocket

from edge.app import config
from edge.app.state import CommandRegistry
from edge.app.websocket import handle_client


def create_app() -> FastAPI:
    """Build an app with fresh per-instance state (restart = new state)."""
    app = FastAPI(title="AeroNex Edge")
    app.state.registry = CommandRegistry()
    app.state.start_time = time.monotonic()

    @app.websocket(config.WS_PATH)
    async def ws_endpoint(ws: WebSocket) -> None:
        await handle_client(ws, app.state.registry, app.state.start_time)

    return app


app = create_app()
