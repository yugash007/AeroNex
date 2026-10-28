# AeroNex Edge

Drone-side WebSocket endpoint prototype. No internet, no cloud, no MAVLink —
receipt ACKs and lifecycle state only. The shipping Android app does not use
it: the phone talks MAVLink to ArduPilot directly (see the top-level README).

## Run

From the repository root:

```bash
pip install -r edge/requirements.txt
uvicorn edge.app.server:app --host 127.0.0.1 --port 8000
```

Anything that can reach the port can submit commands, so the edge is
locked down by default:

- Only loopback clients are served unless a token is set. To serve phones on
  the LAN, bind all interfaces **and** set a shared secret:

  ```bash
  AERONEX_EDGE_TOKEN=$(python -c "import secrets; print(secrets.token_urlsafe(24))") \
    uvicorn edge.app.server:app --host 0.0.0.0 --port 8000 --ws-max-size 65536
  ```

  Clients send it as `ws://<edge-ip>:8000/ws?token=<secret>` or an
  `Authorization: Bearer <secret>` header. Plain `ws://` is not encrypted:
  use it on a closed network only.
- Browser pages are refused (they send an `Origin` header) unless their origin
  is listed in `AERONEX_EDGE_ORIGINS` (comma-separated).
- Text frames over 64 KiB are refused before parsing.
- Each client has its own command-ID namespace: a client can retry or cancel
  only its own commands.
- Limits follow the app's safety envelope: MOVE 0 < d ≤ 10 m, TAKEOFF
  `altitude_m` (optional) 1–10 m.

`requirements.txt` also pins `httpx` and `pytest`, used only by the tests
(`python -m pytest edge/tests -q` from the repo root) and
`demo_client.run_in_process`; the live demo CLI does not need them.

## Connect

WebSocket URL: `ws://<edge-ip>:8000/ws`

## Connect by IP

mDNS discovery is not implemented: `discovery.py` only reserves the service
type `_aeronex-edge._tcp.local`. Connect by address:

1. On the companion computer, find its LAN IP (`ip addr` / `ifconfig`).
2. On the client, join the same Wi-Fi/LAN as the edge.
3. Open `ws://<edge-ip>:8000/ws?token=<secret>`.
4. Send `PING`, expect `PONG`.

## Protocol sketch

- `PING` → `PONG`
- `COMMAND` → `ACK` (receipt only) then `COMMAND_STATUS`
- Re-sending a seen `message_id` returns the existing state (never re-executes);
  the edge remembers each client's 4096 most recently used IDs, each at most
  128 chars
- `STATUS_REQUEST` → `STATUS` (`edge: READY`)
- `CANCEL` → `COMMAND_STATUS` (`CANCELLED`); a command already `REJECTED`
  (or `COMPLETED`/`FAILED`) keeps its verdict and the `CANCEL` gets `ERROR`
- Malformed / unsupported (including binary frames) → `ERROR`
