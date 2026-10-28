"""Relay MAVLink UDP between a phone on the Windows Mobile Hotspot and the WSL simulator.

WSL mirrored networking does not mirror the hotspot adapter (192.168.137.1), so a phone on the laptop's hotspot
cannot reach MAVProxy's udpin:0.0.0.0:14550 inside WSL. This relay runs on Windows, listens on the hotspot address
and forwards each datagram to 127.0.0.1:14550 (which does reach WSL), and sends the simulator's replies back to the
last phone that spoke.

    python tools/simulation/hotspot_relay.py                 # 192.168.137.1:14550 -> 127.0.0.1:14550
    python tools/simulation/hotspot_relay.py --port 14560    # if 14550 is taken on the hotspot address
"""
import argparse
import select
import socket
import sys
import time


def udp_socket():
    """A UDP socket that, on Windows, ignores ICMP "port unreachable" instead of failing the next receive."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    if hasattr(socket, "SIO_UDP_CONNRESET"):
        sock.ioctl(socket.SIO_UDP_CONNRESET, False)
    return sock


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--listen", default="192.168.137.1", help="hotspot address of this laptop")
    parser.add_argument("--port", type=int, default=14550, help="UDP port the phone sends to")
    parser.add_argument("--sim", default="127.0.0.1:14550", help="simulator MAVLink UDP endpoint")
    args = parser.parse_args()
    sim_host, sim_port = args.sim.rsplit(":", 1)
    sim = (sim_host, int(sim_port))

    phone_side = udp_socket()
    try:
        phone_side.bind((args.listen, args.port))
    except OSError as error:
        sys.exit(f"Cannot listen on {args.listen}:{args.port} ({error}). Is the hotspot on? Try --port 14560.")
    sim_side = udp_socket()
    sim_side.bind(("127.0.0.1", 0))

    phone = None
    counts = {"to_sim": 0, "to_phone": 0}
    last_report = time.monotonic()
    print(f"Relaying {args.listen}:{args.port} <-> {sim[0]}:{sim[1]}. Set the app's drone address to "
          f"{args.listen} and drone port to {args.port}. Ctrl+C to stop.", flush=True)
    while True:
        ready, _, _ = select.select([phone_side, sim_side], [], [], 1.0)
        for sock in ready:
            try:
                data, sender = sock.recvfrom(65535)
            except ConnectionResetError:  # an ICMP "port unreachable" from a peer that went away
                continue
            if sock is phone_side:
                if sender != phone:
                    print(f"Phone connected from {sender[0]}:{sender[1]}", flush=True)
                    phone = sender
                sim_side.sendto(data, sim)
                counts["to_sim"] += 1
            elif phone is not None:
                phone_side.sendto(data, phone)
                counts["to_phone"] += 1
        if time.monotonic() - last_report >= 10:
            print(f"datagrams phone->sim {counts['to_sim']}, sim->phone {counts['to_phone']}", flush=True)
            last_report = time.monotonic()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
