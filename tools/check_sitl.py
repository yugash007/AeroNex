"""Launch a disposable software simulator; never connect to existing hardware.

Run inside WSL: python3 tools/check_sitl.py /path/to/arducopter /path/to/copter.parm
This checks ArduPilot command compatibility with pymavlink only; the app's Kotlin link is covered by the
JVM SITL tests (-Psitl=1).
"""
import argparse
import subprocess
import tempfile
import time
from pathlib import Path
from pymavlink import mavutil


def check(binary, defaults):
    for path in (binary, defaults):
        if not Path(path).is_file():
            raise FileNotFoundError(path)
    with tempfile.TemporaryDirectory(prefix="aeronex-sitl-") as directory:
        log_path = Path(directory) / "sitl.log"
        with log_path.open("w+") as log:
            # Reserve a fresh loopback endpoint; never attach to existing hardware.
            link = mavutil.mavlink_connection("udpin:127.0.0.1:0", source_system=255, source_component=190)
            port = link.port.getsockname()[1]
            process = None
            try:
                process = subprocess.Popen([
                    binary, "--model", "quad", "--instance", "23", "--speedup", "1",
                    "--defaults", defaults, "--serial0", f"udpclient:127.0.0.1:{port}",
                    "--home", "35.363,-117.165,600,0",
                ], cwd=directory, stdout=log, stderr=subprocess.STDOUT)
                heartbeat = link.wait_heartbeat(timeout=30)
                assert heartbeat is not None, "No simulator heartbeat"
                assert heartbeat.autopilot == 3 and heartbeat.type == 2, heartbeat
                print("Simulator heartbeat verified", flush=True)
                status_text = []
                next_heartbeat = 0

                def heartbeat_send():
                    link.mav.heartbeat_send(6, 8, 0, 0, 4)

                def receive(timeout=1):
                    nonlocal next_heartbeat
                    if time.monotonic() >= next_heartbeat:
                        heartbeat_send()
                        next_heartbeat = time.monotonic() + 1
                    msg = link.recv_match(blocking=True, timeout=timeout)
                    if msg and (msg.get_srcSystem() != 1 or msg.get_srcComponent() != 1):
                        return None
                    if msg and msg.get_type() == 'STATUSTEXT':
                        status_text.append(msg.text)
                        print('SITL:', msg.text, flush=True)
                    return msg
                def command(command_id, params):
                    link.mav.command_long_send(1, 1, command_id, 0, *params)
                    deadline = time.monotonic() + 8
                    while time.monotonic() < deadline:
                        ack = receive()
                        if (ack and ack.get_type() == 'COMMAND_ACK' and ack.command == command_id
                                and getattr(ack, 'target_system', 0) in (0, 255)
                                and getattr(ack, 'target_component', 0) in (0, 190)):
                            if ack.result == 5:  # in progress is not a final ACK
                                continue
                            if ack.result != 0:
                                until = time.monotonic() + 2
                                while time.monotonic() < until:
                                    receive(timeout=0.1)
                                raise AssertionError(f"Command {command_id} rejected: {ack}; recent status: {status_text[-12:]}")
                            return
                    raise AssertionError(f"Missing ACK for {command_id}")
                for msg_id in [1, 30, 32, 33, 193, 245]:
                    command(511, [msg_id, 200000, 0, 0, 0, 0, 0])
                print("Telemetry stream requests acknowledged", flush=True)
                # Wait for actual EKF readiness, not an assumed startup duration.
                deadline = time.monotonic() + 120
                while time.monotonic() < deadline:
                    msg = receive(timeout=0.1)
                    if msg and msg.get_type() == 'EKF_STATUS_REPORT':
                        required = 1 | 2 | 16 | 32  # attitude, horizontal velocity/position, vertical position
                        flags = msg.flags
                        if flags & required == required and not flags & (128 | 1024):
                            break
                else:
                    raise AssertionError(f'Position estimator not ready: {status_text[-8:]}')
                command(176, [1, 4, 0, 0, 0, 0, 0])
                command(400, [1, 0, 0, 0, 0, 0, 0])
                command(22, [0, 0, 0, 0, 0, 0, 3])
                deadline = time.monotonic() + 30
                while time.monotonic() < deadline:
                    msg = receive(timeout=0.1)
                    if msg and msg.get_type() == 'GLOBAL_POSITION_INT' and msg.relative_alt >= 2600:
                        break
                else:
                    raise AssertionError("Takeoff altitude not confirmed")
                print("Takeoff altitude confirmed", flush=True)
                command(176, [1, 9, 0, 0, 0, 0, 0])
                print("Takeoff telemetry reached 3 m tolerance; land mode acknowledged", flush=True)
            finally:
                if process is not None and process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()
                link.close()
                log.seek(0)
                print(log.read()[-5000:])


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("binary")
    parser.add_argument("defaults")
    args = parser.parse_args()
    check(str(Path(args.binary).resolve()), str(Path(args.defaults).resolve()))
