# AI Agent Handoff Brief: Cross-Device Shared Space App

> **Instructions for the AI agent reading this:** This is a complete project brief. Read it fully before writing any code. It contains all decisions already made — do not re-debate settled architecture choices unless you find a concrete technical blocker. If you do hit a blocker that forces a deviation, document it clearly in code comments and in your handoff summary for the next agent.

---

## 1. Project Summary

Build a **self-hosted, peer-to-peer, cross-device sync app** for personal use. No third-party cloud, no paid service, no central server storing data. Initial target platforms: **Linux ↔ Android** and **Android ↔ Android**.

The app has two independent features that share one underlying network/transport layer:

1. **Shared Tray** — an always-on, instantly-synced space for small content (text, links, images, small files)
2. **Large Files Space** — a reference/index of big files, with actual bytes transferred only on demand

---

## 2. Feature Spec

### 2.1 Shared Tray

| Property | Value |
|---|---|
| Content types | Plain text, links/URLs, images, small files |
| Size cap | 25 MB per item |
| Behavior on cap exceeded | Auto-redirect to Large Files Space instead of Tray |
| Sync behavior | Full copy pushed to all currently-connected paired peers immediately |
| Expiry | 24 hours after creation, auto-deleted on **all** devices (not just origin) |
| Input methods | Drag-and-drop, paste (Ctrl+V on desktop), Android share-sheet target |
| Display | Feed/list, newest first, tagged with content type + source device name |
| Persistence | Local encrypted SQLite per device; survives app restart until expiry |

### 2.2 Large Files Space

| Property | Value |
|---|---|
| Content | Folders/files explicitly "published" by the user (no automatic background scanning) |
| What syncs always | Metadata only: filename, size, checksum (hash), owning device ID, path |
| What syncs on demand | Actual file bytes — only when a user opens/requests a file AND both devices are online |
| Offline behavior | File still listed (from index) but marked "unavailable" if the owning device isn't reachable |
| Transfer method | Chunked, resumable transfer over the same P2P transport |

---

## 3. Finalized Architecture Decisions (do not re-litigate these)

- **No central server.** All connections are device-to-device (P2P).
- **Core networking library: `iroh`** (Rust) — handles peer discovery, QUIC-based transport, NAT hole-punching for internet-routed connections, and encrypted relay fallback when direct connection isn't possible. This replaces building mDNS + WireGuard + custom hole-punching separately.
- **Identity & pairing:** Ed25519 keypairs, generated locally on first run. Pairing between two devices happens once via **QR code exchange** (one device displays, the other scans) to exchange public keys. After pairing, devices trust each other permanently — no re-pairing, no passwords typed again.
- **Encryption:** All transport is encrypted by iroh/QUIC by default. Local on-device storage (SQLite) should also be encrypted at rest (e.g., SQLCipher) since it may hold synced personal content.
- **Connectivity tiers (must all be supported, app should auto-select best available in this order):**
  1. **Local network (LAN):** Direct connection via mDNS-style discovery (iroh handles this).
  2. **Internet, different networks:** NAT hole-punching via iroh; falls back to encrypted relay if hole-punching fails. Relay sees only ciphertext, never content.
  3. **No network at all (devices physically near, no Wi-Fi/no internet):** Android **Wi-Fi Direct** API — this is NOT handled by iroh and must be implemented natively on Android as a separate fallback transport. Treat this as a distinct code path: when no LAN/internet path is available, attempt to establish a Wi-Fi Direct link and route the same protocol messages over it.
  4. Items that can't sync immediately (no path available) should queue locally and flush automatically the moment any connectivity path becomes available. Never require the user to manually "retry" or "reconnect."

- **Platform/language choices:**
  - **Linux app:** Rust (pairs naturally with `iroh`, since iroh is a Rust library). Lightweight background daemon + minimal GUI (or CLI to start, GUI later).
  - **Android app:** Kotlin. Needs native APIs for: background service (foreground service or WorkManager to stay alive), Wi-Fi Direct (`WifiP2pManager`), and FFI bindings into the shared Rust core (iroh + protocol logic) via `uniffi` or similar Rust-Kotlin bridge — **do not reimplement the protocol logic separately in Kotlin; share the Rust core across platforms.**

- **Protocol/message format:** Define a shared schema (Protobuf recommended, or simple JSON if Protobuf adds too much friction early on) with at minimum these message types:
  - `tray_item` — payload: type (text/link/image/file), content/blob, source_device_id, created_at, expires_at, item_id
  - `tray_delete` — payload: item_id (broadcast when an item expires or is manually removed)
  - `file_index_update` — payload: filename, size, hash, owning_device_id, path, last_seen
  - `file_request` — payload: file hash/id (requests actual bytes for a Large Files item)
  - `file_chunk` — payload: file id, chunk index, chunk bytes, total_chunks (supports resumable transfer)

- **Storage:**
  - Tray: SQLite table `tray_items(id, type, content_or_blob_ref, source_device, created_at, expires_at)`. Background job runs periodically (e.g., every 5 minutes) to delete expired rows AND broadcast `tray_delete` to peers.
  - Large Files: SQLite table `file_index(id, filename, size, hash, owning_device, path, last_seen)`.
  - Both databases encrypted at rest.

---

## 4. What Already Exists (reference, don't reinvent)

These are real open-source tools relevant as **reference implementations or potential dependencies** — study their approach, don't necessarily vendor their full codebase:

- **iroh** (https://github.com/n0-computer/iroh) — the chosen P2P transport library. Read its docs for discovery, connection, and relay fallback APIs before building any custom networking code.
- **KDE Connect / GSConnect** — proven Linux↔Android pairing and clipboard-sync UX pattern. Useful reference for pairing flow and background service design, not a dependency.
- **Syncthing** — reference for file index/sync concepts, though its full-sync model differs from our index-only/pull-on-demand design.
- **UniClipboard** — closest existing analog to the Tray concept (Rust + Tauri, iroh-based, P2P-first with relay fallback). Worth reading their source if available for transport-layer patterns.
- **Android Quick Share / Nearby Share** — reference for the Bluetooth-handshake → Wi-Fi-Direct-upgrade pattern and the "same-account auto-accept" trust shortcut. Android's own `WifiP2pManager` API is what to use for the no-network fallback tier.

Do not pull in a heavyweight dependency (e.g., full Syncthing binary, full KDE Connect) as a black box — the goal is a lean custom app using `iroh` as the only major third-party networking dependency.

---

## 5. Build Phases

**Phase 1 — Core networking foundation**
- [ ] Set up Rust workspace: `core` crate (shared protocol + iroh logic), `linux-app` crate, plus FFI bindings target for Android.
- [ ] Implement Ed25519 keypair generation + persistent storage on first run.
- [ ] Implement QR-code-based pairing flow (generate + scan) that exchanges public keys.
- [ ] Integrate `iroh` for connection establishment between two paired devices on the same LAN.
- [ ] Validate: two devices (one Linux, one Android emulator/device) can discover each other and establish an authenticated encrypted connection automatically on app start, with no manual IP entry.

**Phase 2 — Shared Tray**
- [ ] Define and implement the `tray_item` / `tray_delete` message types.
- [ ] Build local encrypted SQLite store for tray items.
- [ ] Implement drag-and-drop capture (Linux) and share-sheet capture (Android).
- [ ] Implement paste capture (Linux clipboard hook).
- [ ] Implement push-on-create: broadcast new item to all connected peers immediately.
- [ ] Implement 25MB size check + auto-redirect logic (stub Large Files Space target until Phase 3 exists).
- [ ] Implement 24h expiry background job + `tray_delete` broadcast.
- [ ] Build minimal feed UI (list view, newest first, type icons, source device tag) on both platforms.

**Phase 3 — Large Files Space**
- [ ] Define and implement `file_index_update`, `file_request`, `file_chunk` message types.
- [ ] Build "publish folder/file" flow (user selects what to share, app computes hash, adds to local index, broadcasts index update).
- [ ] Implement index sync across peers (lightweight, near-instant).
- [ ] Implement on-demand chunked file transfer triggered by `file_request`.
- [ ] Implement resumable transfer (track chunk progress, resume on reconnect).
- [ ] Implement offline/unavailable UI state when owning device isn't reachable.

**Phase 4 — Connectivity resilience**
- [ ] Implement internet-tier connectivity: NAT hole-punching via iroh, verify it works across two different networks (e.g., phone on mobile data, laptop on different Wi-Fi).
- [ ] Implement relay fallback path (iroh's built-in relay) and verify content stays encrypted end-to-end through it.
- [ ] Implement Android Wi-Fi Direct fallback for the no-network case; route the same protocol messages over it.
- [ ] Implement local pending-queue: items that fail to send immediately should auto-retry/flush when any connectivity path becomes available, with no manual user action required.

**Phase 5 — Polish & hardening**
- [ ] Background agent auto-start on boot/login (both platforms).
- [ ] Auto-reconnect logic after network changes (Wi-Fi to mobile data, sleep/wake, etc.).
- [ ] Conflict handling (e.g., same filename published from two devices).
- [ ] Battery/resource usage review on Android (background service shouldn't drain battery).
- [ ] Security review: verify encryption at rest, verify relay never sees plaintext, verify pairing can't be spoofed.

---

## 6. Constraints & Non-Negotiables

- **No third-party cloud or paid services**, ever, in the default flow.
- **No data leaves the device pair unencrypted**, including over relay fallback.
- **No repeated manual setup** — pairing happens once per device pair; after that, connection/reconnection must be automatic.
- Keep the **shared Rust core** as the single source of truth for protocol logic — Android and Linux apps are thin platform shells around it, not independent reimplementations.

## 7. Open Items for the Next Agent to Resolve or Flag

- Confirm exact iroh API version/feature set available at implementation time (library evolves; check current docs before assuming method names).
- Decide Protobuf vs JSON for message schema if not yet started — Protobuf preferred for efficiency, but if it adds too much setup friction in Phase 1, JSON over the iroh stream is an acceptable fallback to revisit later.
- Decide on Android-Rust FFI tooling specifics (`uniffi` recommended starting point) and validate build pipeline (Rust cross-compilation for Android targets) works before deep feature work begins.
- Determine minimum Android API level to support (affects which Wi-Fi Direct APIs are available).

---

## 8. How to Hand Off to the Next Agent

When you stop work (e.g., due to context/token limits), leave behind:
1. A short status note: what phase/checklist items are done, in progress, or blocked.
2. Any deviations made from this brief and why.
3. Updated/new open items discovered during implementation.
4. Current file/module structure so the next agent can orient quickly without re-reading all code from scratch.
