# Changelog

## [0.1.0-beta.5](https://github.com/NeosiaNexus/Warp/compare/v0.1.0-beta.4...v0.1.0-beta.5) (2026-04-07)


### Features

* **proxy:** add login flow, backend connection, and blind forwarding pipeline ([#21](https://github.com/NeosiaNexus/Warp/issues/21)) ([b5c66da](https://github.com/NeosiaNexus/Warp/commit/b5c66da9489159a45a912c56880ec95367d71199))

## [0.1.0-beta.4](https://github.com/NeosiaNexus/Warp/compare/v0.1.0-beta.3...v0.1.0-beta.4) (2026-04-07)


### Features

* **protocol:** add protocol versions up to 26.1.1 ([28fa0a8](https://github.com/NeosiaNexus/Warp/commit/28fa0a82ea17c80cc7f5c554298dbd399acf2152))
* **proxy:** add connection pipeline and server list ping ([#19](https://github.com/NeosiaNexus/Warp/issues/19)) ([e385032](https://github.com/NeosiaNexus/Warp/commit/e385032087e94eb68f24d7a02760593c023cde04))

## [0.1.0-beta.3](https://github.com/NeosiaNexus/Warp/compare/v0.1.0-beta.2...v0.1.0-beta.3) (2026-04-07)


### Features

* **protocol:** add high-performance VarInt and VarLong codecs ([#13](https://github.com/NeosiaNexus/Warp/issues/13)) ([ba70085](https://github.com/NeosiaNexus/Warp/commit/ba70085732f4b1ca2ff86fbf4ca6ac891901bc1c))
* **protocol:** add MinecraftDecoder and MinecraftEncoder Netty handlers ([#18](https://github.com/NeosiaNexus/Warp/issues/18)) ([cf3ee73](https://github.com/NeosiaNexus/Warp/commit/cf3ee73ed3a6b98ee933da1fdc5a9cf5099ac9a6))
* **protocol:** add packet definitions, codecs, and registry ([#17](https://github.com/NeosiaNexus/Warp/issues/17)) ([f9e1a7d](https://github.com/NeosiaNexus/Warp/commit/f9e1a7d3106a367fdbf97e6dfc2c2838b6d1694b))
* **protocol:** add packet frame codec and compression handlers ([#16](https://github.com/NeosiaNexus/Warp/issues/16)) ([134f5b5](https://github.com/NeosiaNexus/Warp/commit/134f5b5c674312426fe60b5f3ef636acca318673))
* **protocol:** add String, UUID, and byte array codecs ([#15](https://github.com/NeosiaNexus/Warp/issues/15)) ([1d41b96](https://github.com/NeosiaNexus/Warp/commit/1d41b9620ab426221b98f8da9ba3afc0145f80c3))

## [0.1.0-beta.2](https://github.com/NeosiaNexus/warp/compare/v0.1.0-beta.1...v0.1.0-beta.2) (2026-04-01)


### Features

* **build:** add Spotless pre-commit hook with auto-install ([#9](https://github.com/NeosiaNexus/warp/issues/9)) ([22c19d7](https://github.com/NeosiaNexus/warp/commit/22c19d755fdd2a4fc274a9185061f7c25b79d4fc))
* **deps:** add Renovate config for automated dependency updates ([#5](https://github.com/NeosiaNexus/warp/issues/5)) ([886e7a6](https://github.com/NeosiaNexus/warp/commit/886e7a621463a45112cb08a53011e83719e3ebcf))
* **project:** add automated SemVer versioning with release-please ([4f21743](https://github.com/NeosiaNexus/warp/commit/4f21743c25fe588ccd636b446c50e0edf3ff4eb9))
* **project:** add GitHub issue/PR templates, contributing guide, and labels ([#7](https://github.com/NeosiaNexus/warp/issues/7)) ([d2a1c09](https://github.com/NeosiaNexus/warp/commit/d2a1c097a0c26be479f0e417b4b63ecb094b804e))
* **project:** bootstrap Warp proxy with production-grade Gradle setup ([346c8ce](https://github.com/NeosiaNexus/warp/commit/346c8ce6351df5c1f90365fc02af08ea9cb74b74))
* **quality:** add Checkstyle convention with naming, Javadoc, and design rules ([#2](https://github.com/NeosiaNexus/warp/issues/2)) ([3020dc3](https://github.com/NeosiaNexus/warp/commit/3020dc38ca7aef3bd6cc675b0775d7131aedc91f))
* **quality:** add JaCoCo code coverage convention ([#3](https://github.com/NeosiaNexus/warp/issues/3)) ([bca19c6](https://github.com/NeosiaNexus/warp/commit/bca19c66ac7c6e9d58262afa2e5d1325970c9251))


### Bug Fixes

* **api:** update Javadoc version example to match actual versioning format ([#12](https://github.com/NeosiaNexus/warp/issues/12)) ([d9ba37a](https://github.com/NeosiaNexus/warp/commit/d9ba37af1841aa23e138c89b3ef362dd35a333a0))
* **build:** regenerate Gradle wrapper with official 8.12 distribution ([f7c4a09](https://github.com/NeosiaNexus/warp/commit/f7c4a09a76e510f230aad3cf18a089f3575e01fb))
* **project:** resolve audit findings — build info, org URLs, changelog config ([#11](https://github.com/NeosiaNexus/warp/issues/11)) ([3076ca9](https://github.com/NeosiaNexus/warp/commit/3076ca9ebcd55315b3b3d703f9ce23c5cd04a5c2))
* **project:** use GitHub Security Advisories instead of non-existent email ([#8](https://github.com/NeosiaNexus/warp/issues/8)) ([d12d242](https://github.com/NeosiaNexus/warp/commit/d12d242f26382fe35cd44c233a0e9c4055464a3e))
* **release:** use prerelease versioning to increment beta counter ([#4](https://github.com/NeosiaNexus/warp/issues/4)) ([e129914](https://github.com/NeosiaNexus/warp/commit/e129914d8a892a7811ec1dbde0a743df9e8006b3))


### Documentation

* **project:** add CLAUDE.md, scoped rules, and project settings ([cb8a455](https://github.com/NeosiaNexus/warp/commit/cb8a45511c7bfe9fc12d00e902a18f8ffb62fa31))
* **project:** add README with project vision, status, and quick start ([#10](https://github.com/NeosiaNexus/warp/issues/10)) ([5a0e7b3](https://github.com/NeosiaNexus/warp/commit/5a0e7b310ace24ba9de61ad99ba80c48f5b7ca16))


### CI

* **project:** add GitHub Actions for CI, release, and PR title validation ([cb6d64d](https://github.com/NeosiaNexus/warp/commit/cb6d64d4358bbc5391c7f2a17292b1c5469c281e))
