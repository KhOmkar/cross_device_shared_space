# Implementation Spec: Anonymous Local File Transfer Tool

## Prompt for AI Coding Agent

You are implementing a file-sharing system that lets a phone transfer files
to/from an untrusted guest device (e.g. a public library or school PC)
with **zero setup on the guest side** — no login, no install, no download,
no third-party cloud involvement, and no personal information exchanged
beyond an ephemeral session code. Build this exactly to the spec below,
flagging any deviation you believe is necessary and why.

---

## 1. Project Goals & Constraints

- **No setup on guest device**: guest only opens a browser and joins a WiFi
  network. No app install, no account, no download.
- **No third-party cloud**: no data (files, metadata, filenames) should
  ever pass through or be stored on infrastructure the user does not
  control.
- **Fast & secure**: local-network-speed transfer, encrypted in transit.
- **Anonymous**: no persistent identifiers, no accounts, no logs tying a
  transfer to a person. Only an ephemeral session token is exchanged.

## 2. V1 Architecture (build this first)

**Topology**: Phone enables its own mobile hotspot. Guest device joins that
hotspot's WiFi. Phone runs a local server. No internet connection is
required for the transfer itself.

**Components**:
1. **Phone app** (Android) running an embedded HTTP + WebSocket server.
2. **Single-page web frontend**, served by the phone, opened by the guest
   browser. No external assets/CDNs — bundle everything locally so it
   works with zero internet access.
3. **Session/pairing layer**: phone generates a random session token,
   displayed as a QR code and as a short manually-typeable code.
4. **Transfer layer**: WebSocket-based binary chunked streaming (not
   repeated HTTP POST per chunk — see protocol below).

**Endpoints**:
- `GET /` → serves the static page (includes the session token embedded or
  fetched via `/session-info`)
- `GET /session-info` → returns `{ token_required: true, expires_at, max_file_size }` (no token needed to hit this, but every other endpoint validates the token)
- `WS /transfer?token=<session_token>` → upgrades to WebSocket, all file
  data flows here after this
- Do **not** implement open file listing/browsing endpoints in V1 — only
  explicit transfer initiated per-session.

**Transfer protocol over the WebSocket**:
- Small JSON/text control frames for metadata: `{type: "meta", filename, size, chunkSize, totalChunks}` and `{type: "done", checksum}`
- Binary frames for actual file bytes, chunk size ~1–4MB, sent via
  `ArrayBuffer`, no base64/JSON wrapping of file bytes themselves
- Sender must respect `bufferedAmount` on the WebSocket and pause sending
  when it grows past a threshold (backpressure) rather than queuing
  unbounded data in memory
- Receiver buffers incoming chunks in memory (e.g. 4–8MB) before flushing
  to disk in larger sequential writes, not per-chunk disk writes
- Compute and verify a checksum (e.g. SHA-256, streamed/incremental) at
  transfer completion to confirm integrity

## 3. Abstraction Boundary for Future V2 (WebRTC upgrade)

Design a `TransferChannel` interface (`connect()`, `send(fileStream)`,
`onReceive(callback)`, `close()`) so V1 implements it over the local
WebSocket, and a later V2 can implement the same interface over a WebRTC
data channel for cross-network transfers (when devices aren't on the same
hotspot). Do not let UI code or session/pairing code depend on which
implementation is active. Similarly, keep the pairing/code-generation flow
decoupled from "how to reach the peer" (local IP now, signaling-discovered
address later) so V2 can reuse it unchanged.

V2 is explicitly **out of scope for this implementation pass** — just
don't build anything that would need to be torn up to add it later.

---

## 4. Security, Risk & Encryption — Required Considerations

Treat every item below as a requirement to address in the implementation,
not optional hardening.

### 4.1 Transport & Network Security
- The hotspot's WPA2/WPA3 encryption already secures the WiFi link itself,
  but implement **application-layer encryption in addition**, don't rely
  solely on link-layer security:
  - Derive a symmetric session key from the pairing code using a
    password-authenticated key exchange (e.g. SPAKE2) rather than sending
    the code itself as the key — this protects against a compromised or
    misbehaving relay/network layer being able to read anything even if
    WiFi-layer security were somehow bypassed.
  - Encrypt file chunks with this derived key (e.g. AES-GCM) before they
    hit the WebSocket, so payload confidentiality does not depend entirely
    on the transport.
- Consider serving over `https://`/`wss://` using a self-signed certificate
  generated fresh per app install, with the guest page trust-on-first-use
  accepting it — mitigates any local network snooping beyond WPA encryption
  (e.g. a misconfigured hotspot falling back to open/unencrypted mode).
  Flag to the user clearly if the hotspot ever runs unencrypted.
- **Evil-twin / rogue AP risk**: someone could stand up a WiFi network with
  a similar name near the real hotspot to trick the guest into joining the
  wrong network. Mitigate by displaying a distinctive, randomly-generated
  hotspot name per session rather than a fixed/predictable name, and by
  having the session token itself be the actual trust anchor (a rogue AP
  can't forge a valid token or a valid session key from it).

### 4.2 Session & Access Control
- Session tokens must be generated with a cryptographically secure random
  source, sufficiently long (e.g. 128-bit), not sequential or guessable.
- Tokens are **single-session, short-lived** (a few minutes idle timeout,
  hard expiry regardless of activity, e.g. 30–60 min max).
- Every request/WebSocket connection other than the initial static page
  load must validate the token; reject anything invalid or expired.
- Rate-limit token/code entry attempts on the pairing endpoint to prevent
  brute-forcing a short manual code (e.g. lock out after 5 failed attempts
  for a short cooldown).
- Only one active guest connection per session token — reject a second
  concurrent connection attempt with a valid token as a signal something
  is wrong (or explicitly design for intentional multi-guest support if
  the user wants that — confirm with the user before allowing it).

### 4.3 File Handling Risks
- **Path traversal**: never construct file paths from client-supplied
  filenames directly. Generate internal storage names (UUIDs) and keep
  the user-facing filename purely as metadata.
- **Size limits**: enforce a max file size and max total session transfer
  size server-side (not just client-side), to prevent storage exhaustion
  or memory exhaustion attacks from a malicious/compromised guest device.
- **Untrusted content**: files received from the guest device must be
  treated as untrusted input. Do not auto-open, auto-execute, or auto-
  preview them. Store to a clearly-separated, non-executable location.
- **No content inspection/execution**: the server should never attempt to
  parse, render, or execute uploaded file contents (e.g. no automatic
  thumbnailing of arbitrary files, no archive auto-extraction) — this
  avoids an entire class of parser-exploit vulnerabilities.
- **Zip/decompression bombs**: if any compression/decompression is added
  later, cap decompressed size and reject files that expand beyond a
  sane ratio.

### 4.4 Anonymity & Data Minimization
- Do not log guest IP, device name, browser user-agent, or filenames to
  persistent storage. In-memory/session-scoped logging for debugging is
  acceptable but must be wiped when the session ends or the app closes.
- Do not request or display any personal information at any point in the
  flow — the UI should never have a name/email field.
- Wipe temporary buffers and any partially-written files if a transfer is
  cancelled or a session expires mid-transfer.
- On session end, actively clear the session key and token from memory,
  not just let them go out of scope.

### 4.5 Web Frontend Security (guest-facing page)
- Set a strict Content-Security-Policy (no inline scripts, no remote
  origins allowed) since the page is served with no internet dependency
  anyway — this also protects against XSS if any user-controlled data
  (e.g. filenames) is ever rendered into the DOM. Always render filenames
  as text content, never via `innerHTML`.
- No cookies, no localStorage/sessionStorage usage that would persist
  identifying data on a shared/public machine — keep all session state in
  memory (JS variables) for the life of the tab only.
- Warn the user in the UI that they're on a public/shared computer and
  should use a private/incognito window, and that files they download
  will land in that PC's Downloads folder (out of the app's control) —
  this is a real residual risk to disclose, not something the app can fix.

### 4.6 Platform / OS-Level Risks
- Android hotspot APIs vary significantly by OS version — verify what's
  programmatically controllable vs. what requires the user to manually
  enable hotspot via system settings, and design the app flow to prompt
  the user through it rather than assuming programmatic control.
- Battery and mobile radio usage: hosting a hotspot + server is
  battery-intensive; surface this to the user, don't silently drain
  their phone.
- Foreground service requirements: Android will kill background services
  aggressively; the server likely needs a foreground service with a
  persistent notification while a session is active, so transfers don't
  silently die if the user switches apps.

### 4.7 Explicitly Out-of-Scope Risks to Disclose to the User (not fixable by this app)
- The guest PC itself may be compromised (keylogger, screen recording,
  malicious browser extension) — this app cannot protect against a
  compromised host machine; the trust boundary ends at the browser tab.
- Shoulder-surfing of the QR code/pairing code by a bystander is a
  physical-security risk outside the app's control.
- If the guest PC's browser caches the page or downloaded files despite
  incognito mode (e.g. enterprise-managed browser policies overriding
  privacy settings), that's outside what the app can guarantee — disclose
  this rather than claim absolute anonymity.

---

## 5. Suggested Build Order for the Agent

1. Local server (HTTP static file serving + WebSocket upgrade) on the
   phone, session token generation, QR/code display.
2. Guest page loads, pairs with token, establishes WebSocket.
3. One-directional transfer (phone → guest) with chunking, backpressure,
   checksum verification.
4. Add guest → phone direction.
5. Add application-layer encryption (SPAKE2 key derivation + AES-GCM on
   chunks) — do not treat this as optional polish, implement before
   considering V1 complete.
6. Add session expiry, rate limiting, size limits, path-traversal-safe
   storage.
7. UI polish: progress bars, cancel/retry, clear session-end state wipe.
8. Write down (but do not implement) the `TransferChannel` abstraction
   points for the future WebRTC V2, so the next phase can slot in cleanly.

## 6. Deliverables Expected

- Phone app source (Android)
- Frontend source (single bundle, no external CDN dependencies)
- A short README documenting: how session tokens are generated, how the
  encryption key is derived, what data (if any) is logged and where it's
  cleared, and known limitations from section 4.7.
