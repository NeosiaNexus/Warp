---
globs:
  - "jni/src/**/*.java"
---

# JNI Module Rules

Native bindings for compression (zlib/zstd) and cryptography. Standalone — no dependency on api/ or protocol/.

- This module declares `native` methods backed by C/Rust implementations
- Keep Java-side code minimal: loading, method declarations, ByteBuf adapters
- All native methods must have clear Javadoc specifying ownership of buffer memory (who allocates, who releases)
- Use Netty ByteBuf for all buffer operations — no raw byte arrays crossing the JNI boundary
- Availability checks: always guard native calls with `Natives.isAvailable()` style checks and provide pure-Java fallbacks
