# Anonymous Local File Transfer Tool

An anonymous, zero-install, local file-sharing system designed for transferring files between an Android phone and an untrusted guest computer (e.g. public library PC, school computer, cybercafé terminal) with **zero guest setup**, **zero cloud involvement**, **application-layer encryption**, and **strict ephemeral data minimization**.

---

## Architecture Overview

```
+-----------------------------+                  +------------------------------+
|     Phone (Host / Hotspot)  |                  |     Guest Device (Browser)   |
|                             |                  |                              |
|  - WifiManager SoftAP       |                  |  - Joins Phone Hotspot WiFi  |
|  - Embedded HTTP / WS Server|  <============>  |  - Opens http://192.168.43.1 |
|  - Foreground Service       |   Local WiFi     |  - Ephemeral Web App (No CDN)|
|  - SPAKE2 PAKE & AES-256-GCM|                  |  - WebCrypto SPAKE2 & AES-GCM|
+-----------------------------+                  +------------------------------+
```

### Core Components
1. **Android Application (`android/`)**:
   - Native Kotlin HTTP/1.1 and RFC 6455 WebSocket engine (`EmbeddedHttpServer`, `WebSocketHandler`).
   - `FileShareForegroundService` keeping server alive with persistent notification and CPU wake lock.
   - `StorageManager`: Path-traversal protected sandbox storage using UUID temporary filenames, sequential 4–8 MB disk flushes, streaming SHA-256 integrity verification, non-executable directory permissions.
   - `TransferChannel` abstraction layer enabling future WebRTC V2 upgrades without UI rewrites.

2. **Zero-Dependency Web Client (`frontend/`)**:
   - Single-file standalone bundle (`index.html`) embedded directly in Android assets (`assets/web/index.html`).
   - Strict Content Security Policy (`default-src 'self'`, `object-src 'none'`, `frame-ancestors 'none'`).
   - Text-only DOM rendering (zero `innerHTML` injection risk).
   - In-memory only state: Zero `localStorage`, `sessionStorage`, cookies, or IndexedDB persistence.

---

## Security & Cryptographic Details

### 1. Ephemeral Session Token & Pairing Code Generation
- **Session Token**: Generated using `SecureRandom` as a 128-bit cryptographically secure pseudorandom number (32 hex characters).
- **Manual Pairing Code**: A 6-digit high-contrast numeric code (e.g., `947-215`) for manual entry when camera scanning is unavailable.
- **QR Code**: Embeds `http://<ip>:8080/?token=<128_bit_token>&code=<pairing_code>` allowing instant automated connection and authentication.
- **Single-Guest Restriction**: The server strictly enforces a single concurrent guest connection per session token. Any secondary connection attempt is rejected.
- **Rate Limiting**: Failed pairing code submissions are tracked per IP. After 5 failed attempts, the IP is locked out for 30 seconds to prevent brute-force attacks on 6-digit codes.
- **Session Lifetime**:
  - Hard expiry: 30 minutes maximum lifetime.
  - Idle timeout: 5 minutes inactivity timeout.

### 2. Application-Layer Key Derivation (SPAKE2 PAKE)
In addition to Wi-Fi link-layer WPA2/WPA3 security, all file chunks are encrypted at the application layer:
1. **Blinding Secret**:
   $$w = \text{PBKDF2-HMAC-SHA256}(\text{password}=\text{pairing\_code}, \text{salt}=\text{session\_token}, \text{iterations}=10000, \text{length}=256\text{ bits})$$
2. **Ephemeral ECDH Key Exchange**:
   - Client and phone generate ephemeral NIST P-256 (secp256r1) key pairs.
   - Ephemeral public keys are exchanged over the WebSocket (`pake_init` and `pake_resp`).
   - Both sides compute the ECDH unblinded shared secret $S = \text{ECDH}(priv, peer\_pub)$.
3. **Master Key Derivation (HKDF)**:
   $$K_{\text{session}} = \text{HKDF-SHA256}(\text{IKM}=S \,||\, w, \text{salt}=\text{"anonymous-file-share-salt"}, \text{info}=\text{"AnonymousFileShare-V1-AESGCM"}, \text{len}=32\text{ bytes})$$
4. **Key Confirmation**:
   - Client confirms with $\text{Auth}_A = \text{SHA256}(\text{"ClientAuth"})$.
   - Server confirms with $\text{Auth}_B = \text{SHA256}(\text{"ServerAuth"})$.
5. **Payload Confidentiality**:
   Even if an adversary operates a rogue AP / evil-twin network or intercepts the local Wi-Fi traffic, they cannot decrypt the payloads without the ephemeral pairing code.

### 3. Chunk Streaming & AES-256-GCM Encryption
- File chunk size: $1\text{ MB} = 1,048,576\text{ bytes}$.
- Each binary frame transmitted over WebSocket is formatted as:
  ```
  +-----------------------+--------------------+---------------------------------------+
  | Sequence No. (4 Bytes)|  IV/Nonce (12 Bytes)| Encrypted Ciphertext + Auth Tag (16B) |
  +-----------------------+--------------------+---------------------------------------+
  ```
  - **IV/Nonce Construction**: Direction byte ($0\text{x}01$ Phone $\to$ Guest, $0\text{x}02$ Guest $\to$ Phone) $+$ 4-byte chunk index $+$ 7-byte random salt.
- **Streaming Hash & Verification**:
  - Sender and receiver incrementally compute `SHA-256` over the plaintext stream.
  - Final `{ type: "done", checksum: "<sha256_hex>" }` is validated before saving or triggering browser download.
  - Any ciphertext tampering triggers immediate GCM authentication failure and session teardown.

---

## Data Minimization & Privacy Policy

- **Zero Persistent Logs**: IP addresses, browser user-agents, device names, and transferred filenames are never written to disk or persistent storage.
- **Ephemeral In-Memory State**: Logs and transfer states exist only in volatile RAM for the active session duration.
- **Memory Wiping**:
  - Upon session termination, all secret keys, byte buffers, and tokens are actively overwritten (`Arrays.fill(bytes, 0.toByte())`).
- **Temporary File Cleansing**:
  - If a transfer is cancelled or fails checksum validation, partial `.part` temporary files are immediately deleted and unlinked.

---

## Disclosed Residual Risks (Section 4.7)

> [!CAUTION]
> The following risks stem from the guest environment and physical surroundings and cannot be mitigated by software:
>
> 1. **Compromised Guest Host**: If the library or school PC has pre-installed malware, keyloggers, screen recorders, or rogue browser extensions, data displayed or downloaded on that PC can be captured by the host machine.
> 2. **Guest Downloads Persistence**: Files downloaded by the guest browser land in the operating system's local Downloads folder. The web application cannot delete files once handed off to the browser's download manager. Users must manually delete downloaded files and empty the trash before leaving.
> 3. **Shoulder-Surfing**: Anyone physically viewing the phone screen can read the 6-digit pairing code or scan the QR code.
> 4. **Enterprise Browser Policies**: Enterprise-managed workstations may override incognito mode or cache browser artifacts regardless of HTTP no-store headers.

---

## Running & Testing

### Running the Python Mock Server (Interactive Verification)
```bash
python test_harness/mock_server.py 8080
```
Then open `http://localhost:8080` in your browser.

### Running Automated Test Suite
```bash
python test_harness/test_transfer.py
```

### Building the Android App
Open `android/` in Android Studio (or run `./gradlew assembleDebug`) and install `app-debug.apk` on your device.
