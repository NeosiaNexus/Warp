---
globs:
  - "api/src/**/*.java"
---

# API Module Rules

This is the **public plugin API**. Every type here is a contract with plugin developers.

- Domain types are interfaces, records, or sealed hierarchies — no mutable classes. Prefer unmodifiable collections for return types.
- Utility access points (e.g., WarpProvider) are the only concrete classes allowed: `final` + `private` constructor
- No internal dependencies — never import from `dev.warp.protocol`, `dev.warp.proxy`, or `dev.warp.jni`
- Every public type and method must have Javadoc explaining behavior, not restating the signature
- Breaking changes require `feat!:` commit and careful consideration — plugins depend on this
