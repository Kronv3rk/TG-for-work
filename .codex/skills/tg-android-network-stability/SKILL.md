# TG Android Network Stability Skill

Use this skill when changing the Android/Kotlin proxy, WebSocket, TLS, socket bridge, or foreground-service paths in TG-for-work.

## Checklist
- Keep connection handling bounded; close sockets when work is rejected.
- Keep route-probe timeouts finite and make bridge loops stoppable by closing sockets.
- Reject network-controlled frame/header lengths before allocating buffers.
- Close both TLS and underlying sockets when handshake/setup fails.
- Verify with `scripts/gradle-jdk17.sh :app:assembleDebug`; record proxy/download failures separately from code failures.
