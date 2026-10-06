## Summary

<!-- What does this PR change, and why? Link related issues with "Fixes #123". -->

## Test plan

<!--
How was this tested? Check all that apply.
CI runs the end-to-end tests (real clients through Warp to real servers) on one Minecraft version
per era. Run them locally with `e2e/run.sh --mc <version>` (see e2e/README.md).
-->

- [ ] `./gradlew build` passes (formatting, compilation, tests, Checkstyle)
- [ ] New or updated tests cover the change
- [ ] Protocol, compression, login, forwarding or server switching change: labelled `e2e: full`
      to run the end-to-end tests on every Minecraft version (ask a maintainer if you cannot)
- [ ] Manually tested with a Minecraft client

## Checklist

- [ ] PR title follows [Conventional Commits](https://www.conventionalcommits.org/) (`type(scope): description`)
- [ ] No unrelated changes included
- [ ] Javadoc added or updated for public API changes
