#!/usr/bin/env python3
"""Pings a Minecraft server (handshake, status request) and prints the JSON status it answers.

Usage: mc-status.py HOST PORT [TIMEOUT]. Exits 0 when the answer is a JSON object with a
`version` key, 1 otherwise. Standard library only: the smoke test of the container image runs it.
"""

import json
import socket
import sys


def varint(value: int) -> bytes:
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def read_varint(sock: socket.socket) -> int:
    result = shift = 0
    while True:
        byte = sock.recv(1)
        if not byte:
            raise EOFError("connection closed")
        result |= (byte[0] & 0x7F) << shift
        if not byte[0] & 0x80:
            return result
        shift += 7
        if shift > 35:
            raise ValueError("VarInt too long")


def read_exactly(sock: socket.socket, size: int) -> bytes:
    data = bytearray()
    while len(data) < size:
        chunk = sock.recv(size - len(data))
        if not chunk:
            raise EOFError("connection closed")
        data.extend(chunk)
    return bytes(data)


def packet(packet_id: int, payload: bytes) -> bytes:
    body = varint(packet_id) + payload
    return varint(len(body)) + body


def main() -> int:
    host, port = sys.argv[1], int(sys.argv[2])
    timeout = float(sys.argv[3]) if len(sys.argv) > 3 else 5.0
    address = host.encode("utf-8")
    # Protocol 770 (1.21.5); the server answers with its own version anyway.
    handshake = varint(770) + varint(len(address)) + address + port.to_bytes(2, "big") + varint(1)
    with socket.create_connection((host, port), timeout=timeout) as sock:
        sock.sendall(packet(0, handshake) + packet(0, b""))
        read_varint(sock)  # the answer's length
        if read_varint(sock) != 0:
            print("unexpected packet id", file=sys.stderr)
            return 1
        status = json.loads(read_exactly(sock, read_varint(sock)).decode("utf-8"))
    print(json.dumps(status, indent=2))
    return 0 if isinstance(status, dict) and "version" in status else 1


if __name__ == "__main__":
    sys.exit(main())
