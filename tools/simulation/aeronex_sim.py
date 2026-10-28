#!/usr/bin/env python3
"""Run an owned Gazebo + ArduCopter simulation in the existing WSL distro."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import time


ROOT = Path.home() / "aeronex-sim"
STATE = ROOT / "running.json"


def process_start(pid):
    # Ignore spaces in the parenthesized process name before reading field 22.
    return Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()[19]


def stop():
    if not STATE.exists():
        print("AeroNex simulation is stopped.")
        return
    with (ROOT / "run.lock").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            pass
        else:
            # No launcher holds the lock, so the record is stale (its PID may belong to anything now).
            STATE.unlink(missing_ok=True)
            print("No AeroNex launcher is running; removed the old status file.")
            return
    state = json.loads(STATE.read_text())
    pid = state["pid"]
    try:
        if process_start(pid) != state["start"]:
            raise RuntimeError("Stale PID record; no process was stopped.")
        command = Path(f"/proc/{pid}/cmdline").read_bytes().split(b"\0")
        if os.fsencode(str(Path(__file__).resolve())) not in command:
            raise RuntimeError("PID does not belong to this launcher; no process was stopped.")
        os.kill(pid, signal.SIGINT)
    except FileNotFoundError:
        print("AeroNex simulation is stopped (old status file).")
        return
    deadline = time.monotonic() + 20
    while STATE.exists() and time.monotonic() < deadline:
        time.sleep(0.2)
    if STATE.exists():
        raise RuntimeError("The launcher has not finished stopping. Check its terminal and logs.")
    print("AeroNex simulation stopped.")


def require_free_ports():
    for port, kind in [(9002, socket.SOCK_DGRAM), (5760, socket.SOCK_STREAM),
                       (14550, socket.SOCK_DGRAM)]:
        with socket.socket(socket.AF_INET, kind) as probe:
            if kind == socket.SOCK_STREAM:
                # TIME_WAIT from the previous run must not block a restart; a live listener still does.
                probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:
                probe.bind(("0.0.0.0", port))
            except OSError as error:
                raise RuntimeError(f"Port {port} is already in use. Stop the other simulator first.") from error


def port_ready(port, protocol):
    for line in Path(f"/proc/net/{protocol}").read_text().splitlines()[1:]:
        fields = line.split()
        if int(fields[1].split(":")[1], 16) == port:
            if protocol == "udp" or fields[3] == "0A":
                return True
    return False


def terminate(processes, seconds):
    for process in processes:
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
    deadline = time.monotonic() + seconds
    for process in processes:
        try:
            process.wait(timeout=max(0.1, deadline - time.monotonic()))
        except subprocess.TimeoutExpired:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait()


def wsl_gpu_env():
    """On WSL, render Gazebo on the GPU through Mesa's D3D12 driver (preferring an NVIDIA adapter).

    Without this, WSLg's Mesa falls back to llvmpipe (CPU): the Gazebo window stays blank ("[WARN:COPY MODE]") and the
    server burns CPU rendering the camera. Variables already set by the caller win.
    """
    if not os.path.exists("/dev/dxg"):
        return {}
    env = {"GALLIUM_DRIVER": "d3d12"}
    if os.path.exists("/usr/lib/wsl/lib/libcuda.so.1"):
        env["MESA_D3D12_DEFAULT_ADAPTER_NAME"] = "NVIDIA"
    return env


class Simulation:
    def __init__(self, headless, testing):
        self.headless, self.testing = headless, testing
        self.children, self.logs = [], []
        self.directory = ROOT / "runs" / time.strftime("%Y%m%d-%H%M%S")
        self.env = dict(os.environ)
        self.env.update({
            "GZ_VERSION": "harmonic", "GZ_PARTITION": f"aeronex-sim-{os.getuid()}",
            "GZ_SIM_SYSTEM_PLUGIN_PATH": str(ROOT / "ardupilot_gazebo/build"),
            "GZ_SIM_RESOURCE_PATH": ":".join(str(ROOT / "ardupilot_gazebo" / p) for p in ("models", "worlds")),
            "PATH": str(ROOT / ".venv/bin") + os.pathsep + os.environ["PATH"],
        })
        for key, value in wsl_gpu_env().items():
            self.env.setdefault(key, value)

    def launch(self, name, command, interactive=False):
        log = (self.directory / f"{name}.log").open("w")
        self.logs.append(log)
        process = subprocess.Popen(command, cwd=self.directory, env=self.env,
                                   stdin=None if interactive else subprocess.DEVNULL,
                                   stdout=None if interactive else log,
                                   stderr=None if interactive else subprocess.STDOUT,
                                   start_new_session=True)
        self.children.append((name, process))

    def check(self):
        for name, process in self.children:
            if process.poll() is not None:
                if name == "viewer" and process.returncode == 0 and not self.testing:
                    raise KeyboardInterrupt
                raise RuntimeError(f"{name} exited ({process.returncode}). See {self.directory}/{name}.log")

    def wait_port(self, port, protocol):
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            self.check()
            if port_ready(port, protocol):
                return
            time.sleep(0.2)
        raise TimeoutError(f"Simulator port {port} did not start. Logs: {self.directory}")

    def start(self):
        plugin = ROOT / "ardupilot_gazebo"
        ap = ROOT / "ardupilot"
        for path in [ap / "build/sitl/bin/arducopter", plugin / "build/libArduPilotPlugin.so",
                     ROOT / ".venv/bin/mavproxy.py"]:
            if not path.is_file():
                raise FileNotFoundError(f"Missing {path}. Run the installation/build steps first.")
        require_free_ports()
        self.directory.mkdir(parents=True)
        print(f"Logs: {self.directory}", flush=True)
        self.launch("gazebo", ["gz", "sim", "-s", "-r", "-v", "3", str(plugin / "worlds/iris_runway.sdf")])
        self.wait_port(9002, "udp")
        defaults = ",".join(str(ap / "Tools/autotest/default_params" / p)
                            for p in ("copter.parm", "gazebo-iris.parm"))
        self.launch("arducopter", [str(ap / "build/sitl/bin/arducopter"), "-S", "-I0",
                                  "--model", "JSON", "--speedup", "1", "--defaults", defaults,
                                  "--sim-address=127.0.0.1", "--home=-35.363261,149.165230,584,353"])
        self.wait_port(5760, "tcp")
        bind = "127.0.0.1" if self.testing else "0.0.0.0"
        proxy = [str(ROOT / ".venv/bin/mavproxy.py"), "--master=tcp:127.0.0.1:5760",
                 f"--out=udpin:{bind}:14550", "--source-system=255", "--target-system=1",
                 "--target-component=1", "--streamrate=5", "--aircraft=AeroNexIris",
                 "--default-modules=log,wp,rally,fence,param,arm,mode,rc,cmdlong,output"]
        if self.testing or not sys.stdin.isatty():
            proxy.append("--non-interactive")
        self.launch("mavproxy", proxy, interactive=not self.testing and sys.stdin.isatty())
        self.wait_port(14550, "udp")
        if not self.headless:
            # Ogre2 draws a blank window under WSLg; the older Ogre 1 engine renders there. AERONEX_GUI_RENDER overrides it.
            engine = os.environ.get("AERONEX_GUI_RENDER", "ogre" if os.path.exists("/dev/dxg") else "ogre2")
            self.launch("viewer", ["gz", "sim", "-g", "-v", "2", "--render-engine-gui", engine])
        state = {"pid": os.getpid(), "start": process_start(os.getpid()),
                 "logs": str(self.directory), "headless": self.headless,
                 "processes": {name: process.pid for name, process in self.children}}
        STATE.write_text(json.dumps(state, indent=2) + "\n")
        print("Gazebo + ArduCopter started. MAVLink UDP port: 14550. Vehicle: 1/1.", flush=True)
        print("Press Ctrl+C or run the launcher with -Stop to stop these processes.", flush=True)
        if not self.testing:
            print("MAVProxy: mode guided | arm throttle | takeoff 3 | mode brake | mode land", flush=True)

    def close(self):
        # A second Ctrl+C or a hangup must not cut cleanup short and orphan ArduCopter.
        for signum in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
            signal.signal(signum, signal.SIG_IGN)
        try:
            # Stop only processes started by this launcher, including their children.
            # Gazebo goes last: ArduCopter only sees SIGTERM while physics packets still arrive.
            terminate([process for name, process in reversed(self.children) if name != "gazebo"], 5)
            terminate([process for name, process in self.children if name == "gazebo"], 3)
        finally:
            for log in self.logs:
                log.close()
            if STATE.exists() and json.loads(STATE.read_text()).get("pid") == os.getpid():
                STATE.unlink()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--headless", action="store_true", help="Skip the Gazebo viewer")
    parser.add_argument("--self-test", action="store_true", help="Launch a fresh simulator, fly a test, then stop")
    parser.add_argument("--stop", action="store_true", help="Stop this launcher's simulator")
    args = parser.parse_args()
    if args.stop:
        stop()
        return
    if not ROOT.is_dir():
        raise FileNotFoundError(f"Simulator is not installed at {ROOT}")
    def interrupted(signum, frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGHUP, interrupted)
    with (ROOT / "run.lock").open("w") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RuntimeError("AeroNex simulation is already running. Use -Stop first.") from None
        sim = Simulation(args.headless, args.self_test)
        try:
            sim.start()
            if args.self_test:
                from check_gazebo import check
                check(sim)
            else:
                while True:
                    sim.check()
                    time.sleep(0.5)
        except KeyboardInterrupt:
            print("Stopping AeroNex simulation...", flush=True)
            if args.self_test:
                raise SystemExit(130)
        finally:
            sim.close()


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr, flush=True)
        sys.exit(1)
