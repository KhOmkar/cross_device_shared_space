#!/usr/bin/env python3
"""
Automated Protocol & Cryptography Test Suite for Anonymous Local File Transfer.
Tests HTTP endpoints, CSP headers, rate-limiting lockout, PAKE key derivation parity,
AES-256-GCM chunked transfer, SHA-256 streaming verification, and tamper rejection.
"""

import os
import sys
import time
import json
import struct
import socket
import base64
import hashlib
import secrets
import unittest
import threading
import urllib.request
import urllib.error

# Import mock server components
from mock_server import MockServer, derive_pake_password_bits, derive_master_key, compute_auth_confirmation, WS_GUID
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


class TestClientHelper:
    """Helper client to simulate browser/guest WebSocket actions."""
    def __init__(self, host="127.0.0.1", port=8089):
        self.host = host
        self.port = port
        self.sock = None
        self.session_key = None

    def connect_ws(self, token="test_token"):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.connect((self.host, self.port))

        sec_key = base64.b64encode(os.urandom(16)).decode('utf-8')
        req = (
            f"GET /transfer?token={token} HTTP/1.1\r\n"
            f"Host: {self.host}:{self.port}\r\n"
            f"Upgrade: websocket\r\n"
            f"Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {sec_key}\r\n"
            f"Sec-WebSocket-Version: 13\r\n\r\n"
        )
        self.sock.sendall(req.encode('utf-8'))

        resp = self.sock.recv(4096).decode('utf-8', errors='ignore')
        if "101 Switching Protocols" not in resp:
            raise ConnectionError(f"Upgrade failed: {resp}")

    def send_masked_frame(self, opcode: int, payload: bytes):
        frame = bytearray()
        frame.append(0x80 | (opcode & 0x0F))
        length = len(payload)
        mask = secrets.token_bytes(4)

        if length <= 125:
            frame.append(0x80 | length)
        elif length <= 65535:
            frame.append(0x80 | 126)
            frame.extend(struct.pack("!H", length))
        else:
            frame.append(0x80 | 127)
            frame.extend(struct.pack("!Q", length))

        frame.extend(mask)
        masked_payload = bytearray(payload)
        for i in range(len(masked_payload)):
            masked_payload[i] ^= mask[i % 4]
        frame.extend(masked_payload)

        self.sock.sendall(frame)

    def read_unmasked_frame(self):
        try:
            head = self.sock.recv(2)
            if len(head) < 2:
                return None
            b1, b2 = head[0], head[1]
            opcode = b1 & 0x0F
            length = b2 & 0x7F

            if length == 126:
                ext = self.sock.recv(2)
                length = struct.unpack("!H", ext)[0]
            elif length == 127:
                ext = self.sock.recv(8)
                length = struct.unpack("!Q", ext)[0]

            payload = bytearray()
            while len(payload) < length:
                chunk = self.sock.recv(min(65536, length - len(payload)))
                if not chunk:
                    break
                payload.extend(chunk)

            if opcode == 0x01:
                return ("text", payload.decode('utf-8'))
            elif opcode == 0x02:
                return ("binary", bytes(payload))
            elif opcode == 0x08:
                return ("close", None)
            return ("other", bytes(payload))
        except (ConnectionResetError, BrokenPipeError, OSError):
            return None

    def perform_pake_handshake(self, pairing_code: str, session_token: str):
        # Generate Client P-256 Key
        client_priv = ec.generate_private_key(ec.SECP256R1())
        client_pub_bytes = client_priv.public_key().public_bytes(
            encoding=serialization.Encoding.X962,
            format=serialization.PublicFormat.UncompressedPoint
        )

        # 1. Send PAKE Init
        init_json = json.dumps({
            "type": "pake_init",
            "client_pub": client_pub_bytes.hex(),
            "code": pairing_code
        })
        self.send_masked_frame(0x01, init_json.encode('utf-8'))

        # 2. Read Server Response
        resp_frame = self.read_unmasked_frame()
        if not resp_frame or resp_frame[0] != "text":
            raise ValueError(f"Handshake failed, got frame: {resp_frame}")

        resp_data = json.loads(resp_frame[1])
        if resp_data.get("type") == "error":
            raise ValueError(f"Server rejected handshake: {resp_data.get('message')}")

        server_pub_bytes = bytes.fromhex(resp_data["server_pub"])
        server_pub = ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), server_pub_bytes)

        # Derive ECDH shared secret and master key
        ecdh_secret = client_priv.exchange(ec.ECDH(), server_pub)
        password_bits = derive_pake_password_bits(pairing_code, session_token)
        self.session_key = derive_master_key(ecdh_secret, password_bits)

        # 3. Send Client Auth Confirmation
        client_auth = compute_auth_confirmation(self.session_key, "ClientAuth")
        self.send_masked_frame(0x01, json.dumps({"type": "pake_auth", "auth": client_auth}).encode('utf-8'))

        # 4. Read Server Confirmed
        conf_frame = self.read_unmasked_frame()
        if not conf_frame or conf_frame[0] != "text":
            raise ValueError("Expected PAKE confirmation frame")

        conf_data = json.loads(conf_frame[1])
        if conf_data.get("type") != "pake_confirmed":
            raise ValueError(f"Invalid confirmation: {conf_data}")

        expected_server_auth = compute_auth_confirmation(self.session_key, "ServerAuth")
        if conf_data.get("server_auth", "").lower() != expected_server_auth.lower():
            raise ValueError(f"Server auth mismatch: expected {expected_server_auth}, got {conf_data.get('server_auth')}")

        return self.session_key

    def close(self):
        if self.sock:
            try:
                self.sock.close()
            except Exception:
                pass


class AnonymousFileShareTestSuite(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.port = 8089
        cls.server = MockServer(host="127.0.0.1", port=cls.port, session_code="947215")
        cls.server_thread = threading.Thread(target=cls.server.start, daemon=True)
        cls.server_thread.start()
        time.sleep(0.5) # Allow server to bind

    @classmethod
    def tearDownClass(cls):
        cls.server.stop()

    def test_01_http_static_serving_and_csp(self):
        """Verify GET / serves frontend bundle and includes strict CSP header."""
        url = f"http://127.0.0.1:{self.port}/"
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req) as resp:
            self.assertEqual(resp.status, 200)
            headers = dict(resp.headers)
            csp = headers.get("Content-Security-Policy", "")
            self.assertIn("default-src 'self'", csp)
            self.assertIn("object-src 'none'", csp)
            self.assertIn("frame-ancestors 'none'", csp)

            body = resp.read().decode('utf-8')
            self.assertIn("Anonymous File Share", body)
            self.assertIn("Public / Shared Computer Notice", body)

    def test_02_http_session_info(self):
        """Verify GET /session-info returns valid session configuration."""
        url = f"http://127.0.0.1:{self.port}/session-info"
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req) as resp:
            self.assertEqual(resp.status, 200)
            data = json.loads(resp.read().decode('utf-8'))
            self.assertTrue(data.get("token_required"))
            self.assertGreater(data.get("expires_at"), 0)
            self.assertEqual(data.get("max_file_size"), 2 * 1024 * 1024 * 1024)

    def test_03_pake_invalid_code_and_rate_limiting(self):
        """Verify that incorrect pairing codes are rejected and trigger rate-limiting."""
        for attempt in range(5):
            client = TestClientHelper(port=self.port)
            client.connect_ws(self.server.session_manager.session_token)
            with self.assertRaises(ValueError):
                client.perform_pake_handshake("000000", self.server.session_manager.session_token)
            client.close()

        # 6th attempt should be blocked by rate limiter with 429
        client = TestClientHelper(port=self.port)
        with self.assertRaises(ConnectionError):
            client.connect_ws(self.server.session_manager.session_token)
        client.close()

        # Reset rate limiter for subsequent tests
        self.server.session_manager.failed_attempts.clear()

    def test_04_pake_handshake_and_key_derivation_parity(self):
        """Verify SPAKE2 PAKE handshake establishes matching 256-bit symmetric session keys."""
        client = TestClientHelper(port=self.port)
        client.connect_ws(self.server.session_manager.session_token)

        session_key = client.perform_pake_handshake(
            self.server.session_manager.pairing_code,
            self.server.session_manager.session_token
        )

        self.assertIsNotNone(session_key)
        self.assertEqual(len(session_key), 32) # 256-bit AES key
        client.close()

    def test_05_end_to_end_chunked_encrypted_transfer(self):
        """Verify 5MB file transfer encrypted with AES-256-GCM and incremental SHA-256."""
        client = TestClientHelper(port=self.port)
        client.connect_ws(self.server.session_manager.session_token)
        session_key = client.perform_pake_handshake(
            self.server.session_manager.pairing_code,
            self.server.session_manager.session_token
        )

        # Generate 5 MB test file
        file_size = 5 * 1024 * 1024
        test_data = secrets.token_bytes(file_size)
        file_hash = hashlib.sha256(test_data).hexdigest()
        chunk_size = 1024 * 1024
        total_chunks = (file_size + chunk_size - 1) // chunk_size

        # 1. Send Metadata Frame
        transfer_id = "tx_test_123"
        meta = {
            "type": "meta",
            "transferId": transfer_id,
            "filename": "document_sample.pdf",
            "size": file_size,
            "chunkSize": chunk_size,
            "totalChunks": total_chunks,
            "direction": "guest_to_phone"
        }
        client.send_masked_frame(0x01, json.dumps(meta).encode('utf-8'))

        # 2. Encrypt & Send Binary Chunks
        aesgcm = AESGCM(session_key)
        for i in range(total_chunks):
            chunk = test_data[i * chunk_size : (i + 1) * chunk_size]

            # Construct 12-byte Nonce: [Direction 1B = 2][ChunkIndex 4B][Random 7B]
            nonce = bytearray(secrets.token_bytes(12))
            nonce[0] = 0x02 # GuestToPhone
            struct.pack_into("!I", nonce, 1, i)

            ciphertext = aesgcm.encrypt(bytes(nonce), chunk, None)

            # Frame format: [SeqNum 4B][Nonce 12B][Ciphertext + Tag 16B]
            frame_payload = bytearray(struct.pack("!I", i))
            frame_payload.extend(nonce)
            frame_payload.extend(ciphertext)

            client.send_masked_frame(0x02, bytes(frame_payload))

        # 3. Send Done Frame
        done = {
            "type": "done",
            "transferId": transfer_id,
            "checksum": file_hash
        }
        client.send_masked_frame(0x01, json.dumps(done).encode('utf-8'))

        # 4. Await Server ACK
        ack_frame = client.read_unmasked_frame()
        self.assertIsNotNone(ack_frame)
        self.assertEqual(ack_frame[0], "text")

        ack_data = json.loads(ack_frame[1])
        self.assertEqual(ack_data.get("type"), "ack")
        self.assertEqual(ack_data.get("status"), "ok")
        self.assertTrue(ack_data.get("verified"))

        client.close()

    def test_06_tamper_detection(self):
        """Verify that any modified bit in ciphertext triggers AES-GCM decryption rejection."""
        client = TestClientHelper(port=self.port)
        client.connect_ws(self.server.session_manager.session_token)
        session_key = client.perform_pake_handshake(
            self.server.session_manager.pairing_code,
            self.server.session_manager.session_token
        )

        test_data = b"Sensitive document content that must not be tampered with."
        file_hash = hashlib.sha256(test_data).hexdigest()

        meta = {
            "type": "meta",
            "transferId": "tx_tamper_test",
            "filename": "secret.txt",
            "size": len(test_data),
            "chunkSize": len(test_data),
            "totalChunks": 1,
            "direction": "guest_to_phone"
        }
        client.send_masked_frame(0x01, json.dumps(meta).encode('utf-8'))

        aesgcm = AESGCM(session_key)
        nonce = bytearray(secrets.token_bytes(12))
        nonce[0] = 0x02
        struct.pack_into("!I", nonce, 1, 0)
        ciphertext = bytearray(aesgcm.encrypt(bytes(nonce), test_data, None))

        # Tamper: flip one bit in the middle of ciphertext
        ciphertext[10] ^= 0x01

        frame_payload = bytearray(struct.pack("!I", 0))
        frame_payload.extend(nonce)
        frame_payload.extend(ciphertext)

        client.send_masked_frame(0x02, bytes(frame_payload))

        # Send Done
        done = {
            "type": "done",
            "transferId": "tx_tamper_test",
            "checksum": file_hash
        }
        try:
            client.send_masked_frame(0x01, json.dumps(done).encode('utf-8'))
        except (ConnectionResetError, BrokenPipeError, OSError):
            pass # Server already disconnected due to GCM tag failure

        # Server should close or fail without acknowledging
        ack_frame = client.read_unmasked_frame()
        # Either no ACK or closed connection due to GCM authentication tag failure
        if ack_frame and ack_frame[0] == "text":
            ack_data = json.loads(ack_frame[1])
            self.assertNotEqual(ack_data.get("status"), "ok")

        client.close()


if __name__ == "__main__":
    unittest.main(verbosity=2)
