# Run the AeroNex drone simulation

This setup uses the existing **Ubuntu-24.04 WSL 2** installation. It runs an Iris quadcopter in **Gazebo Harmonic** with **ArduCopter SITL** and **MAVProxy**. No additional Linux distribution or Conda environment is required.

**Verified on 10 October 2026:** takeoff, 2 m forward movement, hold, landing, disarming, the Gazebo viewer, and Windows-to-WSL telemetry all passed. See the [measured build and test report](../testing/2026-10-10-simulation.md). **Re-checked on 14 October 2026 with app 0.3.0** ([0.3.0 test report](../testing/2026-10-14-v0.3.0.md)): the headless self-test and the emulator end-to-end run passed. In the JVM SITL suite, the Supervisor (11) and LinkSession (1) tests passed. `SessionControllerSitlTest` failed its extra wall-time bound by 3-6 ms in both T15 runs, with the simulator at 0.61-0.64x real time. A later re-run passed all 13. That bound is flaky on a slow simulator; the spec M2 latency target passed in every run. **Re-checked on 15 October 2026 with app 0.4.0** ([0.4.0 test report](../testing/2026-10-15-v0.4.0.md)): all 13 JVM SITL tests passed against headless Gazebo SITL (simulator at 0.68x real time), and the emulator end-to-end run passed (typed natural-phrasing commands, STOP mid-move → BRAKE, land). The simulator is installed and was stopped after testing.

## Start from Windows

Open PowerShell in `C:\Users\yugas\Downloads\Drone-app`:

```powershell
.\Start-Simulation.ps1
```

This starts the Gazebo world, ArduCopter, MAVProxy, and the Gazebo viewer. In an interactive terminal, MAVProxy accepts the commands below. The simulator starts on the ground; opening it does not arm or fly the drone.

To run without the viewer:

```powershell
.\Start-Simulation.ps1 -Headless
```

To stop, press **Ctrl+C** in the launch terminal, or run this from another PowerShell window in the same directory:

```powershell
.\Start-Simulation.ps1 -Stop
```

If the execution policy blocks the script (for example in a non-interactive run), call the launcher's Python program through WSL directly. Use `--headless` to start and `--stop` to stop; `--headless --self-test` runs the flight check below:

```powershell
wsl -d Ubuntu-24.04 --exec /home/drone/aeronex-sim/.venv/bin/python /mnt/c/Users/yugas/Downloads/Drone-app/.worktrees/aeronex-skeleton/tools/simulation/aeronex_sim.py --headless
```

Closing the Gazebo viewer normally also stops the simulation. The launcher stops only the processes it created. It checks for an existing simulation and occupied ports before starting.

## Run the automated flight check

Stop any running instance first, then run:

```powershell
.\Start-Simulation.ps1 -Headless -SelfTest
```

The check creates a fresh simulation, waits for the estimator, uses normal arming checks, takes off to **3 metres**, commands **2 metres forward**, holds in **BRAKE**, then lands and waits for disarming. It talks pymavlink through MAVProxy's UDP endpoint (0.3.0 removed the app's native codec; the app's Kotlin link is tested by the JVM SITL suite, `-Psitl=1`). All simulator processes stop when the check finishes, including on a failed check.

Omit `-Headless` to watch the automated flight in Gazebo. A successful run prints `PASS` and the path to `flight-check.json`. The test binds local UDP port **14551**, so close another local client using that port first.

## Fly manually in the MAVProxy terminal

Wait for `ArduPilot Ready` and the EKF/GPS readiness messages. Enter one command at a time:

```text
mode guided
arm throttle
takeoff 3
```

Wait until the altitude settles near 3 metres, then:

```text
mode brake
```

To land:

```text
mode land
```

Wait for landing and `Disarming motors` before closing the simulation. If arming is rejected, read the `PreArm` message and let initialization complete. Do not disable arming checks to work around an initialization error.

## Connect the Android app

The simulator uses these endpoints:

| Setting | Value |
| --- | --- |
| App transport | UDP |
| SITL UDP port | `14550` |
| Phone UDP listen port | `14551` |
| Vehicle system/component | `1 / 1` |
| Gazebo JSON physics port | `9002`, used internally |
| SITL/MAVProxy TCP port | `5760`, used internally |

**Which address to use:**

| Client | Drone address | Ports |
| --- | --- | --- |
| Android emulator on this PC | `10.0.2.2` | `14550` / `14551` |
| Phone on the same Wi-Fi | the PC's Wi-Fi IPv4 from `ipconfig`, e.g. `192.168.0.5` | `14550` / `14551` |
| Windows tools on this PC | `127.0.0.1` | `14550` |

This PC's `.wslconfig` (in the Windows user folder) sets `networkingMode=mirrored`, so the WSL listener is reachable on the PC's own addresses. A phone also needs Windows Firewall to allow inbound UDP 14550 and a Wi-Fi network without client isolation. See the [phone networking instructions](ARDUPILOT_GAZEBO_SETUP.md#10-connect-a-physical-android-phone-over-wi-fi). `netsh interface portproxy` is TCP-only and cannot help this UDP link.

MAVProxy listens using `udpin:0.0.0.0:14550`. The app's heartbeat teaches it the client's address, and replies come from port **14550**, as required by the app's strict source-port check. The automated test uses a loopback-only endpoint.

In the app (0.4.0):

1. **Menu > Link setup:** transport **UDP**, **Drone address**, **Drone port** `14550`, **Phone port** `14551`, then **Save & Connect**. The link is saved and reconnects at the next launch (in dry run). Leave "This link goes through a telemetry radio" off for the simulator.
2. The status line shows the mode, who owns it (this app or the pilot/autopilot) and the altitude. The flight service keeps the link, the 1 Hz heartbeat and STOP running while the screen is in the background.
3. Give a command: hold **Talk** (or Volume-Up) and speak, type it and tap **Send**, or use the tap pad. Example: "take off to 3 metres", then "go forward 5 meters and move right 3 meters". Number words and synonyms work too: "move two point five meters left", "climb two meters". The plan card lists the steps with a 15 s countdown. While the drone is on the ground a plan must start with a takeoff; while it is airborne a takeoff is rejected.
   - Words the rules do not understand go to the on-phone AI only on a 64-bit ARM phone with the AI models installed. The plan card then marks the plan **AI** and shows "Understood as: …", and it still needs CONFIRM.
   - On the emulator (x86_64) the app answers with the rules' message plus "AI is off: Needs a 64-bit ARM phone."
4. By default every plan is a **dry run** (**CONFIRM (dry run)**, steps report NOT SENT). Turn on **Fly the drone** in Link setup and accept the notice to send plans to the simulator; **CONFIRM** then flies the plan one step at a time. Each step reports COMPLETED, REJECTED, UNKNOWN or SKIPPED.
5. **STOP** (the big button, Volume-Down, the notification action, or a spoken "stop") sends BRAKE while the app owns the mode; the screen shows "STOP confirmed: BRAKE". If you switch modes in MAVProxy, the app treats the drone as pilot-controlled and STOP needs a 1 s hold.

Execution is a per-connection opt-in and is never saved. The app cannot verify that the peer is a simulator: use it only with an isolated ArduPilot SITL instance, never with a real aircraft. MAVProxy and the automated flight check remain independent ways to fly the simulator.

## Files and logs

The installed simulator lives inside the Linux filesystem at **`/home/drone/aeronex-sim`**. Source code is built there for better WSL filesystem performance.

| Location under `~/aeronex-sim` | Contents |
| --- | --- |
| `ardupilot/` | ArduCopter source and `build/sitl/bin/arducopter` |
| `ardupilot_gazebo/` | Official plugin source, compiled libraries, Iris models, and world |
| `.venv/` | Project Python environment, MAVProxy, and pymavlink |
| `codec-build/` | Leftover v0.2.0 native MAVLink check build; unused since 0.3.0 removed the native codec |
| `deps/libunwind/` | Private development files needed for the GStreamer build dependency |
| `logs/` | Installation and build logs |
| `runs/<timestamp>/` | A separate working directory, logs, flight state, and report for each run |
| `python-requirements.lock.txt` | Exact installed Python package versions |

Each run prints its log directory. Inspect `gazebo.log`, `arducopter.log`, `viewer.log`, and `flight-check.json` there. MAVProxy writes its own telemetry log under the run's `AeroNexIris` directory; noninteractive runs also have `mavproxy.log`.

The launcher is [tools/simulation/Start-Simulation.ps1](../../tools/simulation/Start-Simulation.ps1). The Windows workspace also has a forwarding `Start-Simulation.ps1`, so no long worktree path is needed for normal use.

## Rebuild the installed sources

Open the existing distro:

```powershell
wsl -d Ubuntu-24.04
```

Then run in Bash:

```bash
cd "$HOME/aeronex-sim"
source .venv/bin/activate
cd ardupilot
./waf configure --board sitl
./waf copter -j 4

cd ../ardupilot_gazebo
export GZ_VERSION=harmonic
export PKG_CONFIG_PATH="$HOME/aeronex-sim/deps/libunwind/usr/lib/x86_64-linux-gnu/pkgconfig${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}"
cmake -S . -B build -DCMAKE_BUILD_TYPE=RelWithDebInfo
cmake --build build --parallel 2
```

The local `libunwind` development files resolve the missing pkg-config metadata in the existing GStreamer/LLVM package combination. The original `libc++-20-dev` and `libunwind-20-dev` system packages are retained. The extra path applies only to this build shell.

The [full installation guide](ARDUPILOT_GAZEBO_SETUP.md) explains the components and installation on another machine. For this computer, use the launcher above; no reinstall is needed.

## Troubleshooting

- **Port already in use / simulation already running:** stop the existing instance with `-Stop`. The launcher deliberately does not attach the flight test to an existing MAVLink endpoint.
- **Viewer fails:** try `-Headless`. Check `viewer.log` and WSLg/GPU support before changing simulator settings.
- **Flight check fails:** read `flight-check.json` and the component logs. The launcher preserves the failed run and stops its processes.
- **Phone shows disconnected:** verify routing to WSL first, then ports `14550` / `14551`. `netsh interface portproxy` only forwards TCP and cannot fix this UDP connection.
- **No MAVProxy prompt:** launch from an interactive PowerShell terminal. Runs started with redirected input use noninteractive MAVProxy; the automated test still works.

## Refined task prompt

> Build and test an ArduPilot and Gazebo simulation for AeroNex using my existing Ubuntu-24.04 WSL 2 installation. Reuse Gazebo Harmonic and the installed dependencies. Build ArduCopter SITL and the official ArduPilot Gazebo plugin, provide one Windows launcher with an optional headless mode, and verify takeoff, movement, hold, landing, and the app's MAVLink telemetry route. Keep existing projects and the app's dry-run confirmation behavior intact. Save exact versions, logs, and measured test results, and explain any remaining phone-networking limitations.
