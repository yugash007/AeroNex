# ArduPilot SITL + Gazebo setup and testing for AeroNex

Prepared: **10 October 2026**. App sections updated **11 October 2026** for **AeroNex 0.2.0+11**.

**Update:** the simulator has now been built and tested in the existing Ubuntu WSL installation. Use the [one-command quick start](RUN_SIMULATION.md) on this computer and see the [measured verification report](../testing/2026-10-10-simulation.md). The inventory and document-only validation notes below describe the earlier guide-preparation stage.

This guide sets up an Iris quadcopter in **Gazebo Harmonic**, runs **ArduCopter SITL**, and connects the Android app to its MAVLink telemetry. It includes installation commands, a flight check through MAVProxy, app checks, and troubleshooting.

**Current app behavior (0.2.0):** the app can connect to simulator telemetry, recognize speech, review text, and turn a command of up to 5 steps into a plan, parsed by rules first with local AI only as a fallback. **By default every confirmation is a dry run that reports NOT SENT, including when connected to SITL.** Confirmed plans reach the Gazebo drone only after you turn on **Execute confirmed commands in simulator** for the current SITL connection ([section 10E](#e-dry-run-simulator-execution-and-multi-step-plans)). Execution is a simulator profile only: the app cannot verify that a peer is a simulator, and it is not tested or intended for a real aircraft. The manual MAVProxy commands below control the simulated drone and let you validate the simulator independently of the app.

## 1. Refined version of your prompt

> Create a practical Markdown installation and testing guide for my AeroNex Android app using ArduPilot and Gazebo. Use ArduCopter SITL to simulate the flight controller and Gazebo to simulate an Iris quadcopter and its environment. My computer runs Windows with WSL 2 and Ubuntu 24.04. Prefer Gazebo Harmonic and the official ArduPilot Gazebo plugin. Inspect existing installations and explain which steps can be skipped.
>
> Include commands for installing dependencies, building ArduPilot and the plugin, configuring environment variables, starting Gazebo and SITL, and testing takeoff, hold, and landing through MAVProxy. Explain how to connect a physical Android phone over the local network using the app's actual IPv4 and UDP-port settings. Account for WSL networking, firewall rules, and the app's strict source-IP/source-port checks.
>
> Include a test checklist for telemetry, Telugu and Indian English speech, transcript review, model readiness, and errors. Clearly separate simulator movement, app telemetry, speech recognition, and AI interpretation. Preserve the app's current dry-run behavior and identify what would need separate work for future simulation-only command execution. Include expected results, troubleshooting, logs to collect, and official references. Do not connect to a physical aircraft.

The terminology matters:

| Term | Meaning here |
| --- | --- |
| **ArduPilot** | The autopilot software project. |
| **ArduCopter** | Its firmware for copters; use this for the Iris quadcopter. |
| **Flight-controller board** | Physical hardware such as a Pixhawk or Cube. A board is unnecessary for SITL. |
| **SITL** | Software In The Loop: the flight-controller software runs on the computer. |
| **Gazebo** | Simulates the drone, physics, sensors, and world. |
| **ArduPilot Gazebo plugin** | Exchanges physics/sensor and actuator data between Gazebo and SITL. |
| **MAVProxy** | A ground-control console and MAVLink router between SITL and the Android app. |

## 2. What is already present on your computer

Read-only checks made while preparing this document found:

| Component | Observed status | What to do |
| --- | --- | --- |
| WSL | Version 2.7.10; Ubuntu distribution uses WSL 2 | Reuse it. |
| Linux distribution | `Ubuntu-24.04`, reporting Ubuntu 24.04.4 LTS | Reuse it. |
| Gazebo | `gz sim --versions` reports **8.12.0** | Harmonic is already installed. |
| Harmonic packages | `gz-harmonic` and `libgz-sim8-dev` are installed | Skip their installation if the checks below still pass. |
| Earlier ArduCopter binary | Present at `C:\Users\yugas\Downloads\AeroNex_One\ardupilot\build\sitl\bin\arducopter` | This proves the file exists; its compatibility with this Gazebo setup was not retested. |
| Earlier Python environment | Importing `pymavlink` using `/home/drone/venv-ardupilot/bin/python` failed | Verify dependencies; the guide creates a project-specific environment. |
| WSL network configuration | No explicit `networkingMode` setting was found | Follow the phone-networking section before connecting over Wi-Fi. |

The installation recipe below uses **`~/aeronex-sim` inside Linux**. This keeps source builds in the Linux filesystem and gives this simulation its own directories. Existing app models and earlier simulator directories are preserved.

Ubuntu 24.04 + Harmonic is supported by Gazebo's binary packages. The plugin currently has a Harmonic build path using `gz-sim8`; the official ArduPilot examples use `gazebo-iris` with the `JSON` model. Harmonic is an LTS release; this guide deliberately uses that version consistently.

**Validation scope:** official instructions and relevant project/upstream source were checked. Gazebo's installed version was queried. This document-creation task did **not** build a new simulator, launch the Gazebo world, or verify the complete phone connection. Checkpoints below are expected results to verify as you run the guide.

## 3. Understand the connection

```mermaid
flowchart LR
    P[Android app] <-->|MAVLink over UDP| M[MAVProxy]
    M <-->|MAVLink over TCP 5760| S[ArduCopter SITL]
    S <-->|JSON physics interface| G[ArduPilot Gazebo plugin]
    G <--> W[Gazebo Iris world]
```

| Link | Endpoint used in this guide |
| --- | --- |
| Gazebo plugin ↔ SITL | Local JSON simulation interface; the bundled Iris plugin listens on UDP **9002** for instance 0. Keep the supplied model configuration. |
| SITL → MAVProxy | Local TCP **5760**, configured automatically by `sim_vehicle.py`. |
| Android app → MAVProxy | Computer's reachable IPv4 address, UDP **14550**. |
| MAVProxy → Android app | Replies from UDP **14550** to the endpoint learned from the app's first heartbeat; the app binds UDP **14551**. |
| Vehicle identity | ArduCopter quadrotor, system **1**, autopilot component **1**. |

Ports 9002 and 5760 stay between programs on the computer. The phone uses MAVLink on port 14550; it does not connect to Gazebo's physics port.

## 4. Open Ubuntu and verify WSL graphics

### On this computer — PowerShell

```powershell
wsl --list --verbose
wsl --version
wsl -d Ubuntu-24.04
```

The last command opens an Ubuntu shell. Run subsequent **Bash** blocks there, unless a section explicitly says PowerShell or MAVProxy.

### Only on a computer without WSL — administrator PowerShell

```powershell
wsl --install -d Ubuntu-24.04
wsl --update
```

Restart Windows if requested and finish creating the Linux user. Use WSL 2. Windows 11 with WSLg is the recommended Windows path for the Gazebo GUI and mirrored networking.

### Ubuntu / Bash

```bash
cat /etc/os-release
printf 'DISPLAY=%s\nWAYLAND_DISPLAY=%s\n' "$DISPLAY" "$WAYLAND_DISPLAY"
sudo apt update
sudo apt install -y git build-essential cmake pkg-config curl gnupg lsb-release \
  python3 python3-dev python3-pip python3-venv mesa-utils
```

WSLg normally supplies the display variables. Do not replace them with values from old X-server tutorials. If a GUI fails, check WSLg and Windows GPU drivers before investigating the ArduPilot plugin.

## 5. Prepare ArduPilot and its Python environment

These commands assume the new checkout path does not already contain another project. If it exists, inspect that checkout and resume from the relevant step; do not delete it or clone over it.

### Ubuntu / Bash

```bash
mkdir -p "$HOME/aeronex-sim"
cd "$HOME/aeronex-sim"
git clone --recurse-submodules https://github.com/ArduPilot/ardupilot.git
cd "$HOME/aeronex-sim/ardupilot"

python3 -m venv --system-site-packages .venv
DO_PYTHON_VENV_ENV=0 Tools/environment_install/install-prereqs-ubuntu.sh -y
source "$HOME/aeronex-sim/ardupilot/.venv/bin/activate"

git submodule update --init --recursive
python3 -c 'import pymavlink, MAVProxy; print("MAVLink and MAVProxy imports OK")'
command -v mavproxy.py
```

The current prerequisite script recognizes a `.venv` inside the ArduPilot checkout on Ubuntu 24.04. `DO_PYTHON_VENV_ENV=0` keeps activation explicit. The installer can request your Linux sudo password and install compiler/GUI dependencies; it may take time.

If the import check fails, activate that same environment and install the missing Python packages into it:

```bash
source "$HOME/aeronex-sim/ardupilot/.venv/bin/activate"
python3 -m pip install --upgrade pymavlink MAVProxy
python3 -c 'import pymavlink, MAVProxy; print("MAVLink and MAVProxy imports OK")'
```

Use the environment's `python3 -m pip`. Ubuntu 24.04's system-managed Python is not the target for these pip installations.

Build ArduCopter SITL:

```bash
cd "$HOME/aeronex-sim/ardupilot"
source .venv/bin/activate
./waf configure --board sitl
./waf copter -j 2
test -x build/sitl/bin/arducopter
```

**Checkpoint:** the build succeeds and `build/sitl/bin/arducopter` exists. Two build jobs keep resource use moderate; use one if compilation exhausts host memory. This is a build setting, not an app RAM requirement.

This follows the upstream source-checkout workflow. Record the resolved Git revisions at the end of the guide; do not assume two downloads on different dates produce identical builds.

## 6. Verify or install Gazebo Harmonic

### Check first — Ubuntu / Bash

```bash
gz sim --versions
dpkg-query --show gz-harmonic libgz-sim8-dev
```

If Harmonic / Gazebo Sim **8.x** is installed, skip the repository/metapackage installation block. On your machine, these checks passed with **8.12.0**.

### Install when missing — Ubuntu / Bash

```bash
sudo apt update
sudo apt install -y curl lsb-release gnupg
sudo curl -fsSL https://packages.osrfoundation.org/gazebo.gpg \
  --output /usr/share/keyrings/pkgs-osrf-archive-keyring.gpg

printf 'deb [arch=%s signed-by=/usr/share/keyrings/pkgs-osrf-archive-keyring.gpg] https://packages.osrfoundation.org/gazebo/ubuntu-stable %s main\n' \
  "$(dpkg --print-architecture)" "$(lsb_release -cs)" \
  | sudo tee /etc/apt/sources.list.d/gazebo-stable.list

sudo apt update
sudo apt install -y gz-harmonic
```

Use modern Gazebo's `gz sim` commands. Gazebo Classic / `gazebo11` uses different plugins and may conflict with Harmonic packages. If apt reports a Classic conflict, resolve the version choice using the official migration instructions instead of mixing both plugin tutorials.

Test the standalone GUI:

```bash
gz sim -v4 -r shapes.sdf
```

**Checkpoint:** a world containing shapes opens and simulation time advances. Close it before starting the Iris world. If this fails, fix Gazebo/graphics first; ArduPilot is not involved in this check.

## 7. Build the official ArduPilot Gazebo plugin

### Ubuntu / Bash

```bash
sudo apt install -y libgz-sim8-dev rapidjson-dev libopencv-dev \
  libgstreamer1.0-dev libgstreamer-plugins-base1.0-dev \
  gstreamer1.0-plugins-bad gstreamer1.0-libav gstreamer1.0-gl

cd "$HOME/aeronex-sim"
git clone https://github.com/ArduPilot/ardupilot_gazebo.git
cd "$HOME/aeronex-sim/ardupilot_gazebo"
export GZ_VERSION=harmonic
cmake -S . -B build -DCMAKE_BUILD_TYPE=RelWithDebInfo
cmake --build build --parallel 2
find build -name '*ArduPilotPlugin*.so'
```

**Checkpoint:** CMake reports compilation against Gazebo Harmonic, the build succeeds, and a shared ArduPilot plugin library is listed. This setup does not require ROS 2.

Create a reusable environment file. Source it once in each new simulation terminal:

```bash
cat > "$HOME/aeronex-sim/env.sh" <<'EOF'
export AERONEX_SIM_ROOT="$HOME/aeronex-sim"
source "$AERONEX_SIM_ROOT/ardupilot/.venv/bin/activate"
export PATH="$AERONEX_SIM_ROOT/ardupilot/Tools/autotest:$PATH"
export GZ_VERSION=harmonic
export GZ_SIM_SYSTEM_PLUGIN_PATH="$AERONEX_SIM_ROOT/ardupilot_gazebo/build${GZ_SIM_SYSTEM_PLUGIN_PATH:+:$GZ_SIM_SYSTEM_PLUGIN_PATH}"
export GZ_SIM_RESOURCE_PATH="$AERONEX_SIM_ROOT/ardupilot_gazebo/models:$AERONEX_SIM_ROOT/ardupilot_gazebo/worlds${GZ_SIM_RESOURCE_PATH:+:$GZ_SIM_RESOURCE_PATH}"
EOF

source "$HOME/aeronex-sim/env.sh"
```

If your checkout directories differ, update this file to their actual locations. The file avoids needing repeated edits to `.bashrc`.

## 8. Check SITL alone before adding Gazebo

This isolates compiler/Python/MAVProxy problems from Gazebo plugin problems.

### Ubuntu / Bash

```bash
source "$HOME/aeronex-sim/env.sh"
mkdir -p "$AERONEX_SIM_ROOT/runs/basic"
python3 "$AERONEX_SIM_ROOT/ardupilot/Tools/autotest/sim_vehicle.py" \
  -v ArduCopter -f quad --no-rebuild --sysid 1 --speedup 1 \
  --use-dir "$AERONEX_SIM_ROOT/runs/basic" \
  --no-extra-ports --out=udpin:0.0.0.0:14550
```

**Checkpoint:** SITL starts and MAVProxy receives a vehicle heartbeat. In the **MAVProxy console**, run:

```text
status HEARTBEAT
```

Stop this basic run with **Ctrl+C** in its launch terminal before starting the Gazebo-backed run. Do not leave two instance-0 SITL processes competing for ports. The separate run directories keep basic and Gazebo parameter state apart.

## 9. Start the Gazebo-backed simulation

Use two Ubuntu terminals. Both must source `env.sh`.

### Terminal A — Gazebo

```bash
source "$HOME/aeronex-sim/env.sh"
gz sim -v4 -r iris_runway.sdf
```

**Checkpoint:** the Iris quadcopter appears in the runway world. Before SITL connects, lockstep simulation may wait for the controller; that is different from a plugin-load error.

### Terminal B — ArduCopter SITL + MAVProxy

```bash
source "$HOME/aeronex-sim/env.sh"
mkdir -p "$AERONEX_SIM_ROOT/runs/iris"
python3 "$AERONEX_SIM_ROOT/ardupilot/Tools/autotest/sim_vehicle.py" \
  -v ArduCopter -f gazebo-iris --model JSON \
  --no-rebuild --sysid 1 --speedup 1 \
  --use-dir "$AERONEX_SIM_ROOT/runs/iris" \
  --no-extra-ports --out=udpin:0.0.0.0:14550 \
  --console --map
```

`--console` and `--map` open additional MAVProxy GUI windows. If those optional windows fail, omit the two flags and use the terminal console while resolving their GUI dependencies. Map tiles may need internet; a blank map does not by itself mean physics or MAVLink has failed.

**Checkpoint:** SITL connects to Gazebo, a quadrotor heartbeat appears, and sensor/EKF initialization progresses. Keep the default instance 0 paired with the bundled Iris model. Multiple vehicles require separate simulation ports and IDs and are outside this guide.

### Fly a small test through MAVProxy

Enter each command at the **MAVProxy prompt**, not Bash. Wait for each stage to complete before the next command:

```text
mode guided
arm throttle
takeoff 3
```

Wait until the simulated drone reaches roughly 3 metres, then inspect:

```text
status GLOBAL_POSITION_INT
mode brake
```

`GLOBAL_POSITION_INT.relative_alt` is in **millimetres**: approximately `3000` means 3 m. Verify that the drone holds position, then land:

```text
mode land
```

Wait until it reaches the ground and disarms normally. Inspect `status EXTENDED_SYS_STATE` if that message is being streamed; absence of that message is not proof of a landing failure. Use Gazebo and the available altitude/armed-state telemetry together.

If arming is rejected, read the reported pre-arm reason and check simulator time, sensors, GPS/EKF readiness and model configuration. Keep normal arming checks enabled; forced arming or disabling checks would conceal setup failures.

## 10. Connect a physical Android phone over Wi-Fi

Use a local test network with the computer and phone reachable from each other. Keep the simulator connection separate from any physical aircraft.

### A. Configure WSL mirrored networking

On **Windows 11 22H2 or newer**, current WSL supports mirrored networking, which makes LAN access to a WSL listener practical.

Open `%UserProfile%\.wslconfig` in a text editor. Add this setting under its existing `[wsl2]` section, preserving other settings. If the file does not exist, create it:

```ini
[wsl2]
networkingMode=mirrored
```

Save work in all WSL terminals, stop the simulation, then run **PowerShell**:

```powershell
wsl --shutdown
wsl -d Ubuntu-24.04
```

`wsl --shutdown` stops all WSL distributions. Reopen Terminal A and Terminal B and restart the simulation afterward.

For a native Ubuntu installation, use its LAN IPv4 address and applicable Linux firewall rules; WSL configuration is unnecessary. On an older Windows/NAT-only setup, a phone generally cannot reach WSL's private address directly. Windows `netsh interface portproxy` is a TCP facility and does **not** solve this UDP link. Use supported mirrored networking, native Ubuntu, or a separately configured UDP relay.

### B. Allow the simulation UDP listener

Run these once in **administrator PowerShell** for this test setup:

```powershell
New-NetFirewallHyperVRule -Name 'AeroNex-SITL-UDP14550' `
  -DisplayName 'AeroNex SITL UDP 14550' `
  -Direction Inbound `
  -VMCreatorId '{40E0AC32-46A5-438A-A0B2-2B479E8F2E90}' `
  -Protocol UDP -LocalPorts 14550

New-NetFirewallRule -Name 'AeroNex-SITL-UDP14550-LAN' `
  -DisplayName 'AeroNex SITL UDP 14550 LAN' `
  -Direction Inbound -Action Allow -Protocol UDP -LocalPort 14550 `
  -Profile Private -RemoteAddress LocalSubnet
```

Use a trusted network classified as Private. If a rule with the same name already exists, inspect/reuse it instead of adding a duplicate. Managed computers may enforce additional firewall policy. Do not disable the whole firewall to diagnose one UDP port.

If UFW is active inside Ubuntu, allow the phone specifically, substituting its actual IP:

```bash
sudo ufw status
# Run the following only when UFW is active; replace the example phone address.
sudo ufw allow from 192.168.1.50 to any port 14550 proto udp
```

### C. Find the computer's reachable address

In **PowerShell**:

```powershell
Get-NetIPConfiguration
```

Find the IPv4 address of the Wi-Fi/Ethernet adapter on the phone's network. For example, use `192.168.1.20` if that is the computer's actual address. Do not use a VPN adapter, `0.0.0.0`, or `127.0.0.1` for a physical phone. The example addresses in this guide must be replaced with yours.

In **Ubuntu**, verify that Terminal B created the listener:

```bash
ss -lunp 'sport = :14550'
```

### D. Enter these app settings

Open AeroNex, turn on **Use ArduPilot SITL** in the simulator card, and accept its simulator-only notice with **I am using SITL**.

| App field | Value |
| --- | --- |
| **SITL IPv4 address** | Computer's actual LAN IPv4 address, e.g. `192.168.1.20` |
| **SITL UDP port** | `14550` |
| **Phone UDP listen port** | `14551` |

Tap **Connect**. The app sends a heartbeat from its bound socket, waits for the vehicle heartbeat, and then requests the telemetry streams itself; a separate **Request telemetry** tap is no longer needed. The simulator card's heartbeat line then reads **Receiving heartbeats from 192.168.1.20:14550.** (with your address), and the status card at the top shows a vehicle line such as `Connected · Disarmed · On ground · Stabilize · Alt 0.0 m · Battery 100% · Position ready`. If no heartbeat arrives, the heartbeat line says **No heartbeat from …** and asks you to check that SITL is running and sending to this phone before tapping **Connect** again. **Request telemetry** remains available to repeat the request, for example after restarting MAVProxy. The raw telemetry map is in diagnostics.

The request sequence asks for `SYS_STATUS`, `ATTITUDE`, `LOCAL_POSITION_NED`, `GLOBAL_POSITION_INT`, `EKF_STATUS_REPORT` and `EXTENDED_SYS_STATE`. A received ground-station heartbeat is insufficient: the app accepts the selected peer and vehicle system/component **1/1**, with a quadrotor vehicle heartbeat.

SITL mode, the address and both ports are saved on the phone after a connection. On the next launch the app fills them in and reconnects without showing the notice again. Turning SITL mode off is saved too.

### E. Dry run, simulator execution and multi-step plans

Speak or type a command of up to 5 steps, for example “take off to 3 metres then move forward 2 metres and land”. The plan card lists **1. Take off to 3 m**, **2. Move forward 2 m**, **3. Land**, each labelled **Rules** (parsed by the app's deterministic rules) or **AI** (a clause the rules did not recognise, proposed by the selected local model). Check AI steps with particular care. While the simulator reports the drone on the ground, a plan must start with a takeoff height; while it reports the drone airborne, a takeoff step is rejected. Every up/down step must keep the projected altitude within 1–10 m.

By default the status card shows **DRY RUN**: **Confirm dry run** reports every step as **NOT SENT** and nothing reaches the simulator.

To fly the Gazebo drone from the app, turn on **Execute confirmed commands in simulator** in the simulator card. The switch is available only while the simulator heartbeat is fresh. Accept the warning with **Send to simulator**; the status card then shows **SIMULATOR EXECUTION ON**. **Confirm and send to simulator** confirms the whole plan once, within the 15-second **Expires in N s** window. Steps are sent one at a time, and each waits for telemetry to confirm completion before the next. Each step reports **COMPLETED**, **REJECTED — nothing sent**, **UNKNOWN** or **SKIPPED — not sent**; the plan stops at the first REJECTED or UNKNOWN step, and the remaining steps are never sent. **Stop sending to simulator** drops the link and reports the vehicle state as unknown; check the simulator before sending more.

Execution is a per-connection opt-in and is never saved. Reconnecting, disconnecting, toggling SITL mode or backgrounding the app returns to dry run, and the automatic reconnect at launch also starts in dry run. There are no retries, and nothing is replayed after reconnecting. A simulator switch is an acknowledgement, not peer authentication: use it only with an isolated ArduPilot SITL instance, never with a real aircraft.

### Why this particular MAVProxy output is used

The app's [transport](../../app/lib/features/flight/data/mavlink_transport.dart) rejects a datagram unless its **source IPv4 address and source UDP port** match the configured SITL endpoint.

`--out=udpin:0.0.0.0:14550` makes MAVProxy's output link bind UDP 14550. It learns the app's return endpoint when it receives the app heartbeat and sends telemetry from that same bound port. `--no-extra-ports` suppresses the automatic additional outputs so this topology is explicit.

A typical `--out=udpout:PHONE_IP:14551` sends to the right destination but can use an ephemeral **source** port. That would fail this app's `14550` source-port check. Do not substitute that command without accounting for the source-port requirement.

MAVProxy forwards messages received from its output links toward SITL when `mavfwd` is enabled. It defaults to true in the reviewed source. If heartbeat reception works but telemetry requests time out, inspect it in the **MAVProxy console**:

```text
set mavfwd
```

For this isolated simulator session, restore forwarding if it was disabled:

```text
set mavfwd true
```

### Optional: Android emulator instead of a phone

An Android Studio emulator uses **`10.0.2.2`** to reach the Windows host's loopback side. Use that as the app's remote address only for the emulator case, with the same `14550` / `14551` ports and reachable WSL listener. Verify packet flow; the physical-phone LAN address and emulator alias are different routes.

The current APK is ARM64-only, so the AVD must support ARM64 execution or ARM translation. Ordinary `adb forward tcp:...` / `adb reverse tcp:...` commands do not forward this UDP transport. The earlier emulator speech audit did not establish the complete emulator-to-WSL SITL connection.

## 11. Run the app and simulator test checklist

Run the manual MAVProxy flight test first. Then keep the simulated drone landed while checking app speech and proposals.

| Test | Action | Passing result |
| --- | --- | --- |
| Gazebo alone | Run `shapes.sdf` | GUI opens; time advances. |
| SITL alone | Run the basic quad configuration | Vehicle heartbeat reaches MAVProxy. |
| Gazebo + SITL | Start Iris, then `gazebo-iris --model JSON` | Physics/sensor exchange works; simulator becomes ready. |
| Manual simulated flight | GUIDED → arm → takeoff 3 → BRAKE → LAND | Visible takeoff, hold and completed landing. |
| Phone connection | Connect using actual IP and ports | Simulator card shows **Receiving heartbeats from** the SITL endpoint. |
| Requested telemetry | Connect requests it automatically; Request telemetry repeats it | Status card vehicle line updates and requests are acknowledged. |
| Saved settings | Restart the app with SITL mode on | Address and ports are filled in; the app reconnects without the notice and starts in **DRY RUN**. |
| Model retention | Restart the app | Installed models return to ready; selected speech language/backend persists. |
| Indian English speech | Select Indian English; record and Finish | Compare original Heard with the actual spoken words, including numbers and negation. |
| Telugu speech | Select Telugu; speak a native-speaker-reviewed Telugu phrase | Output matches the intended language and meaning; unrelated script/text is a recognition failure. |
| Review and interpretation | Correct the transcript, then interpret | Proposal matches action, direction, units and numbers. A wrong proposal is an AI failure. |
| Dry-run confirmation | With execution off, confirm a correct plan | Every step shows **NOT SENT**; the Gazebo drone does not move from this app action. |
| Simulator execution | Turn on **Execute confirmed commands in simulator**, then confirm “take off to 3 metres then move forward 2 metres and land” | Steps run in order and each shows **COMPLETED** only after telemetry confirms it; Gazebo shows the same motion. |
| Stop sending | Tap **Stop sending to simulator** during a multi-step plan | The link drops, execution turns off, the app reports the vehicle state as unknown, and no later step is sent. Check Gazebo/MAVProxy before reconnecting. |
| Link loss | Stop SITL | Fresh heartbeat expires; the app must not continue claiming a live connection. |
| Reconnect | Restart SITL and reconnect | Telemetry recovers; execution is off again and no old command is replayed. |

English seed phrases:

- `Take off to 3 metres`
- `Move left 2 metres`
- `Hold position`
- `Land`
- `Hey drone, fly` — needs a requested height, not a guessed takeoff.
- `Do not take off` — must not become a positive flight action.
- `Take off to 100 metres` — should be rejected by the app's configured bounds.
- `Take off to 3 metres then move forward 2 metres and land` — a three-step plan listing every action.
- An unrelated question — must not become a flight command. Two actions in one clause without a connective must be rejected, not silently become one flight command.

Record the actual utterance, language/backend, original Heard, edited text, proposal, result and time taken. For Telugu, use reviewed examples from a Telugu speaker rather than treating the language selector as proof of accuracy.

Gazebo does not fix speech recognition. The [0.1.7 audit](../testing/2026-10-10-emulator-qa.md) records inconsistent English recognition and unverified spoken-Telugu accuracy. It also records slow LFM interpretation under emulator ARM translation. A successful Gazebo flight does not establish that speech or AI interpretation is reliable.

## 12. Optional: run this repository's existing SITL compatibility check

This helper starts its **own disposable basic SITL instance** with pymavlink. It checks takeoff to 3 m and the LAND acknowledgement; the move, BRAKE and landing sequence is covered by the app's SITL JVM tests (`-Psitl=1`). It does not attach to the Gazebo world and does not test the entire phone/UDP path.

After stopping the interactive simulation, run in **Ubuntu / Bash**:

```bash
source "$HOME/aeronex-sim/env.sh"
export AERONEX_APP='/mnt/c/Users/yugas/Downloads/Drone-app/.worktrees/aeronex-skeleton'

python3 "$AERONEX_APP/tools/check_sitl.py" \
  "$AERONEX_SIM_ROOT/ardupilot/build/sitl/bin/arducopter" \
  "$AERONEX_SIM_ROOT/ardupilot/Tools/autotest/default_params/copter.parm"
```

Since 0.3.0 the app has no native MAVLink codec (it was removed with whisper.cpp, llama.cpp and Cactus), so there is no CMake codec build or `--codec` run; the Kotlin codec is checked by the app's JVM tests against pymavlink golden vectors.

Expected line before the SITL log tail:

```text
Takeoff telemetry reached 3 m tolerance; land mode acknowledged
```

An earlier run passed using the then-existing simulator binary; see the [historical test record](../testing/2026-10-08-build-status.md). Run the helper again to validate the new build you install from this guide.

## 13. Troubleshooting by symptom

| Symptom | Check / correction |
| --- | --- |
| `sim_vehicle.py` or `mavproxy.py` not found | Source `~/aeronex-sim/env.sh`; use the full `sim_vehicle.py` path shown above; verify the virtual environment. |
| `No module named pymavlink` / `MAVProxy` | Activate the project `.venv`; run the import and pip commands in section 5. |
| `externally-managed-environment` from pip | You are using the system interpreter. Activate `.venv` and use `python3 -m pip`. |
| Gazebo opens a black window or crashes before Iris loads | Check WSLg, GPU drivers and `glxinfo -B`. As a diagnostic, try `LIBGL_ALWAYS_SOFTWARE=1 gz sim -v4 -r shapes.sdf`; software rendering can be slow. |
| `iris_runway.sdf` or Iris resources not found | Check `GZ_SIM_RESOURCE_PATH` and source `env.sh` in the Gazebo terminal. |
| ArduPilot plugin cannot load | Confirm `GZ_VERSION=harmonic`, `libgz-sim8-dev`, a successful plugin build, and `GZ_SIM_SYSTEM_PLUGIN_PATH`. Use `ldd` on the built plugin to identify missing libraries. |
| Gazebo waits; SITL receives no physics | Ensure Gazebo was started with `-r`, SITL uses `-f gazebo-iris --model JSON`, and both use instance 0 / the supplied Iris configuration. Inspect plugin logs and UDP 9002. |
| Address already in use | Stop the earlier basic/Gazebo run from its own terminal. Inspect `ss -lunp` and `ss -ltnp`; avoid starting competing listeners or ground stations on 14550. |
| Phone never gets a heartbeat | Check actual LAN IPv4, mirrored mode, both firewalls, Wi-Fi client isolation and `ss -lunp 'sport = :14550'`. Tap Connect so the app sends the first heartbeat. |
| UDP packets arrive but the app ignores them | Inspect source IP **and** source port, plus vehicle system/component 1/1. Use the `udpin` configuration instead of an output with an ephemeral source port. |
| Heartbeat is fresh but telemetry requests time out | Inspect `set mavfwd`, SITL's responses and message support. The app expects a matching ACK, not just a stream of arbitrary packets. |
| Arm rejected / EKF not ready | Read the actual pre-arm reason; allow initialization; check Gazebo sensors and advancing time. Correct the setup without bypassing arming checks. |
| Correct app confirmation does not move Iris | Expected in the default **DRY RUN**: steps report **NOT SENT**. To fly the simulator, turn on **Execute confirmed commands in simulator** for this connection (section 10E); it turns off again after reconnecting or backgrounding. If a step reports **REJECTED** or **UNKNOWN**, read its message and check mode, arming and EKF state in MAVProxy before sending more. MAVProxy remains an independent check of simulated motion. |
| Wrong words, Arabic output for Telugu, or wrong numbers | Treat as speech-model/capture/language failure; inspect Heard and compare Vosk with Whisper. Gazebo/network settings cannot repair the transcript. |
| Correct transcript but wrong action | Treat as an AI interpretation failure. Preserve the transcript/proposal pair for diagnosis; do not accept the wrong proposal. |

To inspect just the app's UDP link, install `tcpdump` if needed and replace the example phone IP:

```bash
sudo apt install -y tcpdump
sudo tcpdump -ni any 'udp port 14550 and host 192.168.1.50'
```

Expected direction on a physical-phone LAN: `PHONE_IP:14551 → COMPUTER_IP:14550`, then `COMPUTER_IP:14550 → PHONE_IP:14551`. Packet presence proves network traffic, not valid MAVLink or correct flight behavior.

## 14. Save versions, evidence, and shut down

Create a version record in **Ubuntu / Bash**:

```bash
source "$HOME/aeronex-sim/env.sh"
mkdir -p "$AERONEX_SIM_ROOT/evidence"
git -C "$AERONEX_SIM_ROOT/ardupilot" rev-parse HEAD > "$AERONEX_SIM_ROOT/evidence/ardupilot-commit.txt"
git -C "$AERONEX_SIM_ROOT/ardupilot_gazebo" rev-parse HEAD > "$AERONEX_SIM_ROOT/evidence/plugin-commit.txt"
gz sim --versions > "$AERONEX_SIM_ROOT/evidence/gazebo-version.txt"
python3 -m pip freeze > "$AERONEX_SIM_ROOT/evidence/python-packages.txt"
```

Keep the Gazebo terminal output, SITL/MAVProxy logs produced under the chosen run directory, a screenshot of the simulated flight, app diagnostics, and the results table. Mark each case **pass / fail / not run** and retain actual failure text. Do not report an ACK alone as proof of completed movement or landing.

To finish, land through MAVProxy, wait for the simulated vehicle to disarm, tap **Disconnect** in the app, then stop Terminal B and Terminal A with **Ctrl+C**. Preserve run directories for diagnosis. If you later remove the Windows test firewall exceptions, remove only the named rules:

```powershell
Remove-NetFirewallHyperVRule -Name 'AeroNex-SITL-UDP14550'
Remove-NetFirewallRule -Name 'AeroNex-SITL-UDP14550-LAN'
```

Run that cleanup in administrator PowerShell only if you created those rules. If you added the example UFW rule, remove the rule for your actual phone IP separately.

## 15. App-controlled simulated flight

Since 0.2.0 the app can send confirmed plans to the simulator executor, but only after the explicit per-connection opt-in described in [section 10E](#e-dry-run-simulator-execution-and-multi-step-plans). Installing SITL/Gazebo or turning on **Use ArduPilot SITL** alone does not enable it; confirmations stay dry runs until **Execute confirmed commands in simulator** is turned on. Execution is bound to the selected simulator peer and a fresh heartbeat, uses one-use confirmations, sends one step at a time, stops at a rejected or uncertain outcome, and never replays a command after reconnecting. A step refused before any packet is sent reports **REJECTED — nothing sent**; a failure or missing confirmation after a send reports **UNKNOWN**.

What remains: the complete phone-to-SITL flow still needs physical-phone runs recorded in the [phone evaluation guide](../testing/mobile-device-matrix.md), and native-speaker speech/AI regression cases are still needed, especially for negation, numbers and multi-step phrasing. Use only a disposable, isolated simulator. The app cannot verify that a peer is a simulator, and nothing in this guide makes it ready for a real aircraft.

## Official references

Checked while preparing this guide on 10 October 2026:

- [ArduPilot: Using SITL with Gazebo](https://ardupilot.org/dev/docs/sitl-with-gazebo.html)
- [Official ArduPilot Gazebo plugin](https://github.com/ArduPilot/ardupilot_gazebo)
- [ArduPilot Linux development setup](https://ardupilot.org/dev/docs/building-setup-linux.html)
- [ArduPilot: Setting up SITL on Linux](https://ardupilot.org/dev/docs/setting-up-sitl-on-linux.html)
- [Gazebo Harmonic installation on Ubuntu](https://gazebosim.org/docs/harmonic/install_ubuntu/)
- [Microsoft: WSL networking and mirrored mode](https://learn.microsoft.com/en-us/windows/wsl/networking)
- [Microsoft: Run Linux GUI applications with WSL](https://learn.microsoft.com/en-us/windows/wsl/tutorials/gui-apps)
- [pymavlink UDP endpoint implementation](https://github.com/ArduPilot/pymavlink/blob/master/mavutil.py)
- [MAVProxy output-link and forwarding implementation](https://github.com/ArduPilot/MAVProxy/blob/master/MAVProxy/mavproxy.py)

Project references: [current app audit](../testing/2026-10-10-emulator-qa.md), [phone evaluation guide](../testing/mobile-device-matrix.md), [MAVLink transport](../../app/lib/features/flight/data/mavlink_transport.dart), and [plan command controller](../../app/lib/features/flight/application/command_controller.dart).
