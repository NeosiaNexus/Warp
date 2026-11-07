# Changelog

## [0.1.0-beta.6](https://github.com/NeosiaNexus/Warp/compare/v0.1.0-beta.5...v0.1.0-beta.6) (2026-11-07)


### ⚠ BREAKING CHANGES

* **api:** deny the first server with a reason, upsert servers, release plugin registrations, time out pings ([#148](https://github.com/NeosiaNexus/Warp/issues/148))
* Warp now requires a Java 25 runtime.

### Features

* **api:** complete the Adventure audience: sounds, boss bars, resource packs on every version ([#142](https://github.com/NeosiaNexus/Warp/issues/142)) ([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))
* **api:** configure the server list entry and enforce the player limit ([#134](https://github.com/NeosiaNexus/Warp/issues/134)) ([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))
* **api:** deliver events with deterministic order and per-player sequencing ([#120](https://github.com/NeosiaNexus/Warp/issues/120)) ([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))
* **api:** deny the first server with a reason, upsert servers, release plugin registrations, time out pings ([#148](https://github.com/NeosiaNexus/Warp/issues/148)) ([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))
* **api:** expose players, backend servers and their connection events ([#126](https://github.com/NeosiaNexus/Warp/issues/126)) ([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))
* **api:** let permission plugins decide what players may do ([#130](https://github.com/NeosiaNexus/Warp/issues/130)) ([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))
* **api:** let plugins own plugin message channels ([#127](https://github.com/NeosiaNexus/Warp/issues/127)) ([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))
* **api:** load plugins in isolated class loaders, dependencies first ([#119](https://github.com/NeosiaNexus/Warp/issues/119)) ([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))
* **api:** make players and command sources Adventure audiences ([#137](https://github.com/NeosiaNexus/Warp/issues/137)) ([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))
* **api:** refresh a player's command list when its permissions or the commands change ([#165](https://github.com/NeosiaNexus/Warp/issues/165)) ([ca6dc6c](https://github.com/NeosiaNexus/Warp/commit/ca6dc6c6688324c62dde6e11f6fc463d33d0b5a4))
* **api:** run proxy commands with Brigadier, merged into every server's list ([#128](https://github.com/NeosiaNexus/Warp/issues/128)) ([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))
* **protocol:** decode, edit and re-encode the command tree ([#125](https://github.com/NeosiaNexus/Warp/issues/125)) ([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))
* **protocol:** forward compressed frames without recompressing them ([#31](https://github.com/NeosiaNexus/Warp/issues/31)) ([975d681](https://github.com/NeosiaNexus/Warp/commit/975d681dbeaf400a47ff2bd19973204688bc0b9f))
* **protocol:** send Adventure components to every client version ([#129](https://github.com/NeosiaNexus/Warp/issues/129)) ([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))
* **protocol:** support Minecraft 26.2 and 26.3 ([#96](https://github.com/NeosiaNexus/Warp/issues/96)) ([e907463](https://github.com/NeosiaNexus/Warp/commit/e9074635dda1dd8eaad8ca3c8d8c50bb41279fcd))
* **proxy:** a console with colours, startup and shutdown summaries, an interactive prompt and a status line ([#167](https://github.com/NeosiaNexus/Warp/issues/167)) ([ca6dc6c](https://github.com/NeosiaNexus/Warp/commit/ca6dc6c6688324c62dde6e11f6fc463d33d0b5a4))
* **proxy:** a secure remote console: live logs over SSE, scoped commands, and a client in the jar ([#169](https://github.com/NeosiaNexus/Warp/issues/169)) ([e746adf](https://github.com/NeosiaNexus/Warp/commit/e746adf7bb582dbd7c9765455b2e23cb1a8b2563))
* **proxy:** accept the PROXY protocol from trusted load balancers ([#122](https://github.com/NeosiaNexus/Warp/issues/122)) ([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))
* **proxy:** accept transferred players and carry cookies across transfers ([#121](https://github.com/NeosiaNexus/Warp/issues/121)) ([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))
* **proxy:** add client settings cache, fallback server, and switch safety ([#26](https://github.com/NeosiaNexus/Warp/issues/26)) ([1daba96](https://github.com/NeosiaNexus/Warp/commit/1daba968da0458e522c332e2b5dd9c614b236ef0))
* **proxy:** add server switching with blind-forwarded CONFIG phase ([#25](https://github.com/NeosiaNexus/Warp/issues/25)) ([7ffe443](https://github.com/NeosiaNexus/Warp/commit/7ffe443ddb829d42d35e87c008f9a27fb984d5de))
* **proxy:** administration commands /warp, /glist, /send, /find, /alert and /shutdown ([#163](https://github.com/NeosiaNexus/Warp/issues/163)) ([ca6dc6c](https://github.com/NeosiaNexus/Warp/commit/ca6dc6c6688324c62dde6e11f6fc463d33d0b5a4))
* **proxy:** allow overriding the session server with mojang.sessionserver ([#35](https://github.com/NeosiaNexus/Warp/issues/35)) ([1977f6a](https://github.com/NeosiaNexus/Warp/commit/1977f6a02586f1cf49f4bbf8748bcce5251df27f))
* **proxy:** answer the BungeeCord plugin messaging channel ([#150](https://github.com/NeosiaNexus/Warp/issues/150)) ([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))
* **proxy:** bound connections before login with frame limits, deadlines and counted refusals ([#145](https://github.com/NeosiaNexus/Warp/issues/145)) ([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))
* **proxy:** compress with a bundled libdeflate through the FFM API, one context per event loop ([#143](https://github.com/NeosiaNexus/Warp/issues/143)) ([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))
* **proxy:** configure the player limit, timeouts and logging, with JSON logs and plugin log sinks ([#149](https://github.com/NeosiaNexus/Warp/issues/149)) ([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))
* **proxy:** console themes, clickable links, stable error codes, /log and the startup's breakdown ([#170](https://github.com/NeosiaNexus/Warp/issues/170)) ([e746adf](https://github.com/NeosiaNexus/Warp/commit/e746adf7bb582dbd7c9765455b2e23cb1a8b2563))
* **proxy:** detect bot attacks, remember trusted identities and lock down, with an authentication lane ([#151](https://github.com/NeosiaNexus/Warp/issues/151)) ([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))
* **proxy:** drain gracefully and answer Kubernetes and load balancer health checks ([#135](https://github.com/NeosiaNexus/Warp/issues/135)) ([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))
* **proxy:** player messages in each player's language, and configuration and startup errors that say how to fix them ([#166](https://github.com/NeosiaNexus/Warp/issues/166)) ([ca6dc6c](https://github.com/NeosiaNexus/Warp/commit/ca6dc6c6688324c62dde6e11f6fc463d33d0b5a4))
* **proxy:** throttle connections and logins per address ([#123](https://github.com/NeosiaNexus/Warp/issues/123)) ([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))
* **proxy:** track connected players and register servers at runtime ([#118](https://github.com/NeosiaNexus/Warp/issues/118)) ([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))
* **via:** run ViaVersion on Warp through a sanctioned network pipeline API ([#146](https://github.com/NeosiaNexus/Warp/issues/146)) ([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))


### Bug Fixes

* **deps:** bump com.diffplug.spotless from 7.0.4 to 8.10.3 ([#54](https://github.com/NeosiaNexus/Warp/issues/54)) ([dd95cc5](https://github.com/NeosiaNexus/Warp/commit/dd95cc5593d795f4c38f58a4a01d9a360199ef72))
* **deps:** bump com.github.ben-manes.caffeine:caffeine from 3.2.3 to 3.3.0 ([#56](https://github.com/NeosiaNexus/Warp/issues/56)) ([41e3d64](https://github.com/NeosiaNexus/Warp/commit/41e3d646420a0968e880ad9251461ca562a69fb5))
* **deps:** bump com.google.code.gson:gson from 2.13.2 to 2.14.0 ([#58](https://github.com/NeosiaNexus/Warp/issues/58)) ([aba7d00](https://github.com/NeosiaNexus/Warp/commit/aba7d0059aed7d4e3079036f7a88c237876eead5))
* **deps:** bump com.gradleup.shadow from 9.0.0 to 9.6.1 ([#55](https://github.com/NeosiaNexus/Warp/issues/55)) ([3ee6ccf](https://github.com/NeosiaNexus/Warp/commit/3ee6ccf3a6f4f4ee3b54a8323faf70915aa45a51))
* **deps:** bump com.puppycrawl.tools:checkstyle from 13.11.0 to 14.3.0 ([#108](https://github.com/NeosiaNexus/Warp/issues/108)) ([e746adf](https://github.com/NeosiaNexus/Warp/commit/e746adf7bb582dbd7c9765455b2e23cb1a8b2563))
* **deps:** bump com.velocitypowered:velocity-native from 4.2.0 to 4.3.0 ([#116](https://github.com/NeosiaNexus/Warp/issues/116)) ([e746adf](https://github.com/NeosiaNexus/Warp/commit/e746adf7bb582dbd7c9765455b2e23cb1a8b2563))
* **deps:** bump gradle-wrapper from 9.8.0 to 9.8.2 ([#113](https://github.com/NeosiaNexus/Warp/issues/113)) ([e746adf](https://github.com/NeosiaNexus/Warp/commit/e746adf7bb582dbd7c9765455b2e23cb1a8b2563))
* **deps:** bump io.netty:netty-bom from 4.2.18.Final to 4.2.19.Final in the netty group across 1 directory ([#111](https://github.com/NeosiaNexus/Warp/issues/111)) ([e746adf](https://github.com/NeosiaNexus/Warp/commit/e746adf7bb582dbd7c9765455b2e23cb1a8b2563))
* **deps:** bump net.kyori:adventure-bom from 4.26.1 to 5.2.0 ([#104](https://github.com/NeosiaNexus/Warp/issues/104)) ([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))
* **deps:** bump net.ltgt.errorprone from 4.2.0 to 5.1.1 ([#57](https://github.com/NeosiaNexus/Warp/issues/57)) ([31878cf](https://github.com/NeosiaNexus/Warp/commit/31878cf48adb9e4a4dea2e940ccd1b20d1dbe2a1))
* **deps:** bump org.gradle.toolchains.foojay-resolver-convention from 0.9.0 to 1.0.0 ([#59](https://github.com/NeosiaNexus/Warp/issues/59)) ([a40e185](https://github.com/NeosiaNexus/Warp/commit/a40e1853c48932a33dccb457e6c87f3097af3b1f))
* **deps:** bump org.jspecify:jspecify from 1.0.0 to 1.0.1 ([#105](https://github.com/NeosiaNexus/Warp/issues/105)) ([e746adf](https://github.com/NeosiaNexus/Warp/commit/e746adf7bb582dbd7c9765455b2e23cb1a8b2563))
* **deps:** bump org.junit:junit-bom from 5.12.2 to 6.1.3 ([#106](https://github.com/NeosiaNexus/Warp/issues/106)) ([e746adf](https://github.com/NeosiaNexus/Warp/commit/e746adf7bb582dbd7c9765455b2e23cb1a8b2563))
* **deps:** bump org.pitest:pitest-command-line from 1.30.0 to 1.30.1 in the testing group across 1 directory ([#115](https://github.com/NeosiaNexus/Warp/issues/115)) ([e746adf](https://github.com/NeosiaNexus/Warp/commit/e746adf7bb582dbd7c9765455b2e23cb1a8b2563))
* **deps:** bump org.slf4j:slf4j-api from 2.0.17 to 2.0.20 ([#107](https://github.com/NeosiaNexus/Warp/issues/107)) ([e746adf](https://github.com/NeosiaNexus/Warp/commit/e746adf7bb582dbd7c9765455b2e23cb1a8b2563))
* **deps:** bump the code-quality group across 1 directory with 2 updates ([#53](https://github.com/NeosiaNexus/Warp/issues/53)) ([6a4a598](https://github.com/NeosiaNexus/Warp/commit/6a4a598772a20d07fd40b73e6d612ed3da8ac428))
* **deps:** bump the code-quality group across 1 directory with 4 updates ([#160](https://github.com/NeosiaNexus/Warp/issues/160)) ([e746adf](https://github.com/NeosiaNexus/Warp/commit/e746adf7bb582dbd7c9765455b2e23cb1a8b2563))
* **deps:** bump the log4j group across 1 directory with 6 updates ([#51](https://github.com/NeosiaNexus/Warp/issues/51)) ([ee49086](https://github.com/NeosiaNexus/Warp/commit/ee490861834b0905ae58e3865be57e8175108616))
* **deps:** bump the netty group across 1 directory with 2 updates ([#50](https://github.com/NeosiaNexus/Warp/issues/50)) ([2576ae6](https://github.com/NeosiaNexus/Warp/commit/2576ae6ac2fde107c569525c41cd71bdd805a31e))
* **deps:** bump the testing group across 1 directory with 2 updates ([#52](https://github.com/NeosiaNexus/Warp/issues/52)) ([fe73644](https://github.com/NeosiaNexus/Warp/commit/fe7364484e916332cf8ba7359d58a5273800fbe4))
* **deps:** raise Guava and build tool dependencies to patched versions ([#63](https://github.com/NeosiaNexus/Warp/issues/63)) ([f32df0b](https://github.com/NeosiaNexus/Warp/commit/f32df0b92c565c82ca17816a1ebec1f0016c186b))
* **deps:** resolve the patched Guava in every configuration, build tools included ([#153](https://github.com/NeosiaNexus/Warp/issues/153)) ([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))
* **protocol:** audit packet ids and formats for 1.21.5 to 26.1 ([#78](https://github.com/NeosiaNexus/Warp/issues/78)) ([d8e00f6](https://github.com/NeosiaNexus/Warp/commit/d8e00f6f681e1ef0709bdc6cf03d9b1593f1a5e9))
* **protocol:** decode and encode client settings per version ([#66](https://github.com/NeosiaNexus/Warp/issues/66)) ([0289fbc](https://github.com/NeosiaNexus/Warp/commit/0289fbc26bf02c76a77d15e8078fc7a99951b6f3))
* **protocol:** decode the unsigned chat command on 1.20.5 to 1.21.1 ([#68](https://github.com/NeosiaNexus/Warp/issues/68)) ([36cdda6](https://github.com/NeosiaNexus/Warp/commit/36cdda6107c786354ed9baf919be22892573e17d))
* **protocol:** encode packets registered as encode-only ([#32](https://github.com/NeosiaNexus/Warp/issues/32)) ([fd366e2](https://github.com/NeosiaNexus/Warp/commit/fd366e239d09ee353344406954ed95300d9adea4))
* **protocol:** pass the chat acknowledgements of a /server Warp answers on to the backend ([#85](https://github.com/NeosiaNexus/Warp/issues/85)) ([68be368](https://github.com/NeosiaNexus/Warp/commit/68be36866356f4c8276b5b46cd43af1996d4e220))
* **protocol:** reject negative property and pack counts ([833b53f](https://github.com/NeosiaNexus/Warp/commit/833b53f982106b9ad796e96d9633e0aea0d1dbcf))
* **protocol:** remove the previous server's tab list names from 1.7 clients on a switch ([#89](https://github.com/NeosiaNexus/Warp/issues/89)) ([1722c1a](https://github.com/NeosiaNexus/Warp/commit/1722c1a6640b14e19cad5a59ba89fb82b4b175a8))
* **protocol:** send and read the login UUID as a string before 1.16 ([#38](https://github.com/NeosiaNexus/Warp/issues/38)) ([a7fdb70](https://github.com/NeosiaNexus/Warp/commit/a7fdb7070131676fd3ea54bdecf92d9a1374eaa7))
* **protocol:** send proxy messages and handle /server on clients before 1.19.3 ([#70](https://github.com/NeosiaNexus/Warp/issues/70)) ([abaf050](https://github.com/NeosiaNexus/Warp/commit/abaf05007d1bdcdcfc7460c024377f0c0665eca8))
* **protocol:** stop decoding a read once a frame of it closed the connection ([833b53f](https://github.com/NeosiaNexus/Warp/commit/833b53f982106b9ad796e96d9633e0aea0d1dbcf))
* **protocol:** write and read the length of 1.7 plugin message payloads ([#93](https://github.com/NeosiaNexus/Warp/issues/93)) ([8489ba5](https://github.com/NeosiaNexus/Warp/commit/8489ba5112e17846555cc98275b1b9de5b299cfd))
* **protocol:** write the 1.19 to 1.19.2 signature flag in login start ([#69](https://github.com/NeosiaNexus/Warp/issues/69)) ([b2331ba](https://github.com/NeosiaNexus/Warp/commit/b2331ba1e92043233a37da11592bce1b4e12c1e7))
* **proxy:** advertise the client's own protocol in the server list ping ([#71](https://github.com/NeosiaNexus/Warp/issues/71)) ([aae25f5](https://github.com/NeosiaNexus/Warp/commit/aae25f503f5fed829d2881f9b17849a5a5801177))
* **proxy:** fall back to another server when the first one refuses a joining player ([#33](https://github.com/NeosiaNexus/Warp/issues/33)) ([1ace2b8](https://github.com/NeosiaNexus/Warp/commit/1ace2b8f9ae0e23ec1c23c793be86f4537ae8088))
* **proxy:** give backend connections their own read timeout, timeouts.backend-read (30s) ([#159](https://github.com/NeosiaNexus/Warp/issues/159)) ([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))
* **proxy:** give every invalid encryption response the same reply ([#162](https://github.com/NeosiaNexus/Warp/issues/162)) ([4bd75ff](https://github.com/NeosiaNexus/Warp/commit/4bd75ffb6d5f93374b615a72db37480163e91600))
* **proxy:** keep players alive while they are held between servers ([#141](https://github.com/NeosiaNexus/Warp/issues/141)) ([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))
* **proxy:** keep players on their server when a switch fails ([#124](https://github.com/NeosiaNexus/Warp/issues/124)) ([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))
* **proxy:** let newer players join backends before 1.20.2 through the via plugin ([#174](https://github.com/NeosiaNexus/Warp/issues/174)) ([e746adf](https://github.com/NeosiaNexus/Warp/commit/e746adf7bb582dbd7c9765455b2e23cb1a8b2563))
* **proxy:** log a read time-out at INFO, with what the connection was waiting for ([#100](https://github.com/NeosiaNexus/Warp/issues/100)) ([8e51996](https://github.com/NeosiaNexus/Warp/commit/8e519964b79e6a185d69691897aa0b0288163f1f))
* **proxy:** log in 1.19 to 1.19.2 clients that sign the verify token with their profile key ([#83](https://github.com/NeosiaNexus/Warp/issues/83)) ([b9d3c9b](https://github.com/NeosiaNexus/Warp/commit/b9d3c9b5a1688559cb869e97a24225ca9752c41b))
* **proxy:** never compress a 1.7 client's connection, Set Compression only exists from 1.8 ([#88](https://github.com/NeosiaNexus/Warp/issues/88)) ([558cdde](https://github.com/NeosiaNexus/Warp/commit/558cdde71a5d85dc7f4327b7fda13931e12cc131))
* **proxy:** prevent double fallback from handleDisconnect + channelInactive ([#27](https://github.com/NeosiaNexus/Warp/issues/27)) ([a6d79e3](https://github.com/NeosiaNexus/Warp/commit/a6d79e38a15d4ffb80cab7bd65287e71fe8558d6))
* **proxy:** refuse to share the port and stop logging lost connections as errors ([#34](https://github.com/NeosiaNexus/Warp/issues/34)) ([0ba5d1c](https://github.com/NeosiaNexus/Warp/commit/0ba5d1c6335650296302be14dbd7715807c5a96b))
* **proxy:** register the BungeeCord channel while the server configures the player ([#172](https://github.com/NeosiaNexus/Warp/issues/172)) ([e746adf](https://github.com/NeosiaNexus/Warp/commit/e746adf7bb582dbd7c9765455b2e23cb1a8b2563))
* **proxy:** send disconnect reasons in the format of each state and version ([#72](https://github.com/NeosiaNexus/Warp/issues/72)) ([dc1cfdc](https://github.com/NeosiaNexus/Warp/commit/dc1cfdcfcbe487916e8306e92146e398d08cf29b))
* **proxy:** send keep-alive ids that fit the client's protocol version ([#67](https://github.com/NeosiaNexus/Warp/issues/67)) ([a751088](https://github.com/NeosiaNexus/Warp/commit/a75108829a7d966e047ec72e8dc627b4d0e13830))
* **proxy:** send readable login disconnect reasons and resolve code scanning findings ([#65](https://github.com/NeosiaNexus/Warp/issues/65)) ([1bc411f](https://github.com/NeosiaNexus/Warp/commit/1bc411f0ad7da9731104533b945c41886831b7c5))
* **proxy:** separate handler deactivation from channel disconnect ([#23](https://github.com/NeosiaNexus/Warp/issues/23)) ([f685eab](https://github.com/NeosiaNexus/Warp/commit/f685eabd064d18c8d108fa1716f568fc46823f84))
* **proxy:** stop logging a stale read event on a connecting backend as an error ([#64](https://github.com/NeosiaNexus/Warp/issues/64)) ([1f9570b](https://github.com/NeosiaNexus/Warp/commit/1f9570b1b81781811789e5535135d182a78b24ab))
* **proxy:** switch servers and fall back on clients older than 1.20.2 ([#73](https://github.com/NeosiaNexus/Warp/issues/73)) ([6581483](https://github.com/NeosiaNexus/Warp/commit/6581483518c8bf1d3dbe7fa8d4b0e37c5ece967d))


### Performance

* **bench:** guard allocation per packet in CI and track benchmark timings ([#76](https://github.com/NeosiaNexus/Warp/issues/76)) ([ac92d01](https://github.com/NeosiaNexus/Warp/commit/ac92d014a624743a11551a1256678ae53edf5b8f))
* **protocol:** encrypt and decrypt without allocating per frame ([#152](https://github.com/NeosiaNexus/Warp/issues/152)) ([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))
* **protocol:** watch tab list and boss bar packets in place instead of re-encoding them ([#91](https://github.com/NeosiaNexus/Warp/issues/91)) ([6bcc8ac](https://github.com/NeosiaNexus/Warp/commit/6bcc8ac49409b576f0640109bd22acc0748b262b))
* **proxy:** serve each player's backend leg from its client event loop ([#30](https://github.com/NeosiaNexus/Warp/issues/30)) ([878c2e0](https://github.com/NeosiaNexus/Warp/commit/878c2e0c48743eb19033617a619ef492cbe2c356))


### Documentation

* **project:** polish the public front page and community files ([#39](https://github.com/NeosiaNexus/Warp/issues/39)) ([bd51a62](https://github.com/NeosiaNexus/Warp/commit/bd51a624c61c07d04bcf353d3edc14475c35c7bd))
* **research:** catalogue the plugin API defects of other proxies ([#133](https://github.com/NeosiaNexus/Warp/issues/133)) ([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))
* **research:** correct citations that do not say what the documents claimed ([#140](https://github.com/NeosiaNexus/Warp/issues/140)) ([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))
* **research:** design a built-in anti-bot from Sonar, LimboFilter and the proxies' trackers ([#139](https://github.com/NeosiaNexus/Warp/issues/139)) ([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))


### Build System

* **api:** target Java 21 for plugins and number every non-release build as a snapshot ([#147](https://github.com/NeosiaNexus/Warp/issues/147)) ([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))
* **project:** install the pre-commit hook from linked worktrees too ([#131](https://github.com/NeosiaNexus/Warp/issues/131)) ([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))
* **project:** publish the modules as warp-api, warp-protocol and so on ([#138](https://github.com/NeosiaNexus/Warp/issues/138)) ([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))
* **project:** upgrade Gradle from 8.12 to 9.8.0 ([#62](https://github.com/NeosiaNexus/Warp/issues/62)) ([295706c](https://github.com/NeosiaNexus/Warp/commit/295706cec9a9201303d63bada4256fed4591f09d))
* require Java 25 LTS ([#28](https://github.com/NeosiaNexus/Warp/issues/28)) ([a329739](https://github.com/NeosiaNexus/Warp/commit/a3297390667142147fd7534986f49efbddda853b))


### CI

* **deps:** bump the github-actions group across 1 directory with 12 updates ([#161](https://github.com/NeosiaNexus/Warp/issues/161)) ([e746adf](https://github.com/NeosiaNexus/Warp/commit/e746adf7bb582dbd7c9765455b2e23cb1a8b2563))
* **deps:** submit the dependency graph of integration branches too ([#154](https://github.com/NeosiaNexus/Warp/issues/154)) ([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))
* **project:** allow error_prone_annotations in the licence policy ([#60](https://github.com/NeosiaNexus/Warp/issues/60)) ([0365f34](https://github.com/NeosiaNexus/Warp/commit/0365f340589aaa9c16794f9a9e02d38ad15ac425))
* **project:** attach signed provenance to releases ([#61](https://github.com/NeosiaNexus/Warp/issues/61)) ([1bb628d](https://github.com/NeosiaNexus/Warp/commit/1bb628dff7c12c2a9a22c8e538d370d3325664a6))
* **project:** harden workflows and report tests, coverage and workflow lint ([#36](https://github.com/NeosiaNexus/Warp/issues/36)) ([b497aec](https://github.com/NeosiaNexus/Warp/commit/b497aec3f55eaf78529f457eef1ef84eb84d909d))
* **project:** mutate only the classes a pull request changes, every class nightly ([#155](https://github.com/NeosiaNexus/Warp/issues/155)) ([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))
* **project:** scan code, dependencies and workflows with GitHub's native security tools ([#40](https://github.com/NeosiaNexus/Warp/issues/40)) ([b5672fc](https://github.com/NeosiaNexus/Warp/commit/b5672fc2c557a371738c83ec2b7564d759365a36))


### Tests

* **bench:** add JMH suite with Velocity-native baselines ([#29](https://github.com/NeosiaNexus/Warp/issues/29)) ([b334cf9](https://github.com/NeosiaNexus/Warp/commit/b334cf9622b38e9c92c55c98d84408bdd2e209c4))
* **e2e:** cover forwarding, brand, chat and resource packs, and run the full matrix on protocol changes ([#82](https://github.com/NeosiaNexus/Warp/issues/82)) ([8bbd900](https://github.com/NeosiaNexus/Warp/commit/8bbd900ca3aa82adf56e400ca8468e80debdc3a6))
* **e2e:** find the snapshot jars Gradle builds instead of version.txt's names ([#164](https://github.com/NeosiaNexus/Warp/issues/164)) ([ca6dc6c](https://github.com/NeosiaNexus/Warp/commit/ca6dc6c6688324c62dde6e11f6fc463d33d0b5a4))
* **e2e:** keep ViaProxy from compressing towards the bots, which stalls its logins ([#109](https://github.com/NeosiaNexus/Warp/issues/109)) ([ba8fea8](https://github.com/NeosiaNexus/Warp/commit/ba8fea8f86ab305c357cf4f553c29aebe5dc22ee))
* **e2e:** promote the versions fixed by the protocol work ([#74](https://github.com/NeosiaNexus/Warp/issues/74)) ([c5fcb68](https://github.com/NeosiaNexus/Warp/commit/c5fcb688813896741dc948f1b2369822b9276d65))
* **e2e:** require the newest Minecraft versions on every pull request ([#101](https://github.com/NeosiaNexus/Warp/issues/101)) ([022811b](https://github.com/NeosiaNexus/Warp/commit/022811b5066f98933c4f394b2962e4760969ece9))
* **e2e:** run backends from 1.12 on Netty's epoll transport, whose NIO stops reading players ([#157](https://github.com/NeosiaNexus/Warp/issues/157)) ([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))
* **e2e:** run Minecraft 1.7 end to end, and check the tab list after a switch on every version ([#98](https://github.com/NeosiaNexus/Warp/issues/98)) ([062b323](https://github.com/NeosiaNexus/Warp/commit/062b3233de127e59ce29395e2056bbe51603ccac))
* **e2e:** run real clients through Warp against every Minecraft version ([#37](https://github.com/NeosiaNexus/Warp/issues/37)) ([67c0ba6](https://github.com/NeosiaNexus/Warp/commit/67c0ba618bf0e858ac07a78518c597efc596dcc6))
* **e2e:** settle a process's exit once its last output is read ([#144](https://github.com/NeosiaNexus/Warp/issues/144)) ([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))
* **e2e:** soak Warp nightly under load and a degraded network ([#77](https://github.com/NeosiaNexus/Warp/issues/77)) ([efc1b19](https://github.com/NeosiaNexus/Warp/commit/efc1b19b308114d68c8153a5ec4eb683fc54ef2c))
* **e2e:** tell a bot why it was refused when the refusal beats mineflayer's plugins ([#156](https://github.com/NeosiaNexus/Warp/issues/156)) ([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))
* **protocol:** fuzz the protocol decoders with Jazzer ([833b53f](https://github.com/NeosiaNexus/Warp/commit/833b53f982106b9ad796e96d9633e0aea0d1dbcf))
* **protocol:** measure test strength with mutation testing and kill surviving mutants ([#95](https://github.com/NeosiaNexus/Warp/issues/95)) ([ed9e29b](https://github.com/NeosiaNexus/Warp/commit/ed9e29b1a72ae745aac9901dcf21b861a8523a41))
* **protocol:** verify packet ids against Mojang's generated reports ([#75](https://github.com/NeosiaNexus/Warp/issues/75)) ([7921d79](https://github.com/NeosiaNexus/Warp/commit/7921d796bf5cddadaf13a241766b0d1aa5508dac))
* **proxy:** make BackendPingerTest deterministic and stop the pinger reading before its handler is in place ([#173](https://github.com/NeosiaNexus/Warp/issues/173)) ([e746adf](https://github.com/NeosiaNexus/Warp/commit/e746adf7bb582dbd7c9765455b2e23cb1a8b2563))

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
