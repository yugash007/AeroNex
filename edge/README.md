# AeroNex Edge

Drone-side WebSocket endpoint the mobile app talks to. No internet, no
cloud, no MAVLink — receipt ACKs and lifecycle state only.

## Run

```bash
pip install -r requirements.txt
uvicorn edge.app.server:app --host 0.0.0.0 --port 8000
```

`requirements.txt` also pins `httpx` and `pytest`, used only by the tests
(`python -m pytest edge/tests -q` from the repo root) and
`demo_client.run_in_process`; the live demo CLI does not need them.

## Connect

WebSocket URL: `ws://<edge-ip>:8000/ws`

## Manual-IP connect (fallback when mDNS fails)

1. On the companion computer, find its LAN IP (`ip addr` / `ifconfig`).
2. On the phone, join the same Wi-Fi/LAN as the edge.
3. In the app, enter `ws://<edge-ip>:8000/ws` directly instead of
   discovering `_aeronex-edge._tcp.local`.
4. Send `PING`, expect `PONG`.

## Protocol sketch

- `PING` → `PONG`
- `COMMAND` → `ACK` (receipt only) then `COMMAND_STATUS`
- Re-sending a seen `message_id` returns the existing state (never re-executes);
  the edge remembers the 4096 most recently used IDs, each at most 128 chars
- `STATUS_REQUEST` → `STATUS` (`edge: READY`)
- `CANCEL` → `COMMAND_STATUS` (`CANCELLED`); a command already `REJECTED`
  (or `COMPLETED`/`FAILED`) keeps its verdict and the `CANCEL` gets `ERROR`
- Malformed / unsupported (including binary frames) → `ERROR`
