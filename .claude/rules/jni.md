---
globs:
  - "jni/src/**/*.java"
---

# Natives Module Rules

Native libraries (libdeflate today) called through the Java 25 FFM API (`java.lang.foreign`), not JNI: no hand-written C glue. Standalone: no dependency on api/ or protocol/.

- Libraries are built from pinned, checksummed upstream releases by CI (`_natives.yml`) for every supported platform, bundled in the jar, and loaded by `NativeLoader` after checking owner, size and SHA-256
- Keep Java-side code minimal: loading, downcall handles, ByteBuf adapters
- Every downcall must have Javadoc specifying ownership of buffer memory (who allocates, who releases) and the bounds it relies on
- Use Netty ByteBuf for all buffer operations: direct buffers by memory address, a copy only for heap buffers; no per-call `Arena` or other allocation on the hot path (contexts live per event loop thread, see `ThreadContexts`)
- Untrusted input is decompressed to exactly the declared size, and any mismatch is rejected
- Availability checks: guard native calls with `Natives.compression()` and keep a pure-Java fallback that is always correct; a failed load or self-test falls back with one clear log line, never a crash
