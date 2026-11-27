"""Operator smoke test: two TCP peers relay through an Android room.

Usage: python test_mesh_wifi.py code-ui.xml room-ui.xml
XML files are local ADB UI dumps from a disposable test room; key is never printed.
Requires cryptography. No cloud API, real-account token or database access.
"""
import base64
import os
import re
import socket
import struct
import sys
import time
import uuid
import xml.etree.ElementTree as ET
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


def texts(path):
    return [n.get("text", "") for n in ET.parse(path).iter("node")]


def receive(sock, size):
    data = b""
    while len(data) < size:
        chunk = sock.recv(size - len(data))
        if not chunk:
            raise EOFError("Peer closed")
        data += chunk
    return data


def main():
    code = next(t for t in texts(sys.argv[1]) if re.fullmatch(r"[A-Za-z0-9_-]{43}", t))
    address = next(t for t in texts(sys.argv[2]) if re.fullmatch(r"192\.168\.\d+\.\d+:\d+", t))
    host, port = address.split(":")
    cipher = AESGCM(base64.urlsafe_b64decode(code + "="))
    ident = uuid.uuid4().bytes
    issued = int(time.time() * 1000)
    aad = struct.pack(">I", 0x4D4D5331) + ident + struct.pack(">q", issued)
    # ASCII test strings have the same representation in Java modified UTF-8.
    name, message = b"Laptop test A", b"Offline Wi-Fi relay acceptance"
    plain = struct.pack(">H", len(name)) + name + struct.pack(">H", len(message)) + message
    nonce = os.urandom(12)
    encrypted = cipher.encrypt(nonce, plain, aad)
    packet = aad + struct.pack(">i", 4) + nonce + struct.pack(">i", len(encrypted)) + encrypted
    with socket.create_connection((host, int(port)), timeout=5) as a, socket.create_connection((host, int(port)), timeout=5) as c:
        time.sleep(0.3)
        a.sendall(packet)
        header = receive(c, 48)
        assert header[:28] == aad
        assert struct.unpack(">i", header[28:32])[0] == 3
        length = struct.unpack(">i", header[44:48])[0]
        assert 17 <= length <= 4608
        body = receive(c, length)
        assert cipher.decrypt(header[32:44], body, aad) == plain
        a.sendall(packet)
        c.settimeout(1)
        try:
            c.recv(1)
            raise AssertionError("Duplicate was relayed or connection dropped")
        except socket.timeout:
            pass
    print("PASS: two laptop peers -> Galaxy relay -> authenticated decryption; duplicate suppressed")


if __name__ == "__main__":
    main()
