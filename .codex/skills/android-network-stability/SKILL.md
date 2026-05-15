# Android Network Stability Skill

Use when working on Android/Kotlin code that owns sockets, foreground services, proxy loops, WebSocket/TLS handshakes, or route fallback logic.

## Checklist
- Bound executors and queues; reject excess work gracefully and close rejected sockets.
- Name background threads to make ANR/crash traces actionable.
- Ensure each connect/handshake failure closes every socket created during the attempt.
- Keep route-probe connect/read timeouts finite.
- Add explicit parser limits for headers, frames, packet sizes, and buffers before allocation.
- Keep long-lived bridge loops cancellable by closing sockets from `close()`/service stop.
- Surface concise status messages for user-facing diagnostics without leaking secrets.
<<<<<<< ours
- Verify with `gradle :app:assembleDebug` under JDK 17 or document the local JDK mismatch.
=======
- Verify with `scripts/gradle-jdk17.sh :app:assembleDebug`; if dependencies cannot be downloaded, capture the repository/proxy error separately from code failures.
>>>>>>> theirs

## Anti-patterns to flag
- `Executors.newCachedThreadPool()` in proxy/server code.
- `ByteArray(length)` from network-controlled length without a maximum.
- Header reads that append until EOF with no byte cap.
- Socket wrapping where a TLS/WebSocket failure can leave the underlying plain socket open.
- Restart paths that reuse a shut-down executor instance.

## References
- AGENTS.md format and examples: https://github.com/agentsmd/agents.md
- Android Studio AGENTS.md guidance: https://developer.android.com/studio/gemini/agent-files
- Codex AGENTS.md vs SKILL.md guidance: https://codex-best-practices-d67bea.pages.oit.duke.edu/best-practices/agents.html
