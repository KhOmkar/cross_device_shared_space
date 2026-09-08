#!/usr/bin/env python3
"""
Anonymous Local File Transfer - Standalone Mock Server
Replicates the Android embedded HTTP & WebSocket server, SPAKE2 PAKE key exchange,
and AES-256-GCM chunk streaming protocol.
"""

import os
import sys
import json
import time
import struct
import base64
import hashlib
import secrets
import socket
import threading
from urllib.parse import urlparse, parse_qs

# Cryptography for PAKE & AES-GCM
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import hashes, serialization, hmac
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
CSP_HEADER = "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; connect-src 'self' ws: wss:; object-src 'none'; base-uri 'none'; frame-ancestors 'none';"

class SessionManager:
    def __init__(self, token=None, code="123456"):
        self.session_token = token or secrets.token_hex(16)
        self.pairing_code = code
        self.created_at = time.time()
        self.expires_at = self.created_at + 1800 # 30 mins
        self.last_activity = time.time()
        self.active_guest_ip = None
        self.failed_attempts = {}
        self.lockout_duration = 30 # seconds
        self.max_attempts = 5

    def is_allowed(self, ip):
        now = time.time()
        if ip in self.failed_attempts:
            count, lock_until = self.failed_attempts[ip]
            if lock_until > now:
                return False
            if lock_until > 0 and lock_until <= now:
                self.failed_attempts[ip] = (0, 0)
        return True

    def record_failure(self, ip):
        now = time.time()
        count, _ = self.failed_attempts.get(ip, (0, 0))
        count += 1
        lock_until = now + self.lockout_duration if count >= self.max_attempts else 0
        self.failed_attempts[ip] = (count, lock_until)
        return count

    def record_success(self, ip):
        if ip in self.failed_attempts:
            del self.failed_attempts[ip]

    def validate_code(self, code):
        if not code:
            return False
        return self.pairing_code.replace("-", "").strip() == str(code).replace("-", "").strip()


def derive_pake_password_bits(code: str, session_id: str = "anonymous-file-share-v1") -> bytes:
    kdf = PBKDF2HMAC(
        algorithm=hashes.SHA256(),
        length=32,
        salt=b"anonymous-file-share-v1",
        iterations=10000
    )
    return kdf.derive(code.encode('utf-8'))


def derive_master_key(ecdh_secret: bytes, password_bits: bytes) -> bytes:
    combined = ecdh_secret + password_bits
    hkdf = HKDF(
        algorithm=hashes.SHA256(),
        length=32,
        salt=b"anonymous-file-share-salt",
        info=b"AnonymousFileShare-V1-AESGCM"
    )
    return hkdf.derive(combined)


def compute_auth_confirmation(session_key: bytes, label: str) -> str:
    h = hmac.HMAC(session_key, hashes.SHA256())
    h.update(label.encode('utf-8'))
    return h.finalize().hex()


class WebSocketConnection:
    def __init__(self, sock):
        self.sock = sock
        self.closed = False

    def read_frame(self):
        if self.closed:
            return None
        try:
            head = self.sock.recv(2)
            if len(head) < 2:
                self.closed = True
                return None

            b1, b2 = head[0], head[1]
            fin = (b1 & 0x80) != 0
            opcode = b1 & 0x0F
            is_masked = (b2 & 0x80) != 0
            length = b2 & 0x7F

            if length == 126:
                ext = self.sock.recv(2)
                length = struct.unpack("!H", ext)[0]
            elif length == 127:
                ext = self.sock.recv(8)
                length = struct.unpack("!Q", ext)[0]

            mask = self.sock.recv(4) if is_masked else None

            payload = bytearray()
            while len(payload) < length:
                chunk = self.sock.recv(min(65536, length - len(payload)))
                if not chunk:
                    self.closed = True
                    return None
                payload.extend(chunk)

            if is_masked and mask:
                for i in range(len(payload)):
                    payload[i] ^= mask[i % 4]

            if opcode == 0x01: # Text
                return ("text", payload.decode('utf-8'))
            elif opcode == 0x02: # Binary
                return ("binary", bytes(payload))
            elif opcode == 0x08: # Close
                self.closed = True
                return ("close", None)
            elif opcode == 0x09: # Ping
                self.send_pong(bytes(payload))
                return ("ping", None)
            return ("other", bytes(payload))
        except Exception:
            self.closed = True
            return None

    def send_text(self, message: str):
        self._send_frame(0x01, message.encode('utf-8'))

    def send_binary(self, data: bytes):
        self._send_frame(0x02, data)

    def send_pong(self, data: bytes = b""):
        self._send_frame(0x0A, data)

    def close(self):
        if not self.closed:
            self.closed = True
            try:
                self._send_frame(0x08, b"\x03\xe8")
                self.sock.close()
            except Exception:
                pass

    def _send_frame(self, opcode: int, payload: bytes):
        if self.closed:
            return
        frame = bytearray()
        frame.append(0x80 | (opcode & 0x0F))
        length = len(payload)
        if length <= 125:
            frame.append(length)
        elif length <= 65535:
            frame.append(126)
            frame.extend(struct.pack("!H", length))
        else:
            frame.append(127)
            frame.extend(struct.pack("!Q", length))
        frame.extend(payload)
        self.sock.sendall(frame)


class MockServer:
    def __init__(self, host="0.0.0.0", port=8080, session_code="123456"):
        self.host = host
        self.port = port
        self.session_manager = SessionManager(code=session_code)
        self.running = False
        self.server_sock = None
        self.frontend_path = os.path.join(os.path.dirname(__file__), "..", "frontend", "index.html")

    def start(self):
        self.running = True
        self.server_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server_sock.bind((self.host, self.port))
        self.server_sock.listen(10)

        print(f"============================================================")
        print(f"[+] Anonymous File Share Server Started on http://localhost:{self.port}")
        print(f"[*] Ephemeral Pairing Code: {self.session_manager.pairing_code}")
        print(f"[*] Direct Pairing URL: http://localhost:{self.port}/?token={self.session_manager.session_token}&code={self.session_manager.pairing_code}")
        print(f"============================================================")

        while self.running:
            try:
                client_sock, addr = self.server_sock.accept()
                threading.Thread(target=self.handle_client, args=(client_sock, addr), daemon=True).start()
            except Exception:
                break

    def stop(self):
        self.running = False
        if self.server_sock:
            try:
                self.server_sock.close()
            except Exception:
                pass

    def handle_client(self, sock, addr):
        client_ip = addr[0]
        try:
            req_data = sock.recv(4096).decode('utf-8', errors='ignore')
            if not req_data:
                sock.close()
                return

            lines = req_data.split("\r\n")
            request_line = lines[0].split(" ")
            if len(request_line) < 2:
                sock.close()
                return

            method, full_path = request_line[0], request_line[1]
            headers = {}
            for line in lines[1:]:
                if not line:
                    break
                if ":" in line:
                    k, v = line.split(":", 1)
                    headers[k.strip().lower()] = v.strip()

            parsed = urlparse(full_path)
            path = parsed.path
            params = parse_qs(parsed.query)

            if path == "/" and method == "GET":
                self.serve_frontend(sock)
            elif path == "/session-info" and method == "GET":
                self.serve_session_info(sock)
            elif path == "/transfer" and method == "GET":
                self.handle_ws_upgrade(sock, headers, params, client_ip)
            else:
                sock.sendall(b"HTTP/1.1 404 Not Found\r\nContent-Length: 9\r\n\r\nNot Found")
                sock.close()
        except Exception as e:
            try:
                sock.close()
            except Exception:
                pass

    def serve_frontend(self, sock):
        try:
            with open(self.frontend_path, "rb") as f:
                content = f.read()
            resp = (
                b"HTTP/1.1 200 OK\r\n"
                b"Content-Type: text/html; charset=UTF-8\r\n"
                b"Content-Length: " + str(len(content)).encode() + b"\r\n"
                b"Cache-Control: no-cache, no-store, must-revalidate\r\n"
                b"Content-Security-Policy: " + CSP_HEADER.encode() + b"\r\n"
                b"Connection: close\r\n\r\n" + content
            )
            sock.sendall(resp)
        except Exception as e:
            sock.sendall(b"HTTP/1.1 500 Internal Error\r\n\r\n" + str(e).encode())
        finally:
            sock.close()

    def serve_session_info(self, sock):
        info = json.dumps({
            "token_required": True,
            "expires_at": int(self.session_manager.expires_at * 1000),
            "max_file_size": 2 * 1024 * 1024 * 1024
        }).encode('utf-8')
        resp = (
            b"HTTP/1.1 200 OK\r\n"
            b"Content-Type: application/json\r\n"
            b"Content-Length: " + str(len(info)).encode() + b"\r\n"
            b"Cache-Control: no-store\r\n"
            b"Connection: close\r\n\r\n" + info
        )
        sock.sendall(resp)
        sock.close()

    def handle_ws_upgrade(self, sock, headers, params, client_ip):
        if not self.session_manager.is_allowed(client_ip):
            sock.sendall(b"HTTP/1.1 429 Too Many Requests\r\nContent-Length: 17\r\n\r\nRate limit active")
            sock.close()
            return

        sec_key = headers.get("sec-websocket-key")
        if not sec_key:
            sock.sendall(b"HTTP/1.1 400 Bad Request\r\n\r\nMissing Sec-WebSocket-Key")
            sock.close()
            return

        accept_val = base64.b64encode(hashlib.sha1((sec_key + WS_GUID).encode('utf-8')).digest()).decode('utf-8')
        upgrade_resp = (
            "HTTP/1.1 101 Switching Protocols\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Accept: {accept_val}\r\n\r\n"
        )
        sock.sendall(upgrade_resp.encode('utf-8'))

        ws = WebSocketConnection(sock)
        self.run_ws_transfer_protocol(ws, client_ip)

    def run_ws_transfer_protocol(self, ws, client_ip):
        session_key = None
        current_transfer = None

        try:
            # 1. PAKE Handshake
            frame = ws.read_frame()
            if not frame or frame[0] != "text":
                ws.close()
                return

            init_msg = json.loads(frame[1])
            if init_msg.get("type") != "pake_init":
                ws.close()
                return

            code = init_msg.get("code")
            if not self.session_manager.validate_code(code):
                self.session_manager.record_failure(client_ip)
                ws.send_text(json.dumps({"type": "error", "message": "Invalid pairing code"}))
                ws.close()
                return

            client_pub_hex = init_msg.get("client_pub")
            client_pub_bytes = bytes.fromhex(client_pub_hex)

            # Generate Server P-256 KeyPair
            server_priv = ec.generate_private_key(ec.SECP256R1())
            server_pub_bytes = server_priv.public_key().public_bytes(
                encoding=serialization.Encoding.X962,
                format=serialization.PublicFormat.UncompressedPoint
            )

            # Import Client P-256 Public Key
            client_pub = ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), client_pub_bytes)

            # Compute ECDH Shared Secret
            ecdh_secret = server_priv.exchange(ec.ECDH(), client_pub)

            # Derive Password Bits & Master AES-256 Key
            password_bits = derive_pake_password_bits(self.session_manager.pairing_code, self.session_manager.session_token)
            session_key = derive_master_key(ecdh_secret, password_bits)

            # Send Server Public Key
            ws.send_text(json.dumps({
                "type": "pake_resp",
                "server_pub": server_pub_bytes.hex()
            }))

            # 2. Receive Client Auth Confirmation
            auth_frame = ws.read_frame()
            if not auth_frame or auth_frame[0] != "text":
                ws.close()
                return

            auth_msg = json.loads(auth_frame[1])
            expected_auth = compute_auth_confirmation(session_key, "ClientAuth")
            if auth_msg.get("auth", "").lower() != expected_auth.lower():
                self.session_manager.record_failure(client_ip)
                ws.send_text(json.dumps({"type": "error", "message": "Authentication failed"}))
                ws.close()
                return

            # Send PAKE Confirmed
            server_auth = compute_auth_confirmation(session_key, "ServerAuth")
            ws.send_text(json.dumps({
                "type": "pake_confirmed",
                "server_auth": server_auth
            }))

            self.session_manager.record_success(client_ip)
            print(f"[+] Authenticated & Encrypted session established with {client_ip}")

            # 3. File Transfer Loop
            aesgcm = AESGCM(session_key)

            while not ws.closed:
                frame = ws.read_frame()
                if not frame:
                    break

                kind, payload = frame
                if kind == "text":
                    msg = json.loads(payload)
                    if msg.get("type") == "meta":
                        current_transfer = {
                            "id": msg["transferId"],
                            "filename": msg["filename"],
                            "size": msg["size"],
                            "hasher": hashlib.sha256(),
                            "received_bytes": 0,
                            "chunks": []
                        }
                        print(f"[*] Incoming file: {msg['filename']} ({msg['size']} bytes)")
                    elif msg.get("type") == "done" and current_transfer:
                        expected_hash = msg["checksum"]
                        computed_hash = current_transfer["hasher"].hexdigest()
                        if computed_hash.lower() == expected_hash.lower():
                            print(f"[+] File verified successfully! SHA-256: {computed_hash}")
                            ws.send_text(json.dumps({
                                "type": "ack",
                                "transferId": current_transfer["id"],
                                "status": "ok",
                                "verified": True
                            }))
                        else:
                            print(f"[-] Checksum mismatch! Expected {expected_hash}, got {computed_hash}")
                            ws.send_text(json.dumps({
                                "type": "ack",
                                "transferId": current_transfer["id"],
                                "status": "error",
                                "message": "Checksum mismatch"
                            }))
                        current_transfer = None

                elif kind == "binary" and current_transfer:
                    # Binary frame: [SeqNum 4B][Nonce 12B][Ciphertext + Tag 16B]
                    seq_num = struct.unpack("!I", payload[0:4])[0]
                    nonce = payload[4:16]
                    ciphertext = payload[16:]

                    plaintext = aesgcm.decrypt(nonce, ciphertext, None)
                    current_transfer["hasher"].update(plaintext)
                    current_transfer["received_bytes"] += len(plaintext)
                    print(f"   Decrypted chunk #{seq_num} ({len(plaintext)} bytes) - Progress: {current_transfer['received_bytes']}/{current_transfer['size']}")

        except Exception as e:
            print(f"Session error with {client_ip}: {e}")
        finally:
            ws.close()


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
    server = MockServer(port=port)
    try:
        server.start()
    except KeyboardInterrupt:
        print("\nStopping server...")
        server.stop()
