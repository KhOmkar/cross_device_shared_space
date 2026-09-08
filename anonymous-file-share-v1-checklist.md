# V1 Verification Checklist — Anonymous Hotspot File Transfer

Test on at least two guest devices/browsers (e.g. an old Windows laptop
with Chrome, and a different browser like Firefox) plus your phone, to
catch browser-specific and hardware-specific issues.

## 1. Core Setup Flow

- [ ] Phone hotspot can be enabled from within the app flow (or the app
      correctly prompts the user to enable it manually if the OS blocks
      programmatic control)
- [ ] Guest device can join the hotspot with no additional software
- [ ] Phone server starts automatically once hotspot is active, with a
      visible foreground notification (Android) while running
- [ ] Guest browser loads the page at the displayed address with **no**
      internet connection required (turn off the PC's other network
      access entirely and confirm the page still loads)
- [ ] Page loads correctly with **no external requests** — check browser
      dev tools Network tab, confirm zero requests to any domain outside
      the phone's local address (no CDN calls, no fonts, no analytics)

## 2. Pairing & Session

- [ ] QR code displays and scans correctly to the right address/token
- [ ] Manual short code entry works as an alternative to QR
- [ ] Session token is different every time a new session starts (not
      reused/predictable across runs)
- [ ] Wrong/expired code is rejected with a clear error, not a silent
      failure or generic crash
- [ ] After 5 failed code attempts, entry is temporarily locked out
      (confirm the rate limit actually triggers)
- [ ] Session expires automatically after the configured timeout, and a
      second guest cannot join with the same expired token
- [ ] A second concurrent connection attempt with a still-valid token is
      rejected (or, if multi-guest was intentionally enabled, confirm
      that's actually the intended design)

## 3. Transfer — Functional

- [ ] Small file transfers correctly, phone → guest
- [ ] Small file transfers correctly, guest → phone
- [ ] Large file (e.g. 1GB+) transfers without the app or browser tab
      crashing or freezing
- [ ] Multiple files in one session transfer correctly and don't get
      mixed up (filenames, contents match exactly post-transfer)
- [ ] Checksum verification actually runs and would catch a corrupted
      transfer (test by deliberately truncating/corrupting a chunk in a
      debug build and confirming the checksum check fails as expected)
- [ ] Progress bar accurately reflects bytes transferred vs. total on
      both sender and receiver
- [ ] Cancelling mid-transfer cleans up partial files (no orphaned
      incomplete files left on either device)

## 4. Transfer — Performance

- [ ] Confirm WebSocket connection is reused for the whole session (not
      reconnecting per chunk) — check dev tools Network/WS tab
- [ ] Confirm chunk size matches spec (1–4MB) — check actual frame sizes
      in dev tools
- [ ] Confirm backpressure works: throttle the guest device's disk write
      speed (or use a large file on a slow USB drive) and verify the
      phone's `bufferedAmount` check actually pauses sending rather than
      piling up memory unboundedly
- [ ] Measure real throughput on 5GHz hotspot vs. 2.4GHz — confirm 5GHz
      is meaningfully faster and the app doesn't force 2.4GHz unnecessarily
- [ ] Confirm large-file receiving buffers to memory in blocks (e.g.
      4–8MB) rather than writing on every small chunk — verify via disk
      I/O monitoring if possible, or check the code directly

## 5. Encryption & Security

- [ ] Confirm application-layer encryption is active — capture WebSocket
      traffic (e.g. via browser dev tools or a packet capture tool) and
      confirm file bytes are not readable/plaintext even though it's a
      local network
- [ ] Confirm the session key is derived per-session (not hardcoded, not
      reused across sessions)
- [ ] Confirm the pairing code itself is never sent as the encryption key
      in the clear
- [ ] If using self-signed TLS: confirm `wss://` is used and the
      certificate is regenerated per install (not a single shared cert
      baked into the app)
- [ ] Confirm the hotspot name is randomized per session, not a fixed
      predictable name
- [ ] Attempt a path traversal in a filename (e.g. `../../etc/passwd` as
      the reported filename) and confirm the server stores it safely
      under its own generated name, not at the traversed path
- [ ] Attempt to upload a file exceeding the configured max size and
      confirm it's rejected server-side (not just blocked in JS, which
      could be bypassed)
- [ ] Confirm uploaded files are never auto-opened, auto-executed, or
      auto-previewed by the phone app
- [ ] Confirm no cookies/localStorage/sessionStorage are used to persist
      session data in the guest browser — check dev tools Application tab

## 6. Anonymity & Data Minimization

- [ ] Check the phone app's logs (if any) after a session ends — confirm
      no filenames, IPs, or device identifiers persisted to disk/logs
- [ ] Confirm session key and token are cleared from memory when the
      session ends (not just left to garbage collection eventually —
      check they're explicitly nulled/zeroed if the language allows it)
- [ ] Confirm no personal-info fields exist anywhere in the UI (name,
      email, device nickname, etc.)
- [ ] Restart the app after a completed session and confirm no trace of
      the previous session's token/files/filenames remains

## 7. Failure & Edge Cases

- [ ] Guest disconnects mid-transfer (close the tab abruptly) — confirm
      the phone detects this and cleans up rather than hanging
- [ ] Phone app is killed/backgrounded mid-transfer — confirm either
      graceful failure with a clear message, or (if foreground service is
      implemented correctly) the transfer survives app-switching
- [ ] Hotspot briefly drops and reconnects — confirm behavior is a clean
      failure/retry prompt, not a silent hang
- [ ] Try pairing with a stale/already-used QR code from a previous
      session — confirm it's rejected
- [ ] Fill available phone storage close to zero mid-transfer — confirm
      it fails gracefully rather than corrupting a partial file silently

## 8. Guest-Facing UX & Disclosure

- [ ] UI clearly tells the guest they're on someone else's session (not
      confusing about whose device is whose)
- [ ] UI includes the disclosure that downloaded files land in the PC's
      normal Downloads folder (outside the app's control) and that using
      a private/incognito window is recommended
- [ ] Confirm the page renders correctly and usably on an older browser
      (test on whatever's realistically still running in a school/library
      environment, not just the latest Chrome)
- [ ] Confirm filenames containing special characters or HTML-like text
      (e.g. `<script>alert(1)</script>.txt`) render as plain text in the
      UI, not executed — tests the CSP/escaping from the security spec

## 9. Battery & Resource Impact

- [ ] Measure battery drain over a typical session (hotspot + server
      running) so this can be honestly communicated to users
- [ ] Confirm the app doesn't silently keep the hotspot/server running
      indefinitely after the user is done — should stop when session ends
      or app is closed

---

## Sign-off

Only consider V1 "done" once every box above is checked on **at least two
different guest browser/OS combinations** — browser quirks (especially
around WebSocket, File API, and Streams API support) are a common source
of "works on my machine" bugs in exactly this kind of tool.
