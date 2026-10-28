"""Flight/UDP integration check, called only after launching our own simulator."""
from collections import deque
import json
import math
import socket
import time

from pymavlink.dialects.v20 import ardupilotmega


def telemetry_of(message):
    """The fields the flight check reads (as the removed C codec decoded them), or None."""
    kind = message.get_type()
    if kind == "HEARTBEAT":
        return {"mode": message.custom_mode, "armed": bool(message.base_mode & 128),
                "autopilot": message.autopilot, "type": message.type, "status": message.system_status}
    if kind == "LOCAL_POSITION_NED":
        return {"x": message.x, "y": message.y, "z": message.z,
                "speed": math.sqrt(message.vx ** 2 + message.vy ** 2 + message.vz ** 2)}
    if kind == "ATTITUDE":
        return {"yaw": message.yaw}
    if kind == "GLOBAL_POSITION_INT":
        return {"altitude": message.relative_alt / 1000.0}
    if kind == "EXTENDED_SYS_STATE":
        return {"landed": message.landed_state}
    if kind == "EKF_STATUS_REPORT":
        return {"ekfFlags": message.flags & 0xFFFF}
    if kind == "SYS_STATUS":
        enabled = message.onboard_control_sensors_enabled
        return {"battery": message.battery_remaining,
                "healthy": (enabled & message.onboard_control_sensors_health) == enabled}
    return None


def check(sim):
    report = {"result": "FAIL", "endpoint": "127.0.0.1:14550", "client_port": 14551,
              "vehicle": "1/1", "checks": [], "max_altitude_m": 0.0}
    telemetry, received, status, pending = {}, {}, [], deque()
    parser = ardupilotmega.MAVLink(None)
    parser.robust_parsing = True
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    next_heartbeat = 0.0
    peer = ("127.0.0.1", 14550)

    class Peer:
        @staticmethod
        def write(data):
            sock.sendto(data, peer)

    mav = ardupilotmega.MAVLink(Peer(), srcSystem=255, srcComponent=190)

    def receive():
        nonlocal next_heartbeat
        sim.check()
        now = time.monotonic()
        if now >= next_heartbeat:
            mav.heartbeat_send(6, 8, 0, 0, 4)  # GCS, no autopilot, active
            next_heartbeat = now + 1
        if not pending:
            try:
                raw, address = sock.recvfrom(65535)
            except socket.timeout:
                return None
            if address != peer:
                raise AssertionError(f"MAVLink source differs from the app's required peer: {address}")
            messages = parser.parse_buffer(raw) or []
            for message in messages:
                fields = telemetry_of(message)
                if fields is not None and message.get_srcSystem() == 1 and message.get_srcComponent() == 1:
                    telemetry.update(fields)
                    received[message.get_msgId()] = time.monotonic()
            pending.extend(messages)
        if not pending:
            return None
        message = pending.popleft()
        if message.get_srcSystem() != 1 or message.get_srcComponent() != 1:
            return None
        if message.get_type() == "STATUSTEXT":
            status.append(message.text)
            print("SITL:", message.text, flush=True)
        report["max_altitude_m"] = max(report["max_altitude_m"], telemetry.get("altitude", 0))
        return message

    def wait_for(description, predicate, ids, timeout=60):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            receive()
            if all(time.monotonic() - received.get(i, 0) < 3 for i in ids) and predicate():
                report["checks"].append(description)
                print("PASS:", description, flush=True)
                return
        raise AssertionError(f"{description} timed out. Status: {status[-10:]}; telemetry: {telemetry}")

    def command(command_id, params):
        mav.command_long_send(1, 1, command_id, 0, *params)
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            message = receive()
            if (message and message.get_type() == "COMMAND_ACK" and message.command == command_id
                    and message.target_system in (0, 255) and message.target_component in (0, 190)):
                if message.result == 5:
                    continue
                if message.result != 0:
                    raise AssertionError(f"Command {command_id} rejected: {message}; status: {status[-10:]}")
                return
        raise AssertionError(f"Command {command_id} did not receive a final ACK")

    started = time.monotonic()
    try:
        sock.bind(("127.0.0.1", 14551))
        sock.settimeout(0.2)
        wait_for("ArduCopter quadrotor heartbeat through fixed UDP port 14550",
                 lambda: telemetry.get("autopilot") == 3 and telemetry.get("type") == 2, [0])
        for message_id in [1, 30, 32, 33, 193, 245]:
            command(511, [message_id, 200000, 0, 0, 0, 0, 0])
        report["checks"].append("All six telemetry requests acknowledged")
        wait_for("Position estimator ready", lambda: telemetry.get("ekfFlags", 0) & 51 == 51
                 and not telemetry.get("ekfFlags", 0) & (128 | 1024), [193], timeout=150)
        # Initial RC setup can reset flight mode during boot. Wait for the full
        # prearm/sensor state before changing mode, rather than an early EKF flag.
        wait_for("Startup sensors and prearm checks healthy", lambda:
                 telemetry.get("healthy") is True, [1], timeout=150)
        command(176, [1, 4, 0, 0, 0, 0, 0])
        # EKF flags can appear before GPS, compass and the prearm checks settle.
        # SYS_STATUS includes ArduPilot's prearm-check health bit.
        wait_for("GUIDED prearm checks and sensors healthy", lambda:
                 telemetry.get("healthy") is True and telemetry.get("mode") == 4, [0, 1], timeout=150)
        # Ordinary arming, without force-arm or disabling prearm checks.
        command(400, [1, 0, 0, 0, 0, 0, 0])
        command(22, [0, 0, 0, 0, 0, 0, 3])
        wait_for("Takeoff reached 3 m tolerance", lambda: 2.6 <= telemetry.get("altitude", 0) <= 3.6, [33])
        wait_for("All app telemetry streams decoded with healthy sensors", lambda:
                 telemetry.get("landed") == 2 and telemetry.get("healthy") is True,
                 [0, 1, 30, 32, 33, 193, 245])
        yaw = telemetry["yaw"]
        target = [telemetry["x"] + 2 * math.cos(yaw), telemetry["y"] + 2 * math.sin(yaw), telemetry["z"]]
        # LOCAL_NED (1), position only (type_mask 3576), as the removed C codec sent it.
        mav.set_position_target_local_ned_send(0, 1, 1, 1, 3576, *target, 0, 0, 0, 0, 0, 0, 0, 0)
        wait_for("Forward movement completed (2 m command)", lambda:
                 math.dist([telemetry["x"], telemetry["y"], telemetry["z"]], target) < 0.5
                 and telemetry["speed"] < 0.3, [32])
        report["position_error_m"] = math.dist([telemetry["x"], telemetry["y"], telemetry["z"]], target)
        command(176, [1, 17, 0, 0, 0, 0, 0])
        wait_for("BRAKE hold confirmed", lambda: telemetry.get("mode") == 17 and telemetry["speed"] < 0.3, [0, 32])
        command(176, [1, 9, 0, 0, 0, 0, 0])
        wait_for("Landing completed and motors disarmed", lambda:
                 telemetry.get("landed") == 1 and telemetry.get("armed") is False, [0, 245], timeout=90)
        report["result"] = "PASS"
    except Exception as error:
        report["error"] = str(error)
        raise
    finally:
        report["duration_seconds"] = round(time.monotonic() - started, 2)
        report["final_telemetry"] = telemetry
        report["recent_status"] = status[-20:]
        (sim.directory / "flight-check.json").write_text(json.dumps(report, indent=2) + "\n")
        sock.close()
    print(f"PASS: Gazebo physics / pymavlink / MAVProxy flight check. Report: {sim.directory}/flight-check.json", flush=True)
