---
globs:
  - "protocol/src/**/*.java"
---

# Protocol Module Rules

Minecraft protocol codec and packet definitions. Performance-critical.

- Packets are records or sealed interfaces — never mutable classes
- Each packet colocates its codec (read/write) with its definition
- Use Netty ByteBuf directly for encoding/decoding — no intermediate wrappers
- Unregistered packets stay as raw ByteBuf (blind forwarding path)
- Protocol versions are registered in ProtocolVersion — keep the registry exhaustive
- VarInt/VarLong encoding must be branchless or lookup-table optimized
