"""FastAPI app exposing the edge WebSocket endpoint at /ws."""

from __future__ import annotations

import time

from fastapi import FastAPI, WebSocket

from edge.app import config
from edge.app.state import CommandRegistry
from edge.app.websocket import AccessPolicy, handle_client


def create_app(policy: AccessPolicy | None = None) -> FastAPI:
    """Build an app with fresh per-instance state (restart = new state).

    ``policy`` defaults to the environment (config.TOKEN_ENV, config.ORIGINS_ENV).
    """
    app = FastAPI(title="AeroNex Edge")
    app.state.registry = CommandRegistry()
    app.state.start_time = time.monotonic()
    app.state.policy = policy or AccessPolicy(config.token_from_env(), config.origins_from_env())

    @app.websocket(config.WS_PATH)
    async def ws_endpoint(ws: WebSocket) -> None:
        await handle_client(ws, app.state.registry, app.state.start_time, app.state.policy)

    return app


app = create_app()
