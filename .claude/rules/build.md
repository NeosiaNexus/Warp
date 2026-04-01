---
globs:
  - "**/*.gradle.kts"
  - "build-logic/**/*.kts"
  - "gradle/**"
---

# Build System Rules

- Convention plugins live in `build-logic/` — all shared config goes there, not in subproject build files
- Version catalog (`gradle/libs.versions.toml`) is the single source for all dependency versions
- Never add `repositories {}` in subprojects — `FAIL_ON_PROJECT_REPOS` is enforced in settings
- Shadow JAR config lives only in `proxy/build.gradle.kts`
- `version.txt` is managed by release-please — never edit it manually
- Run `./gradlew build` after any build config change to verify
