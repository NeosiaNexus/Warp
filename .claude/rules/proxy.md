---
globs:
  - "proxy/src/**/*.java"
---

# Proxy Module Rules

Core proxy implementation. This is where the hot path lives.

- Entry point: `WarpBootstrap.main()` → `WarpServer`
- Netty event loop is king: never block it (no Thread.sleep, no synchronized, no blocking I/O)
- Use virtual threads only for plugin-facing async work, not for Netty pipeline handlers
- Shadow JAR relocates: Gson → `dev.warp.libs.gson`, Caffeine → `dev.warp.libs.caffeine`, Configurate → `dev.warp.libs.configurate`, Typesafe Config → `dev.warp.libs.typesafe.config`
- Log at INFO for lifecycle events, WARN for recoverable errors, ERROR for critical failures, TRACE for packet-level debug
