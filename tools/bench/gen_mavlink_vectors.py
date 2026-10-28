"""Write golden MAVLink 2 vectors for the Kotlin codec (link.mavlink), using pymavlink as the reference.

Run inside WSL with the simulator venv (pymavlink 2.4.50, ardupilotmega dialect):
    /home/drone/aeronex-sim/.venv/bin/python tools/bench/gen_mavlink_vectors.py
    /home/drone/aeronex-sim/.venv/bin/python tools/bench/gen_mavlink_vectors.py --check

Every frame is packed by pymavlink, decoded back by pymavlink, and signed frames are verified by a
pymavlink instance holding the test key (and rejected by one holding a different key). The Kotlin tests
must reproduce the bytes exactly and decode the recorded field values.
"""
import argparse
import hashlib
import json
import math
import sys
from pathlib import Path

import pymavlink
from pymavlink.dialects.v20 import ardupilotmega as mavlink

PYMAVLINK_VERSION = '2.4.50'
OUTPUT = Path(__file__).resolve().parents[2] / 'app/android/app/src/test/resources/mavlink_vectors.json'
MESSAGE_IDS = [0, 1, 20, 22, 30, 32, 33, 76, 77, 84, 85, 109, 111, 193, 242, 245, 253, 256]
# Fixed test key; never a real vehicle key.
TEST_KEY = hashlib.sha256(b'aeronex mavlink test key').digest()
# 2026-10-12T06:00:00Z in 10 microsecond units since 2015-01-01T00:00:00Z (MAVLink signing epoch).
T0 = 37171440000000
GCS = (255, 190)
VEHICLE = (1, 1)
SIK_RADIO = (51, 68)

STATUSTEXT_50 = 'PreArm: Fence enabled, need position estimate now!'
assert len(STATUSTEXT_50) == 50

# (name, message, (sysid, compid), seq, signing (link_id, timestamp) or None, MAVLink 1)
FRAMES = [
    ('heartbeat_gcs', mavlink.MAVLink_heartbeat_message(6, 8, 0, 0, 4, 3), GCS, 0, None, False),
    ('heartbeat_vehicle_guided_armed', mavlink.MAVLink_heartbeat_message(2, 3, 217, 4, 4, 3), VEHICLE, 17, None, False),
    ('heartbeat_vehicle_v1', mavlink.MAVLink_heartbeat_message(2, 3, 89, 0, 3, 3), VEHICLE, 200, None, True),
    ('heartbeat_gcs_signed', mavlink.MAVLink_heartbeat_message(6, 8, 0, 0, 4, 3), GCS, 1, (1, T0), False),
    ('sys_status_extensions_truncated', mavlink.MAVLink_sys_status_message(
        325188655, 325122095, 325188655, 213, 12592, 1430, 87, 0, 0, 0, 0, 0, 0, 0, 0, 0), VEHICLE, 18, None, False),
    ('sys_status_with_extensions', mavlink.MAVLink_sys_status_message(
        325188655, 325122095, 291634223, 1000, 10480, -1, -1, 12, 3, 1, 2, 3, 4, 1, 1, 1), VEHICLE, 19, None, False),
    ('param_request_read_by_name', mavlink.MAVLink_param_request_read_message(1, 1, b'FS_GCS_ENABLE', -1), GCS, 2, None, False),
    ('param_request_read_by_index', mavlink.MAVLink_param_request_read_message(1, 1, b'', 42), GCS, 3, None, False),
    ('param_value_real32', mavlink.MAVLink_param_value_message(b'FENCE_RADIUS', 30.0, 9, 1287, 412), VEHICLE, 20, None, False),
    ('param_value_sixteen_chars', mavlink.MAVLink_param_value_message(b'SERIAL1_PROTOCOL', 2.0, 2, 1287, 900), VEHICLE, 21, None, False),
    ('global_position_int', mavlink.MAVLink_global_position_int_message(
        123456, 129716000, 775946000, 920500, 10020, 52, -13, -4, 9000), VEHICLE, 22, None, False),
    ('global_position_int_north_truncated', mavlink.MAVLink_global_position_int_message(
        123556, 129716010, 775946000, 920480, 10000, 150, 0, 0, 0), VEHICLE, 23, None, False),
    ('attitude_truncated', mavlink.MAVLink_attitude_message(
        123500, 0.0123, -0.034, 1.5707964, 0.001, -0.002, 0.0), VEHICLE, 24, None, False),
    ('local_position_ned_truncated', mavlink.MAVLink_local_position_ned_message(
        123600, 4.98, -0.12, -10.02, 0.25, 0.0, 0.0), VEHICLE, 25, None, False),
    ('command_long_set_mode_guided', mavlink.MAVLink_command_long_message(
        1, 1, 176, 0, 1, 4, 0, 0, 0, 0, 0), GCS, 4, None, False),
    ('command_long_brake_signed', mavlink.MAVLink_command_long_message(
        1, 1, 176, 0, 1, 17, 0, 0, 0, 0, 0), GCS, 5, (1, T0 + 100), False),
    ('command_long_brake_resend_signed', mavlink.MAVLink_command_long_message(
        1, 1, 176, 1, 1, 17, 0, 0, 0, 0, 0), GCS, 6, (1, T0 + 101), False),
    ('command_long_arm', mavlink.MAVLink_command_long_message(
        1, 1, 400, 0, 1, 0, 0, 0, 0, 0, 0), GCS, 7, None, False),
    ('command_long_disarm_non_forced', mavlink.MAVLink_command_long_message(
        1, 1, 400, 0, 0, 0, 0, 0, 0, 0, 0), GCS, 8, None, False),
    ('command_long_takeoff_nan_yaw', mavlink.MAVLink_command_long_message(
        1, 1, 22, 2, 0, 0, 0, math.nan, 0, 0, 2.5), GCS, 9, None, False),
    ('command_long_set_message_interval', mavlink.MAVLink_command_long_message(
        1, 1, 511, 0, 33, 200000, 0, 0, 0, 0, 0), GCS, 255, None, False),
    ('command_long_v1', mavlink.MAVLink_command_long_message(
        1, 1, 176, 0, 1, 4, 0, 0, 0, 0, 0), GCS, 10, None, True),
    ('command_ack_minimal_truncated', mavlink.MAVLink_command_ack_message(176, 0), VEHICLE, 26, None, False),
    ('command_ack_failed', mavlink.MAVLink_command_ack_message(176, 4), VEHICLE, 27, None, False),
    ('command_ack_full', mavlink.MAVLink_command_ack_message(400, 0, 0, 0, 255, 190), VEHICLE, 28, None, False),
    ('command_ack_in_progress', mavlink.MAVLink_command_ack_message(22, 5, 40, -7, 255, 190), VEHICLE, 29, None, False),
    ('command_ack_signed', mavlink.MAVLink_command_ack_message(176, 0, 0, 0, 255, 190), VEHICLE, 30, (0, T0 + 5000), False),
    ('set_position_target_body_offset', mavlink.MAVLink_set_position_target_local_ned_message(
        0, 1, 1, 9, 3576, 5.0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), GCS, 11, None, False),
    ('set_position_target_body_offset_signed', mavlink.MAVLink_set_position_target_local_ned_message(
        0, 1, 1, 9, 3576, -2.5, 1.5, -0.5, 0, 0, 0, 0, 0, 0, 0, 0), GCS, 12, (1, T0 + 200), False),
    ('position_target_local_ned_echo', mavlink.MAVLink_position_target_local_ned_message(
        130000, 1, 3576, 9.98, -0.12, -10.0, 0, 0, 0, 0, 0, 0, 0, 0), VEHICLE, 31, None, False),
    ('radio_status_sik_v1', mavlink.MAVLink_radio_status_message(178, 170, 98, 40, 38, 0, 3), SIK_RADIO, 0, None, True),
    ('radio_status_truncated', mavlink.MAVLink_radio_status_message(178, 170, 100, 40, 0, 2, 0), SIK_RADIO, 1, None, False),
    ('timesync_request', mavlink.MAVLink_timesync_message(0, 123456789000), GCS, 13, None, False),
    ('timesync_response', mavlink.MAVLink_timesync_message(987654321000000, 123456789000), VEHICLE, 32, None, False),
    ('timesync_negative', mavlink.MAVLink_timesync_message(-5, -123456789000), VEHICLE, 33, None, False),
    ('ekf_status_report_truncated', mavlink.MAVLink_ekf_status_report_message(
        831, 0.023, 0.011, 0.0042, 0.031, 0.0, 0.0), VEHICLE, 34, None, False),
    ('ekf_status_report_airspeed', mavlink.MAVLink_ekf_status_report_message(
        0x33F, 0.5, 0.25, 0.125, 0.75, 1.0, 0.05), VEHICLE, 35, None, False),
    ('home_position_truncated', mavlink.MAVLink_home_position_message(
        129716000, 775946000, 920000, 0, 0, 0, [1, 0, 0, 0], 0, 0, 0, 0), VEHICLE, 36, None, False),
    ('home_position_with_time', mavlink.MAVLink_home_position_message(
        -337000000, 1512000000, 15000, 0.5, -0.25, 0.1, [0.7071068, 0, 0, 0.7071068], 0, 0, 0, 1234567890), VEHICLE, 37, None, False),
    ('extended_sys_state_on_ground', mavlink.MAVLink_extended_sys_state_message(0, 1), VEHICLE, 38, None, False),
    ('extended_sys_state_undefined_one_byte', mavlink.MAVLink_extended_sys_state_message(0, 0), VEHICLE, 39, None, False),
    ('extended_sys_state_in_air_signed', mavlink.MAVLink_extended_sys_state_message(0, 2), VEHICLE, 40, (0, T0 + 5001), False),
    ('statustext_short', mavlink.MAVLink_statustext_message(6, b'EKF3 IMU0 is using GPS'), VEHICLE, 41, None, False),
    ('statustext_fifty_chars', mavlink.MAVLink_statustext_message(2, STATUSTEXT_50.encode()), VEHICLE, 42, None, False),
    ('statustext_chunked', mavlink.MAVLink_statustext_message(4, b'Battery 1 is low 10.5V used 1200 mAh', 7, 1), VEHICLE, 43, None, False),
    ('setup_signing', mavlink.MAVLink_setup_signing_message(1, 1, list(TEST_KEY), T0), GCS, 14, None, False),
]


def pack(message, source, seq, signing, v1):
    mav = mavlink.MAVLink(None, srcSystem=source[0], srcComponent=source[1])
    mav.seq = seq
    if signing:
        mav.signing.secret_key = TEST_KEY
        mav.signing.link_id, mav.signing.timestamp = signing
        mav.signing.sign_outgoing = True
    return bytes(message.pack(mav, force_mavlink1=v1))


def json_value(value):
    if isinstance(value, float) and math.isnan(value):
        return 'NaN'
    if isinstance(value, list):
        return [json_value(item) for item in value]
    return value


def check_signature(frame, key):
    receiver = mavlink.MAVLink(None)
    receiver.signing.secret_key = key
    try:
        receiver.decode(bytearray(frame))
        return True
    except mavlink.MAVError:
        return False


def vector(name, message, source, seq, signing, v1):
    frame = pack(message, source, seq, signing, v1)
    decoded = mavlink.MAVLink(None).decode(bytearray(frame))
    if decoded.get_type() != message.get_type():
        raise AssertionError(f'{name}: decoded {decoded.get_type()}')
    if signing:
        if not check_signature(frame, TEST_KEY):
            raise AssertionError(f'{name}: pymavlink rejected its own signature')
        if check_signature(frame, hashlib.sha256(b'wrong key').digest()):
            raise AssertionError(f'{name}: pymavlink accepted a wrong key')
    header = 6 if v1 else 10
    # get_msgId(), not .id: STATUSTEXT has a field named id that shadows the message id.
    full = mavlink.mavlink_map[message.get_msgId()].unpacker.size
    payload_length = frame[1]
    fields = {key: json_value(value) for key, value in decoded.to_dict().items() if key != 'mavpackettype'}
    return {
        'name': name,
        'message': message.get_type(),
        'id': message.get_msgId(),
        'version': 1 if v1 else 2,
        'seq': seq,
        'sysid': source[0],
        'compid': source[1],
        'signed': bool(signing),
        'link_id': signing[0] if signing else None,
        'timestamp': signing[1] if signing else None,
        'payload_length': payload_length,
        'truncated': payload_length < full,
        'crc': int.from_bytes(frame[header + payload_length:header + payload_length + 2], 'little'),
        'fields': fields,
        'hex': frame.hex(),
    }


def build():
    if pymavlink.__version__ != PYMAVLINK_VERSION:
        raise SystemExit(f'pymavlink {PYMAVLINK_VERSION} required, found {pymavlink.__version__}')
    messages = {}
    for message_id in MESSAGE_IDS:
        cls = mavlink.mavlink_map[message_id]
        messages[cls.msgname] = {'id': message_id, 'crc_extra': cls.crc_extra, 'max_length': cls.unpacker.size}
    frames = [vector(*spec) for spec in FRAMES]
    covered = {frame['id'] for frame in frames}
    missing = sorted(set(MESSAGE_IDS) - covered)
    if missing:
        raise AssertionError(f'No vector for message ids {missing}')
    return {
        'generator': 'tools/bench/gen_mavlink_vectors.py',
        'pymavlink': pymavlink.__version__,
        'dialect': 'ardupilotmega',
        'signing_key_hex': TEST_KEY.hex(),
        'signing_epoch': '2015-01-01T00:00:00Z, 10 microsecond units',
        'messages': messages,
        'frames': frames,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--output', type=Path, default=OUTPUT)
    parser.add_argument('--check', action='store_true', help='fail if the file differs from a fresh generation')
    args = parser.parse_args()
    text = json.dumps(build(), indent=1) + '\n'
    if args.check:
        if not args.output.exists() or args.output.read_text() != text:
            print(f'{args.output} is stale; regenerate it', file=sys.stderr)
            return 1
        print(f'{args.output} is up to date')
        return 0
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(text, newline='\n')
    print(f'Wrote {len(FRAMES)} frames to {args.output}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
