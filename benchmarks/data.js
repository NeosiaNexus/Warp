window.BENCHMARK_DATA = {
  "lastUpdate": 1794069978012,
  "repoUrl": "https://github.com/NeosiaNexus/Warp",
  "entries": {
    "Hot path on AMD EPYC 9V45 96-Core Processor (4 CPUs)": [
      {
        "commit": {
          "author": {
            "email": "63867369+NeosiaNexus@users.noreply.github.com",
            "name": "NeosiaNexus",
            "username": "NeosiaNexus"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "ac92d014a624743a11551a1256678ae53edf5b8f",
          "message": "perf(bench): guard allocation per packet in CI and track benchmark timings (#76)\n\nA new Allocation guard job, required through CI OK, measures the bytes allocated per packet on the hot path with escape analysis off and fails above the committed baseline plus the larger of 2 B and 1%, then runs every benchmark once. Every push to main times the same benchmarks and appends them to a per-CPU-model trend on gh-pages, which comments on a commit 25% slower without failing anything. Every benchmark now extends AbstractMicrobenchmark and runs in the JVM the proxy runs in, with Unsafe allowed and event-loop threads.",
          "timestamp": "2026-10-06T18:00:55+02:00",
          "tree_id": "3159aa5724ff1075fe9afc5a33339a5b657cebeb",
          "url": "https://github.com/NeosiaNexus/Warp/commit/ac92d014a624743a11551a1256678ae53edf5b8f"
        },
        "date": 1791303322624,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=CHUNK]",
            "value": 1653.3,
            "range": "± 56.0",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V45 96-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=ENTITY_MOVE]",
            "value": 114.6,
            "range": "± 1.6",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V45 96-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=MIXED]",
            "value": 273.4,
            "range": "± 2.3",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V45 96-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=CHUNK]",
            "value": 67122.1,
            "range": "± 1001.9",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V45 96-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=ENTITY_MOVE]",
            "value": 492.7,
            "range": "± 8.8",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V45 96-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=MIXED]",
            "value": 4816.8,
            "range": "± 50.6",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V45 96-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "PacketIdPeekBenchmark.peek[workload=CHUNK]",
            "value": 1161.5,
            "range": "± 7.5",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V45 96-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "PacketIdPeekBenchmark.peek[workload=MIXED]",
            "value": 878.8,
            "range": "± 5.6",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V45 96-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "63867369+NeosiaNexus@users.noreply.github.com",
            "name": "NeosiaNexus",
            "username": "NeosiaNexus"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "b9d3c9b5a1688559cb869e97a24225ca9752c41b",
          "message": "fix(proxy): log in 1.19 to 1.19.2 clients that sign the verify token with their profile key (#83)\n\nA 1.19 to 1.19.2 client with a chat signing key signs the verify token instead of encrypting it, and Warp failed its login with a padding error. Login Start now carries the profile key and Encryption Response the signed token; in online mode Warp checks the key against Mojang's certificate (or -Dwarp.profilekeys.signer) and verifies the token signature as vanilla and Velocity do, refusing a bad key with invalid_public_key_signature. Offline mode ignores the key, and the E2E harness signs bot keys to cover both modes on 1.19 to 1.19.2. Fixes #79.",
          "timestamp": "2026-10-06T18:21:40+02:00",
          "tree_id": "c86ae06713ff19db5bb451759990dffc09854a56",
          "url": "https://github.com/NeosiaNexus/Warp/commit/b9d3c9b5a1688559cb869e97a24225ca9752c41b"
        },
        "date": 1791304510188,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=CHUNK]",
            "value": 1638.4,
            "range": "± 54.3",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V45 96-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=ENTITY_MOVE]",
            "value": 114.1,
            "range": "± 0.9",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V45 96-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=MIXED]",
            "value": 286,
            "range": "± 2.6",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V45 96-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=CHUNK]",
            "value": 67311.2,
            "range": "± 524.9",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V45 96-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=ENTITY_MOVE]",
            "value": 452.5,
            "range": "± 7",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V45 96-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=MIXED]",
            "value": 4595.1,
            "range": "± 51.2",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V45 96-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "PacketIdPeekBenchmark.peek[workload=CHUNK]",
            "value": 1099.8,
            "range": "± 10.2",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V45 96-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "PacketIdPeekBenchmark.peek[workload=MIXED]",
            "value": 874.7,
            "range": "± 10.8",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V45 96-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "63867369+NeosiaNexus@users.noreply.github.com",
            "name": "NeosiaNexus",
            "username": "NeosiaNexus"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "68be36866356f4c8276b5b46cd43af1996d4e220",
          "message": "fix(protocol): pass the chat acknowledgements of a /server Warp answers on to the backend (#85)\n\nFrom 1.19.3 to 1.20.4 every command carries a last-seen offset that the server applies to its window over the signed chat it sent. Warp kept its own /server commands from the backend, offset included, so the backend's window fell behind the client's and it kicked the player at their next chat message. Warp now reads the offset and sends it to the backend as an Acknowledge Message, as Velocity does. The E2E harness lets bots sign chat through a mock Mojang and checks chat around /server on every version. Fixes #81.",
          "timestamp": "2026-10-06T19:04:08+02:00",
          "tree_id": "7fdf15eef7146b45c50b9a4cdc8c5f7f25b8c0e8",
          "url": "https://github.com/NeosiaNexus/Warp/commit/68be36866356f4c8276b5b46cd43af1996d4e220"
        },
        "date": 1791307048117,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=CHUNK]",
            "value": 1574.8,
            "range": "± 34.8",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V45 96-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=ENTITY_MOVE]",
            "value": 108.2,
            "range": "± 2",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V45 96-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=MIXED]",
            "value": 274.1,
            "range": "± 3.5",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V45 96-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=CHUNK]",
            "value": 66702.7,
            "range": "± 533.9",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V45 96-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=ENTITY_MOVE]",
            "value": 472.5,
            "range": "± 16",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V45 96-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=MIXED]",
            "value": 4737.4,
            "range": "± 64.3",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V45 96-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "PacketIdPeekBenchmark.peek[workload=CHUNK]",
            "value": 1198.6,
            "range": "± 36.7",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V45 96-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "PacketIdPeekBenchmark.peek[workload=MIXED]",
            "value": 885,
            "range": "± 22.5",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V45 96-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "63867369+NeosiaNexus@users.noreply.github.com",
            "name": "NeosiaNexus",
            "username": "NeosiaNexus"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "8e519964b79e6a185d69691897aa0b0288163f1f",
          "message": "fix(proxy): log a read time-out at INFO, with what the connection was waiting for (#100)\n\nA read time-out was logged at ERROR with a stack trace, as if it were a proxy fault, and said nothing about which side went quiet. It is now one INFO line naming the protocol state, the quiet side, whether Warp was reading the peer and how many bytes were queued towards it; genuine errors still log at ERROR. The read time-out is defined once for both pipelines, and the end-to-end harness still fails a run on the new line. Fixes #99.",
          "timestamp": "2026-10-06T19:27:48+02:00",
          "tree_id": "e34e896294d96222e2f9685295b1ce2ee019581a",
          "url": "https://github.com/NeosiaNexus/Warp/commit/8e519964b79e6a185d69691897aa0b0288163f1f"
        },
        "date": 1791308476883,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=CHUNK]",
            "value": 1617.5,
            "range": "± 35.8",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V45 96-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=ENTITY_MOVE]",
            "value": 115.4,
            "range": "± 1.2",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V45 96-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=MIXED]",
            "value": 293,
            "range": "± 15.5",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V45 96-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=CHUNK]",
            "value": 68633.7,
            "range": "± 776.6",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V45 96-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=ENTITY_MOVE]",
            "value": 496.6,
            "range": "± 17.5",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V45 96-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=MIXED]",
            "value": 4853.7,
            "range": "± 39.3",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V45 96-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "PacketIdPeekBenchmark.peek[workload=CHUNK]",
            "value": 1130.3,
            "range": "± 10",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V45 96-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "PacketIdPeekBenchmark.peek[workload=MIXED]",
            "value": 874.8,
            "range": "± 16.9",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V45 96-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "63867369+NeosiaNexus@users.noreply.github.com",
            "name": "NeosiaNexus",
            "username": "NeosiaNexus"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "558cdde71a5d85dc7f4327b7fda13931e12cc131",
          "message": "fix(proxy): never compress a 1.7 client's connection, Set Compression only exists from 1.8 (#88)\n\nCompression and its Set Compression login packet appeared in 1.8, so Warp threw an EncoderException and dropped every 1.7 client when compression was on. ServerLoginContext.compressionThreshold(ProtocolVersion) now returns the configured threshold from 1.8 and -1 before, and both the client login and the backend compression warnings use it. Nothing changes from 1.8 on. Fixes #84.",
          "timestamp": "2026-10-06T20:50:52+02:00",
          "tree_id": "f1007dd2378e9f3b750ff5fdd1c99cd5c5175760",
          "url": "https://github.com/NeosiaNexus/Warp/commit/558cdde71a5d85dc7f4327b7fda13931e12cc131"
        },
        "date": 1791313450499,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=CHUNK]",
            "value": 1609.9,
            "range": "± 42.8",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V45 96-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=ENTITY_MOVE]",
            "value": 111.1,
            "range": "± 1.7",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V45 96-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=MIXED]",
            "value": 277.4,
            "range": "± 4.5",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V45 96-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=CHUNK]",
            "value": 67338.9,
            "range": "± 816.9",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V45 96-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=ENTITY_MOVE]",
            "value": 479.4,
            "range": "± 11.1",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V45 96-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=MIXED]",
            "value": 4876.5,
            "range": "± 43.1",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V45 96-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "PacketIdPeekBenchmark.peek[workload=CHUNK]",
            "value": 1133.2,
            "range": "± 15.8",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V45 96-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "PacketIdPeekBenchmark.peek[workload=MIXED]",
            "value": 840.9,
            "range": "± 17.5",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V45 96-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          }
        ]
      }
    ],
    "Hot path on INTEL(R) XEON(R) PLATINUM 8573C (4 CPUs)": [
      {
        "commit": {
          "author": {
            "email": "63867369+NeosiaNexus@users.noreply.github.com",
            "name": "NeosiaNexus",
            "username": "NeosiaNexus"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "6bcc8ac49409b576f0640109bd22acc0748b262b",
          "message": "perf(protocol): watch tab list and boss bar packets in place instead of re-encoding them (#91)\n\nBefore 1.20.2, Warp decoded and re-encoded every tab list and boss bar packet only to follow the UUIDs it clears on a server switch, which cost a copy, a decode, an encode and a deflate per packet. They are now watched: the decoder reads their UUIDs and actions in place, reports only additions and removals, and forwards the original frame like a blind one, so a boss bar update allocates 104 B instead of 697 B and a compressed latency update takes about 1 microsecond instead of 40. The crowd E2E scenario now checks that the bots that switched list none of those that stayed on the lobby. Fixes #80.",
          "timestamp": "2026-10-06T20:23:50+02:00",
          "tree_id": "d694a0b630dba05edc67e8d5edc984bd522ed42c",
          "url": "https://github.com/NeosiaNexus/Warp/commit/6bcc8ac49409b576f0640109bd22acc0748b262b"
        },
        "date": 1791311840855,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=CHUNK]",
            "value": 2497.9,
            "range": "± 99.8",
            "unit": "ns/packet",
            "extra": "INTEL(R) XEON(R) PLATINUM 8573C (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=ENTITY_MOVE]",
            "value": 151.7,
            "range": "± 0.5",
            "unit": "ns/packet",
            "extra": "INTEL(R) XEON(R) PLATINUM 8573C (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=MIXED]",
            "value": 349.9,
            "range": "± 4.4",
            "unit": "ns/packet",
            "extra": "INTEL(R) XEON(R) PLATINUM 8573C (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=CHUNK]",
            "value": 69525.8,
            "range": "± 476.9",
            "unit": "ns/packet",
            "extra": "INTEL(R) XEON(R) PLATINUM 8573C (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=ENTITY_MOVE]",
            "value": 564.9,
            "range": "± 8.7",
            "unit": "ns/packet",
            "extra": "INTEL(R) XEON(R) PLATINUM 8573C (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=MIXED]",
            "value": 4992.2,
            "range": "± 7.2",
            "unit": "ns/packet",
            "extra": "INTEL(R) XEON(R) PLATINUM 8573C (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "PacketIdPeekBenchmark.peek[workload=CHUNK]",
            "value": 1383.1,
            "range": "± 2.9",
            "unit": "ns/packet",
            "extra": "INTEL(R) XEON(R) PLATINUM 8573C (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "PacketIdPeekBenchmark.peek[workload=MIXED]",
            "value": 1054.4,
            "range": "± 1.7",
            "unit": "ns/packet",
            "extra": "INTEL(R) XEON(R) PLATINUM 8573C (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "63867369+NeosiaNexus@users.noreply.github.com",
            "name": "NeosiaNexus",
            "username": "NeosiaNexus"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "4bd75ffb6d5f93374b615a72db37480163e91600",
          "message": "fix(proxy): give every invalid encryption response the same reply (#162)\n\nPorts the one change of the unmerged branch\n`security/uniform-key-exchange-failures` that `main` does not have yet\n(35e1092). The other commits of that branch are on `main` already, in\nanother form: see the end.\n\n## Problem\n\nThe protocol fixes RSA with PKCS#1 v1.5 padding for the encryption\nresponse, which falls to Bleichenbacher's attack wherever a client can\ntell a badly padded ciphertext from a well padded one: by the reply, or\nby the time the reply takes. With such an oracle, an attacker who\nrecorded a login can recover its shared secret, one chosen ciphertext\nper login attempt.\n\n`LoginSessionHandler` gave one away. An invalid response got a reason\nthat depended on why it failed:\n\n| Response | Reason before |\n|---|---|\n| Token or secret that is not a valid PKCS#1 v1.5 ciphertext |\n`Encryption error: javax.crypto.BadPaddingException: ...` |\n| Well padded token, wrong value | `Verify token mismatch` |\n| Well padded secret, not 16 bytes | `Invalid shared secret length` |\n| 1.19 to 1.19.2 signed token that does not check | `Invalid verify\ntoken signature` |\n\nThe secret is the field an attacker probes (they encrypt their own\nsession's token correctly): a bad padding and a good padding of the\nwrong length got different replies. Even with one reply, the JDK's\n`RSA/ECB/PKCS1Padding` throws for a bad padding and returns otherwise,\nso building the exception makes a bad padding take time a good one does\nnot.\n\n## What changed\n\n- Every invalid encryption response gets the same reply, `Encryption\nfailed`, whatever the cause. It is still counted as a failed\nauthentication (`Refusal.AUTHENTICATION_FAILED`), so the anti-bot\ndetector and the aggregated refusal report see it as before, and it is\nlogged at DEBUG only.\n- New `dev.warp.proxy.auth.Pkcs1Decryption` decrypts each value with raw\nRSA (`RSA/ECB/NoPadding`) and checks the PKCS#1 v1.5 padding itself,\nwithout branching on it. The length of each value is known (4 bytes for\nthe token, 16 for the secret), so a valid block has one layout: `00 02`,\nat least 8 nonzero padding bytes, `00`, the value. Anything else is\ninvalid, including a well padded value of another length, so that case\ncannot be told apart either. The token is compared with\n`MessageDigest.isEqual` whatever its padding.\n- Each value gets its own `Cipher`.\n- As in the vanilla server (`ServerLoginPacketListenerImpl.handleKey`\nchecks the challenge before `getSecretKey`), the token is checked before\nthe secret is decrypted. The work a response costs depends on whether\nits token checks, which the client knows, never on a padding.\n- A client with a 1.19 to 1.19.2 profile key must still sign the token,\none without must encrypt it; both failures get the same reply as the\nrest.\n- A failure to set up AES/CFB8 (a broken runtime, nothing a client can\ncause) is logged at ERROR and closes with `Internal error`, instead of\npassing as a client's encryption error.\n\nWhat stays observable is what the protocol requires: a well padded\n16-byte secret behind a valid token enables encryption and asks the\nsession server. Vanilla and every proxy below do the same.\n\n## Known issues in other proxies that Warp avoids\n\nChecked against their default branches (Velocity `ed4c46e`, BungeeCord\n`55e1b0b`, Gate `ebecaf7`). All three check the token before the secret\nand create a fresh RSA cipher per value; none checks the secret's length\nexplicitly.\n\n- **BungeeCord** sends the exception to the client.\n`InitialHandler.exception` disconnects with `Util.exception(t)` while in\nthe ENCRYPT state ([InitialHandler\nL179-L188](https://github.com/SpigotMC/BungeeCord/blob/55e1b0bbe776e4b3af6c572a2b3d43fb60a526b1/proxy/src/main/java/net/md_5/bungee/connection/InitialHandler.java#L179-L188),\n[Util\nL103-L109](https://github.com/SpigotMC/BungeeCord/blob/55e1b0bbe776e4b3af6c572a2b3d43fb60a526b1/api/src/main/java/net/md_5/bungee/Util.java#L103-L109)):\na bad padding reads `BadPaddingException : Decryption error`, a wrong\ntoken `IllegalStateException : Invalid verification`. A direct padding\noracle. Each bad padding is also logged at SEVERE with its stack trace\n([HandlerBoss\nL169-L236](https://github.com/SpigotMC/BungeeCord/blob/55e1b0bbe776e4b3af6c572a2b3d43fb60a526b1/proxy/src/main/java/net/md_5/bungee/netty/HandlerBoss.java#L169-L236)),\nwhich a client can repeat at will:\n[BungeeCord#3432](https://github.com/SpigotMC/BungeeCord/issues/3432)\nreports a misbehaving client filling the log with\n`javax.crypto.BadPaddingException`.\n- **Gate** decrypts with Go's `rsa.DecryptPKCS1v15` ([authenticator.go\nL194-L204](https://github.com/minekube/gate/blob/ebecaf74132249268dcc5fd9c0fed2424a363b8c/pkg/edition/java/auth/authenticator.go#L194-L204)),\nwhose documentation warns that whether it returns an error discloses\nsecret information, rather than the constant time\n`DecryptPKCS1v15SessionKey` ([pkcs1v15.go\nL104-L168](https://github.com/golang/go/blob/44b6bc904602c7ffe674956d59f804a997f9bae3/src/crypto/rsa/pkcs1v15.go#L104-L168)).\nA badly padded secret closes the connection silently, while a well\npadded one of a length AES rejects gets a `Disconnect` with an internal\nerror message and an ERROR log ([session_client_initial_login.go\nL352-L367](https://github.com/minekube/gate/blob/ebecaf74132249268dcc5fd9c0fed2424a363b8c/pkg/edition/java/proxy/session_client_initial_login.go#L352-L367)):\nan oracle on the shared secret, reachable with a valid token for the\nattacker's own session. 24 and 32 byte secrets are accepted, since only\n`aes.NewCipher` checks the length.\n- **Velocity** closes silently in every case, so the client sees one\noutcome. But a bad padding is a `GeneralSecurityException` logged at\nERROR with its stack trace ([InitialLoginSessionHandler\nL267-L270](https://github.com/PaperMC/Velocity/blob/ed4c46ef43e7d93db0f69522a354a39412c66117/proxy/src/main/java/com/velocitypowered/proxy/connection/client/InitialLoginSessionHandler.java#L267-L270)),\none per forged packet, while a wrong token is an `IllegalStateException`\nthe frontline handler does not log. Its `EncryptionUtils.decryptRsa`\nuses the JDK's PKCS#1 v1.5 cipher, so the exception's cost remains.\n\nNo issue in the three repositories mentions a padding oracle or\nBleichenbacher.\n\n## Tests\n\n- `Pkcs1DecryptionTest`, every ciphertext built by hand: decrypts a\n4-byte and a 16-byte value, a value that leaves exactly the 8 required\npadding bytes, and a ciphertext without its leading zero; refuses a\nnonzero first byte, block types 0 and 1, a zero in the padding, a\nmissing separator, well padded values of 0, 4, 15, 17 and 32 bytes,\nfewer than 8 padding bytes, an expected value longer than the block, a\nnumber not below the modulus, an empty ciphertext and one longer than\nthe modulus. The JDK's PKCS#1 v1.5 cipher, which clients encrypt with,\nlogs in through it in the existing `LoginSessionHandlerTest` profile key\ntests (1.8 to the latest version).\n- `LoginSessionHandlerTest` \"encryption responses\": RSA ciphertexts\nbuilt by hand so that the test chooses the padding. Nine invalid\nresponses (badly padded token, zero in the token's padding, wrong token,\n5-byte token, badly padded secret, secret without separator, 15 and\n17-byte secrets, secret above the modulus) all get `{\"text\":\"Encryption\nfailed\"}`, install no cipher and never reach the session server. A valid\nresponse on 1.7.2, 1.8, 1.19, 1.19.3 and the latest version installs the\ncipher, asks the session server with the hash of the hand-built secret,\nand the first thing the proxy sends, decrypted with that secret, is\nLogin Success. The profile key tests now expect the same reply.\n- `ConnectionLimitsTest` still counts forged responses as failed\nauthentications without logging them.\n- `./gradlew build -x installGitHooks`: green. PIT on the changed\nclasses (`:proxy:pitest -Pwarp.pitest.since=origin/main`): 59%,\nthreshold 47.\n- E2E online mode: `e2e/run.sh --mc 1.8.8,26.1.2 --variants online --jar\nproxy/build/libs/warp-*.jar`, all pass (login through the encrypted\npath, chat signed on 26.1.2, and the flood scenario's 50 forged logins\nall closed with 5 log lines):\n\n```\n  PASS  1.8.8    77s  online:pass\n  PASS  26.1.2   93s  online:pass\n```\n\n## Rest of the branch, already on `main`\n\n| Commit | On `main` as |\n|---|---|\n| e334427 say where a generated forwarding secret was written |\n`WarpConfig` logs `Generated a random forwarding secret in {}` with the\nfile |\n| cae875f say where the protocol fixes the handshake cryptography |\ncomments in `AesCfb8` (IV), `WarpServer.RSA_KEY_SIZE` (1024 bits),\n`ServerHash` (SHA-1) |\n| 2010fd1 unnamed patterns for ignored packets | every handler matches\nthem with `_` |\n| 9881119 disconnect reasons the client can read during login |\n`LoginDisconnect.ofPlainText` / `TextComponent.plainTextJson`, tested in\n\"disconnect reasons\" |\n| 69b5f54 DeflatePeek's code-length run bound | `DeflatePeek` computes\n`end` once; `ZlibStreams.maximalDynamicBlock` tests |",
          "timestamp": "2026-11-03T11:01:47+01:00",
          "tree_id": "7f4cab62f308296db787817f36b91eb188232fe5",
          "url": "https://github.com/NeosiaNexus/Warp/commit/4bd75ffb6d5f93374b615a72db37480163e91600"
        },
        "date": 1793702180460,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "CipherBenchmark.decrypt[buffer=DIRECT,frameSize=1024]",
            "value": 20026.7,
            "range": "± 88.2",
            "unit": "ns/packet",
            "extra": "INTEL(R) XEON(R) PLATINUM 8573C (4 CPUs)\nJDK 25.0.5, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "CipherBenchmark.decrypt[buffer=HEAP,frameSize=1024]",
            "value": 19533.9,
            "range": "± 41.8",
            "unit": "ns/packet",
            "extra": "INTEL(R) XEON(R) PLATINUM 8573C (4 CPUs)\nJDK 25.0.5, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "CipherBenchmark.encrypt[buffer=DIRECT,frameSize=1024]",
            "value": 20293.9,
            "range": "± 111.1",
            "unit": "ns/packet",
            "extra": "INTEL(R) XEON(R) PLATINUM 8573C (4 CPUs)\nJDK 25.0.5, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "CipherBenchmark.encrypt[buffer=HEAP,frameSize=1024]",
            "value": 20553.1,
            "range": "± 123.4",
            "unit": "ns/packet",
            "extra": "INTEL(R) XEON(R) PLATINUM 8573C (4 CPUs)\nJDK 25.0.5, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "CodecCostBenchmark.deflate[connections=1,implementation=WARP_NATIVE,level=6,workload=CHUNK]",
            "value": 283299.2,
            "range": "± 506.5",
            "unit": "ns/packet",
            "extra": "INTEL(R) XEON(R) PLATINUM 8573C (4 CPUs)\nJDK 25.0.5, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "CodecCostBenchmark.deflate[connections=1,implementation=WARP_NATIVE,level=6,workload=MIXED]",
            "value": 11716,
            "range": "± 18.3",
            "unit": "ns/packet",
            "extra": "INTEL(R) XEON(R) PLATINUM 8573C (4 CPUs)\nJDK 25.0.5, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "CodecCostBenchmark.deflate[connections=1,implementation=WARP_NATIVE,level=6,workload=SMALL]",
            "value": 16173.4,
            "range": "± 45.0",
            "unit": "ns/packet",
            "extra": "INTEL(R) XEON(R) PLATINUM 8573C (4 CPUs)\nJDK 25.0.5, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "CodecCostBenchmark.inflate[connections=1,implementation=WARP_NATIVE,level=6,workload=CHUNK]",
            "value": 25197.9,
            "range": "± 111.1",
            "unit": "ns/packet",
            "extra": "INTEL(R) XEON(R) PLATINUM 8573C (4 CPUs)\nJDK 25.0.5, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "CodecCostBenchmark.inflate[connections=1,implementation=WARP_NATIVE,level=6,workload=MIXED]",
            "value": 1292.3,
            "range": "± 4.0",
            "unit": "ns/packet",
            "extra": "INTEL(R) XEON(R) PLATINUM 8573C (4 CPUs)\nJDK 25.0.5, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "CodecCostBenchmark.inflate[connections=1,implementation=WARP_NATIVE,level=6,workload=SMALL]",
            "value": 4859,
            "range": "± 18.6",
            "unit": "ns/packet",
            "extra": "INTEL(R) XEON(R) PLATINUM 8573C (4 CPUs)\nJDK 25.0.5, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=CHUNK]",
            "value": 3079.4,
            "range": "± 70.4",
            "unit": "ns/packet",
            "extra": "INTEL(R) XEON(R) PLATINUM 8573C (4 CPUs)\nJDK 25.0.5, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=ENTITY_MOVE]",
            "value": 182.2,
            "range": "± 0.9",
            "unit": "ns/packet",
            "extra": "INTEL(R) XEON(R) PLATINUM 8573C (4 CPUs)\nJDK 25.0.5, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=MIXED]",
            "value": 420.6,
            "range": "± 3.4",
            "unit": "ns/packet",
            "extra": "INTEL(R) XEON(R) PLATINUM 8573C (4 CPUs)\nJDK 25.0.5, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=CHUNK]",
            "value": 80826.6,
            "range": "± 129.5",
            "unit": "ns/packet",
            "extra": "INTEL(R) XEON(R) PLATINUM 8573C (4 CPUs)\nJDK 25.0.5, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=ENTITY_MOVE]",
            "value": 617.5,
            "range": "± 1.8",
            "unit": "ns/packet",
            "extra": "INTEL(R) XEON(R) PLATINUM 8573C (4 CPUs)\nJDK 25.0.5, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=MIXED]",
            "value": 5962.9,
            "range": "± 226",
            "unit": "ns/packet",
            "extra": "INTEL(R) XEON(R) PLATINUM 8573C (4 CPUs)\nJDK 25.0.5, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "PacketIdPeekBenchmark.peek[workload=CHUNK]",
            "value": 1677.1,
            "range": "± 7.1",
            "unit": "ns/packet",
            "extra": "INTEL(R) XEON(R) PLATINUM 8573C (4 CPUs)\nJDK 25.0.5, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "PacketIdPeekBenchmark.peek[workload=MIXED]",
            "value": 1280.5,
            "range": "± 6.6",
            "unit": "ns/packet",
            "extra": "INTEL(R) XEON(R) PLATINUM 8573C (4 CPUs)\nJDK 25.0.5, 3 forks × 10 iterations of 2 s"
          }
        ]
      }
    ],
    "Hot path on AMD EPYC 9V74 80-Core Processor (4 CPUs)": [
      {
        "commit": {
          "author": {
            "email": "63867369+NeosiaNexus@users.noreply.github.com",
            "name": "NeosiaNexus",
            "username": "NeosiaNexus"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "1722c1a6640b14e19cad5a59ba89fb82b4b175a8",
          "message": "fix(protocol): remove the previous server's tab list names from 1.7 clients on a switch (#89)\n\nBefore 1.20.2 the new server's Join Game keeps the client's tab list, and a 1.7 client keys it by name with its own Player List Item packet, which Warp did not follow, so the previous server's players stayed listed after a switch. Warp now watches that packet on 1.7 like the 1.8+ tab list packets: it reads the name in place, forwards the frame verbatim, and follows the names the current server lists. On a switch it sends one removal per remaining name before the new Join Game, as Velocity does. Fixes #86.",
          "timestamp": "2026-10-06T21:17:55+02:00",
          "tree_id": "398d818ca88a0a73e5e954aabc389dd8eacdaaad",
          "url": "https://github.com/NeosiaNexus/Warp/commit/1722c1a6640b14e19cad5a59ba89fb82b4b175a8"
        },
        "date": 1791315112537,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=CHUNK]",
            "value": 1904.8,
            "range": "± 43.2",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V74 80-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=ENTITY_MOVE]",
            "value": 117.1,
            "range": "± 0.7",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V74 80-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=MIXED]",
            "value": 331.4,
            "range": "± 6.4",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V74 80-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=CHUNK]",
            "value": 84301.4,
            "range": "± 2378.1",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V74 80-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=ENTITY_MOVE]",
            "value": 558.2,
            "range": "± 8.7",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V74 80-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=MIXED]",
            "value": 5796.5,
            "range": "± 34.9",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V74 80-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "PacketIdPeekBenchmark.peek[workload=CHUNK]",
            "value": 1327,
            "range": "± 3.8",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V74 80-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "PacketIdPeekBenchmark.peek[workload=MIXED]",
            "value": 1087.5,
            "range": "± 3.2",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V74 80-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "63867369+NeosiaNexus@users.noreply.github.com",
            "name": "NeosiaNexus",
            "username": "NeosiaNexus"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "8489ba5112e17846555cc98275b1b9de5b299cfd",
          "message": "fix(protocol): write and read the length of 1.7 plugin message payloads (#93)\n\nBefore 1.8 the plugin message payload sits behind its length, a short in vanilla that Forge extends to three bytes from 32 KiB; the codec only knew the 1.8 layout and would have corrupted a 1.7 connection on the first plugin message sent. The codec now writes and reads that length before 1.8, as Velocity does, bounded by Forge's 0x1FFF9A, and refuses a payload too large to encode or a length past the end of the packet. Nothing changes from 1.8. Fixes #92.",
          "timestamp": "2026-10-06T21:37:56+02:00",
          "tree_id": "af9ffad3a3ad102794351d64fa3fab71c3952ff6",
          "url": "https://github.com/NeosiaNexus/Warp/commit/8489ba5112e17846555cc98275b1b9de5b299cfd"
        },
        "date": 1791316290377,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=CHUNK]",
            "value": 1898.3,
            "range": "± 29",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V74 80-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=ENTITY_MOVE]",
            "value": 117.5,
            "range": "± 1.3",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V74 80-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=MIXED]",
            "value": 321.3,
            "range": "± 1.7",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V74 80-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=CHUNK]",
            "value": 82129.8,
            "range": "± 109.5",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V74 80-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=ENTITY_MOVE]",
            "value": 571.9,
            "range": "± 20.3",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V74 80-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=MIXED]",
            "value": 5794.1,
            "range": "± 12.3",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V74 80-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "PacketIdPeekBenchmark.peek[workload=CHUNK]",
            "value": 1325.9,
            "range": "± 3",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V74 80-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "PacketIdPeekBenchmark.peek[workload=MIXED]",
            "value": 1088.2,
            "range": "± 1.7",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V74 80-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "63867369+NeosiaNexus@users.noreply.github.com",
            "name": "NeosiaNexus",
            "username": "NeosiaNexus"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "062b3233de127e59ce29395e2056bbe51603ccac",
          "message": "test(e2e): run Minecraft 1.7 end to end, and check the tab list after a switch on every version (#98)\n\nAdds 1.7.2 (vanilla, bridged through ViaProxy) and 1.7.10 (Paper, native) to the E2E matrix, with 1.7 bots built on minecraft-protocol alone since mineflayer starts at 1.8.8, and runs 1.7.10 on every pull request. A new tab-list scenario checks on every version that a switch leaves none of the previous server's players listed. One tab list model, keyed by name on 1.7 and by UUID after, now serves both that scenario and the crowd check, which therefore covers 1.7 too. Fixes #87.",
          "timestamp": "2026-10-06T22:01:38+02:00",
          "tree_id": "e3b32803899d7466eac43f486a444400bfc27b55",
          "url": "https://github.com/NeosiaNexus/Warp/commit/062b3233de127e59ce29395e2056bbe51603ccac"
        },
        "date": 1791317754952,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=CHUNK]",
            "value": 2318.1,
            "range": "± 42.7",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V74 80-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=ENTITY_MOVE]",
            "value": 152.6,
            "range": "± 1.6",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V74 80-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=MIXED]",
            "value": 424.4,
            "range": "± 6",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V74 80-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=CHUNK]",
            "value": 109532.1,
            "range": "± 1198.4",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V74 80-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=ENTITY_MOVE]",
            "value": 731.3,
            "range": "± 4.4",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V74 80-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=MIXED]",
            "value": 7659.3,
            "range": "± 23.2",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V74 80-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "PacketIdPeekBenchmark.peek[workload=CHUNK]",
            "value": 1773.9,
            "range": "± 4.8",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V74 80-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "PacketIdPeekBenchmark.peek[workload=MIXED]",
            "value": 1478.9,
            "range": "± 2.3",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V74 80-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "63867369+NeosiaNexus@users.noreply.github.com",
            "name": "NeosiaNexus",
            "username": "NeosiaNexus"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "833b53f982106b9ad796e96d9633e0aea0d1dbcf",
          "message": "test(protocol): fuzz the protocol decoders with Jazzer (#94)\n\nFive Jazzer fuzz tests hold VarInt, FrameDecoder, DeflatePeek, CompressionDecoder and MinecraftDecoder to reference implementations, on every state, protocol version and compressed framing, truthful or lying. Their seeds and findings replay in every build; exploratory fuzzing runs on pull requests and nightly without gating CI, and the nightly run minimizes the shared corpus. The fuzzing found and this fixes negative element counts reaching collection sizes, now checked by VarInt.readCount, and frames decoded or rejected after one of the same read closed the connection.",
          "timestamp": "2026-10-07T01:53:35+02:00",
          "tree_id": "04290d34f1bdbc93784411792c98ce401b031cb5",
          "url": "https://github.com/NeosiaNexus/Warp/commit/833b53f982106b9ad796e96d9633e0aea0d1dbcf"
        },
        "date": 1791331693062,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=CHUNK]",
            "value": 1906,
            "range": "± 46.9",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V74 80-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=ENTITY_MOVE]",
            "value": 117.5,
            "range": "± 2.8",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V74 80-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=MIXED]",
            "value": 369.3,
            "range": "± 41.6",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V74 80-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=CHUNK]",
            "value": 81846,
            "range": "± 59.5",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V74 80-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=ENTITY_MOVE]",
            "value": 558,
            "range": "± 2.8",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V74 80-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=MIXED]",
            "value": 5785.9,
            "range": "± 38.4",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V74 80-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "PacketIdPeekBenchmark.peek[workload=CHUNK]",
            "value": 1322,
            "range": "± 0.8",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V74 80-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "PacketIdPeekBenchmark.peek[workload=MIXED]",
            "value": 1090.9,
            "range": "± 3.5",
            "unit": "ns/packet",
            "extra": "AMD EPYC 9V74 80-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          }
        ]
      }
    ],
    "Hot path on AMD EPYC 7763 64-Core Processor (4 CPUs)": [
      {
        "commit": {
          "author": {
            "email": "63867369+NeosiaNexus@users.noreply.github.com",
            "name": "NeosiaNexus",
            "username": "NeosiaNexus"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "efc1b19b308114d68c8153a5ec4eb683fc54ef2c",
          "message": "test(e2e): soak Warp nightly under load and a degraded network (#77)\n\nAdds a soak mode to the e2e harness: e2e/run.sh --soak MINUTES keeps a crowd of bots switching and reconnecting through Warp while it samples Warp's memory, descriptors, connections and threads, and fails on sustained growth, on connections or objects Warp keeps once every player left, and on any bot failure. --netem degrades the network between the bots and Warp with tc netem on Warp's ports only, and removes it on exit, on signals and after a killed run. The new soak.yml workflow runs it every night for 30 minutes on a clean and on a degraded network, and on demand.",
          "timestamp": "2026-10-06T22:34:13+02:00",
          "tree_id": "a2ff9f9b5f772dc21238f136a9a49c4076316cac",
          "url": "https://github.com/NeosiaNexus/Warp/commit/efc1b19b308114d68c8153a5ec4eb683fc54ef2c"
        },
        "date": 1791319688302,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=CHUNK]",
            "value": 2777.3,
            "range": "± 50.4",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=ENTITY_MOVE]",
            "value": 142.7,
            "range": "± 2.1",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=MIXED]",
            "value": 421.2,
            "range": "± 2.6",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=CHUNK]",
            "value": 104837.1,
            "range": "± 101.1",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=ENTITY_MOVE]",
            "value": 708.1,
            "range": "± 5.0",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=MIXED]",
            "value": 7572,
            "range": "± 57.0",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "PacketIdPeekBenchmark.peek[workload=CHUNK]",
            "value": 1858.6,
            "range": "± 26.5",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "PacketIdPeekBenchmark.peek[workload=MIXED]",
            "value": 1426.4,
            "range": "± 6.1",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "63867369+NeosiaNexus@users.noreply.github.com",
            "name": "NeosiaNexus",
            "username": "NeosiaNexus"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "8bbd900ca3aa82adf56e400ca8468e80debdc3a6",
          "message": "test(e2e): cover forwarding, brand, chat and resource packs, and run the full matrix on protocol changes (#82)\n\nThe end-to-end harness now checks Velocity modern forwarding against Paper, server brands, chat between players and resource pack offers on every version, and pull requests that change Warp's modules or the harness run the whole matrix inside CI OK. A backend that hangs while booting has its threads dumped and boots once more (#97), and Paper 1.19 declares a concurrentLogins quirk that staggers the crowd's switches by 50 ms there. Bots now answer resource packs with the pack's UUID, which servers from 1.21.8 require. Fixes #103.",
          "timestamp": "2026-10-07T00:08:03+02:00",
          "tree_id": "51bcee177d5b5987da08f6a9e015d7bbcb14107e",
          "url": "https://github.com/NeosiaNexus/Warp/commit/8bbd900ca3aa82adf56e400ca8468e80debdc3a6"
        },
        "date": 1791325314371,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=CHUNK]",
            "value": 2809.4,
            "range": "± 10.4",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=ENTITY_MOVE]",
            "value": 140.9,
            "range": "± 0.9",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=MIXED]",
            "value": 417.9,
            "range": "± 2",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=CHUNK]",
            "value": 104692.7,
            "range": "± 65",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=ENTITY_MOVE]",
            "value": 723.7,
            "range": "± 4.8",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=MIXED]",
            "value": 7526.7,
            "range": "± 59.1",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "PacketIdPeekBenchmark.peek[workload=CHUNK]",
            "value": 1844.5,
            "range": "± 9.8",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "PacketIdPeekBenchmark.peek[workload=MIXED]",
            "value": 1426.4,
            "range": "± 7.8",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "63867369+NeosiaNexus@users.noreply.github.com",
            "name": "NeosiaNexus",
            "username": "NeosiaNexus"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "ed9e29b1a72ae745aac9901dcf21b861a8523a41",
          "message": "test(protocol): measure test strength with mutation testing and kill surviving mutants (#95)\n\nAdds PIT mutation testing to the protocol and proxy modules, with a ratchet on each module's mutation score that every pull request is held to. New tests kill the surviving mutants that mattered in the codecs Warp relies on, raising the protocol module's score from 81.1% to 94.6%, and fix a proxy boss bar test that passed by luck.",
          "timestamp": "2026-10-07T04:21:36+02:00",
          "tree_id": "1870346d7d1e657ca05c2d21b6a8b39ee9c7b6fc",
          "url": "https://github.com/NeosiaNexus/Warp/commit/ed9e29b1a72ae745aac9901dcf21b861a8523a41"
        },
        "date": 1791340731849,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=CHUNK]",
            "value": 2843.7,
            "range": "± 10.5",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=ENTITY_MOVE]",
            "value": 141.5,
            "range": "± 0.6",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=MIXED]",
            "value": 420.6,
            "range": "± 2",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=CHUNK]",
            "value": 104830,
            "range": "± 101.1",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=ENTITY_MOVE]",
            "value": 722.7,
            "range": "± 3.8",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=MIXED]",
            "value": 7502.2,
            "range": "± 9.8",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "PacketIdPeekBenchmark.peek[workload=CHUNK]",
            "value": 1831,
            "range": "± 11.1",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "PacketIdPeekBenchmark.peek[workload=MIXED]",
            "value": 1426.6,
            "range": "± 5.5",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          }
        ]
      },
      {
        "commit": {
          "author": {
            "email": "41898282+github-actions[bot]@users.noreply.github.com",
            "name": "github-actions[bot]",
            "username": "github-actions[bot]"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "e699d6e0a586b7e9e7e8de10b7d9a25223ae21d3",
          "message": "chore(main): release 0.1.0-beta.6 (#24)\n\n:robot: I have created a release *beep* *boop*\n---\n\n\n##\n[0.1.0-beta.6](https://github.com/NeosiaNexus/Warp/compare/v0.1.0-beta.5...v0.1.0-beta.6)\n(2026-11-07)\n\n\n### ⚠ BREAKING CHANGES\n\n* **api:** deny the first server with a reason, upsert servers, release\nplugin registrations, time out pings\n([#148](https://github.com/NeosiaNexus/Warp/issues/148))\n* Warp now requires a Java 25 runtime.\n\n### Features\n\n* **api:** complete the Adventure audience: sounds, boss bars, resource\npacks on every version\n([#142](https://github.com/NeosiaNexus/Warp/issues/142))\n([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))\n* **api:** configure the server list entry and enforce the player limit\n([#134](https://github.com/NeosiaNexus/Warp/issues/134))\n([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))\n* **api:** deliver events with deterministic order and per-player\nsequencing ([#120](https://github.com/NeosiaNexus/Warp/issues/120))\n([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))\n* **api:** deny the first server with a reason, upsert servers, release\nplugin registrations, time out pings\n([#148](https://github.com/NeosiaNexus/Warp/issues/148))\n([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))\n* **api:** expose players, backend servers and their connection events\n([#126](https://github.com/NeosiaNexus/Warp/issues/126))\n([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))\n* **api:** let permission plugins decide what players may do\n([#130](https://github.com/NeosiaNexus/Warp/issues/130))\n([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))\n* **api:** let plugins own plugin message channels\n([#127](https://github.com/NeosiaNexus/Warp/issues/127))\n([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))\n* **api:** load plugins in isolated class loaders, dependencies first\n([#119](https://github.com/NeosiaNexus/Warp/issues/119))\n([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))\n* **api:** make players and command sources Adventure audiences\n([#137](https://github.com/NeosiaNexus/Warp/issues/137))\n([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))\n* **api:** refresh a player's command list when its permissions or the\ncommands change ([#165](https://github.com/NeosiaNexus/Warp/issues/165))\n([ca6dc6c](https://github.com/NeosiaNexus/Warp/commit/ca6dc6c6688324c62dde6e11f6fc463d33d0b5a4))\n* **api:** run proxy commands with Brigadier, merged into every server's\nlist ([#128](https://github.com/NeosiaNexus/Warp/issues/128))\n([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))\n* **protocol:** decode, edit and re-encode the command tree\n([#125](https://github.com/NeosiaNexus/Warp/issues/125))\n([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))\n* **protocol:** forward compressed frames without recompressing them\n([#31](https://github.com/NeosiaNexus/Warp/issues/31))\n([975d681](https://github.com/NeosiaNexus/Warp/commit/975d681dbeaf400a47ff2bd19973204688bc0b9f))\n* **protocol:** send Adventure components to every client version\n([#129](https://github.com/NeosiaNexus/Warp/issues/129))\n([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))\n* **protocol:** support Minecraft 26.2 and 26.3\n([#96](https://github.com/NeosiaNexus/Warp/issues/96))\n([e907463](https://github.com/NeosiaNexus/Warp/commit/e9074635dda1dd8eaad8ca3c8d8c50bb41279fcd))\n* **proxy:** a console with colours, startup and shutdown summaries, an\ninteractive prompt and a status line\n([#167](https://github.com/NeosiaNexus/Warp/issues/167))\n([ca6dc6c](https://github.com/NeosiaNexus/Warp/commit/ca6dc6c6688324c62dde6e11f6fc463d33d0b5a4))\n* **proxy:** a secure remote console: live logs over SSE, scoped\ncommands, and a client in the jar\n([#169](https://github.com/NeosiaNexus/Warp/issues/169))\n([e746adf](https://github.com/NeosiaNexus/Warp/commit/e746adf7bb582dbd7c9765455b2e23cb1a8b2563))\n* **proxy:** accept the PROXY protocol from trusted load balancers\n([#122](https://github.com/NeosiaNexus/Warp/issues/122))\n([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))\n* **proxy:** accept transferred players and carry cookies across\ntransfers ([#121](https://github.com/NeosiaNexus/Warp/issues/121))\n([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))\n* **proxy:** add client settings cache, fallback server, and switch\nsafety ([#26](https://github.com/NeosiaNexus/Warp/issues/26))\n([1daba96](https://github.com/NeosiaNexus/Warp/commit/1daba968da0458e522c332e2b5dd9c614b236ef0))\n* **proxy:** add server switching with blind-forwarded CONFIG phase\n([#25](https://github.com/NeosiaNexus/Warp/issues/25))\n([7ffe443](https://github.com/NeosiaNexus/Warp/commit/7ffe443ddb829d42d35e87c008f9a27fb984d5de))\n* **proxy:** administration commands /warp, /glist, /send, /find, /alert\nand /shutdown ([#163](https://github.com/NeosiaNexus/Warp/issues/163))\n([ca6dc6c](https://github.com/NeosiaNexus/Warp/commit/ca6dc6c6688324c62dde6e11f6fc463d33d0b5a4))\n* **proxy:** allow overriding the session server with\nmojang.sessionserver\n([#35](https://github.com/NeosiaNexus/Warp/issues/35))\n([1977f6a](https://github.com/NeosiaNexus/Warp/commit/1977f6a02586f1cf49f4bbf8748bcce5251df27f))\n* **proxy:** answer the BungeeCord plugin messaging channel\n([#150](https://github.com/NeosiaNexus/Warp/issues/150))\n([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))\n* **proxy:** bound connections before login with frame limits, deadlines\nand counted refusals\n([#145](https://github.com/NeosiaNexus/Warp/issues/145))\n([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))\n* **proxy:** compress with a bundled libdeflate through the FFM API, one\ncontext per event loop\n([#143](https://github.com/NeosiaNexus/Warp/issues/143))\n([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))\n* **proxy:** configure the player limit, timeouts and logging, with JSON\nlogs and plugin log sinks\n([#149](https://github.com/NeosiaNexus/Warp/issues/149))\n([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))\n* **proxy:** console themes, clickable links, stable error codes, /log\nand the startup's breakdown\n([#170](https://github.com/NeosiaNexus/Warp/issues/170))\n([e746adf](https://github.com/NeosiaNexus/Warp/commit/e746adf7bb582dbd7c9765455b2e23cb1a8b2563))\n* **proxy:** detect bot attacks, remember trusted identities and lock\ndown, with an authentication lane\n([#151](https://github.com/NeosiaNexus/Warp/issues/151))\n([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))\n* **proxy:** drain gracefully and answer Kubernetes and load balancer\nhealth checks ([#135](https://github.com/NeosiaNexus/Warp/issues/135))\n([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))\n* **proxy:** player messages in each player's language, and\nconfiguration and startup errors that say how to fix them\n([#166](https://github.com/NeosiaNexus/Warp/issues/166))\n([ca6dc6c](https://github.com/NeosiaNexus/Warp/commit/ca6dc6c6688324c62dde6e11f6fc463d33d0b5a4))\n* **proxy:** throttle connections and logins per address\n([#123](https://github.com/NeosiaNexus/Warp/issues/123))\n([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))\n* **proxy:** track connected players and register servers at runtime\n([#118](https://github.com/NeosiaNexus/Warp/issues/118))\n([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))\n* **via:** run ViaVersion on Warp through a sanctioned network pipeline\nAPI ([#146](https://github.com/NeosiaNexus/Warp/issues/146))\n([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))\n\n\n### Bug Fixes\n\n* **deps:** bump com.diffplug.spotless from 7.0.4 to 8.10.3\n([#54](https://github.com/NeosiaNexus/Warp/issues/54))\n([dd95cc5](https://github.com/NeosiaNexus/Warp/commit/dd95cc5593d795f4c38f58a4a01d9a360199ef72))\n* **deps:** bump com.github.ben-manes.caffeine:caffeine from 3.2.3 to\n3.3.0 ([#56](https://github.com/NeosiaNexus/Warp/issues/56))\n([41e3d64](https://github.com/NeosiaNexus/Warp/commit/41e3d646420a0968e880ad9251461ca562a69fb5))\n* **deps:** bump com.google.code.gson:gson from 2.13.2 to 2.14.0\n([#58](https://github.com/NeosiaNexus/Warp/issues/58))\n([aba7d00](https://github.com/NeosiaNexus/Warp/commit/aba7d0059aed7d4e3079036f7a88c237876eead5))\n* **deps:** bump com.gradleup.shadow from 9.0.0 to 9.6.1\n([#55](https://github.com/NeosiaNexus/Warp/issues/55))\n([3ee6ccf](https://github.com/NeosiaNexus/Warp/commit/3ee6ccf3a6f4f4ee3b54a8323faf70915aa45a51))\n* **deps:** bump com.puppycrawl.tools:checkstyle from 13.11.0 to 14.3.0\n([#108](https://github.com/NeosiaNexus/Warp/issues/108))\n([e746adf](https://github.com/NeosiaNexus/Warp/commit/e746adf7bb582dbd7c9765455b2e23cb1a8b2563))\n* **deps:** bump com.velocitypowered:velocity-native from 4.2.0 to 4.3.0\n([#116](https://github.com/NeosiaNexus/Warp/issues/116))\n([e746adf](https://github.com/NeosiaNexus/Warp/commit/e746adf7bb582dbd7c9765455b2e23cb1a8b2563))\n* **deps:** bump gradle-wrapper from 9.8.0 to 9.8.2\n([#113](https://github.com/NeosiaNexus/Warp/issues/113))\n([e746adf](https://github.com/NeosiaNexus/Warp/commit/e746adf7bb582dbd7c9765455b2e23cb1a8b2563))\n* **deps:** bump io.netty:netty-bom from 4.2.18.Final to 4.2.19.Final in\nthe netty group across 1 directory\n([#111](https://github.com/NeosiaNexus/Warp/issues/111))\n([e746adf](https://github.com/NeosiaNexus/Warp/commit/e746adf7bb582dbd7c9765455b2e23cb1a8b2563))\n* **deps:** bump net.kyori:adventure-bom from 4.26.1 to 5.2.0\n([#104](https://github.com/NeosiaNexus/Warp/issues/104))\n([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))\n* **deps:** bump net.ltgt.errorprone from 4.2.0 to 5.1.1\n([#57](https://github.com/NeosiaNexus/Warp/issues/57))\n([31878cf](https://github.com/NeosiaNexus/Warp/commit/31878cf48adb9e4a4dea2e940ccd1b20d1dbe2a1))\n* **deps:** bump org.gradle.toolchains.foojay-resolver-convention from\n0.9.0 to 1.0.0 ([#59](https://github.com/NeosiaNexus/Warp/issues/59))\n([a40e185](https://github.com/NeosiaNexus/Warp/commit/a40e1853c48932a33dccb457e6c87f3097af3b1f))\n* **deps:** bump org.jspecify:jspecify from 1.0.0 to 1.0.1\n([#105](https://github.com/NeosiaNexus/Warp/issues/105))\n([e746adf](https://github.com/NeosiaNexus/Warp/commit/e746adf7bb582dbd7c9765455b2e23cb1a8b2563))\n* **deps:** bump org.junit:junit-bom from 5.12.2 to 6.1.3\n([#106](https://github.com/NeosiaNexus/Warp/issues/106))\n([e746adf](https://github.com/NeosiaNexus/Warp/commit/e746adf7bb582dbd7c9765455b2e23cb1a8b2563))\n* **deps:** bump org.pitest:pitest-command-line from 1.30.0 to 1.30.1 in\nthe testing group across 1 directory\n([#115](https://github.com/NeosiaNexus/Warp/issues/115))\n([e746adf](https://github.com/NeosiaNexus/Warp/commit/e746adf7bb582dbd7c9765455b2e23cb1a8b2563))\n* **deps:** bump org.slf4j:slf4j-api from 2.0.17 to 2.0.20\n([#107](https://github.com/NeosiaNexus/Warp/issues/107))\n([e746adf](https://github.com/NeosiaNexus/Warp/commit/e746adf7bb582dbd7c9765455b2e23cb1a8b2563))\n* **deps:** bump the code-quality group across 1 directory with 2\nupdates ([#53](https://github.com/NeosiaNexus/Warp/issues/53))\n([6a4a598](https://github.com/NeosiaNexus/Warp/commit/6a4a598772a20d07fd40b73e6d612ed3da8ac428))\n* **deps:** bump the code-quality group across 1 directory with 4\nupdates ([#160](https://github.com/NeosiaNexus/Warp/issues/160))\n([e746adf](https://github.com/NeosiaNexus/Warp/commit/e746adf7bb582dbd7c9765455b2e23cb1a8b2563))\n* **deps:** bump the log4j group across 1 directory with 6 updates\n([#51](https://github.com/NeosiaNexus/Warp/issues/51))\n([ee49086](https://github.com/NeosiaNexus/Warp/commit/ee490861834b0905ae58e3865be57e8175108616))\n* **deps:** bump the netty group across 1 directory with 2 updates\n([#50](https://github.com/NeosiaNexus/Warp/issues/50))\n([2576ae6](https://github.com/NeosiaNexus/Warp/commit/2576ae6ac2fde107c569525c41cd71bdd805a31e))\n* **deps:** bump the testing group across 1 directory with 2 updates\n([#52](https://github.com/NeosiaNexus/Warp/issues/52))\n([fe73644](https://github.com/NeosiaNexus/Warp/commit/fe7364484e916332cf8ba7359d58a5273800fbe4))\n* **deps:** raise Guava and build tool dependencies to patched versions\n([#63](https://github.com/NeosiaNexus/Warp/issues/63))\n([f32df0b](https://github.com/NeosiaNexus/Warp/commit/f32df0b92c565c82ca17816a1ebec1f0016c186b))\n* **deps:** resolve the patched Guava in every configuration, build\ntools included ([#153](https://github.com/NeosiaNexus/Warp/issues/153))\n([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))\n* **protocol:** audit packet ids and formats for 1.21.5 to 26.1\n([#78](https://github.com/NeosiaNexus/Warp/issues/78))\n([d8e00f6](https://github.com/NeosiaNexus/Warp/commit/d8e00f6f681e1ef0709bdc6cf03d9b1593f1a5e9))\n* **protocol:** decode and encode client settings per version\n([#66](https://github.com/NeosiaNexus/Warp/issues/66))\n([0289fbc](https://github.com/NeosiaNexus/Warp/commit/0289fbc26bf02c76a77d15e8078fc7a99951b6f3))\n* **protocol:** decode the unsigned chat command on 1.20.5 to 1.21.1\n([#68](https://github.com/NeosiaNexus/Warp/issues/68))\n([36cdda6](https://github.com/NeosiaNexus/Warp/commit/36cdda6107c786354ed9baf919be22892573e17d))\n* **protocol:** encode packets registered as encode-only\n([#32](https://github.com/NeosiaNexus/Warp/issues/32))\n([fd366e2](https://github.com/NeosiaNexus/Warp/commit/fd366e239d09ee353344406954ed95300d9adea4))\n* **protocol:** pass the chat acknowledgements of a /server Warp answers\non to the backend ([#85](https://github.com/NeosiaNexus/Warp/issues/85))\n([68be368](https://github.com/NeosiaNexus/Warp/commit/68be36866356f4c8276b5b46cd43af1996d4e220))\n* **protocol:** reject negative property and pack counts\n([833b53f](https://github.com/NeosiaNexus/Warp/commit/833b53f982106b9ad796e96d9633e0aea0d1dbcf))\n* **protocol:** remove the previous server's tab list names from 1.7\nclients on a switch\n([#89](https://github.com/NeosiaNexus/Warp/issues/89))\n([1722c1a](https://github.com/NeosiaNexus/Warp/commit/1722c1a6640b14e19cad5a59ba89fb82b4b175a8))\n* **protocol:** send and read the login UUID as a string before 1.16\n([#38](https://github.com/NeosiaNexus/Warp/issues/38))\n([a7fdb70](https://github.com/NeosiaNexus/Warp/commit/a7fdb7070131676fd3ea54bdecf92d9a1374eaa7))\n* **protocol:** send proxy messages and handle /server on clients before\n1.19.3 ([#70](https://github.com/NeosiaNexus/Warp/issues/70))\n([abaf050](https://github.com/NeosiaNexus/Warp/commit/abaf05007d1bdcdcfc7460c024377f0c0665eca8))\n* **protocol:** stop decoding a read once a frame of it closed the\nconnection\n([833b53f](https://github.com/NeosiaNexus/Warp/commit/833b53f982106b9ad796e96d9633e0aea0d1dbcf))\n* **protocol:** write and read the length of 1.7 plugin message payloads\n([#93](https://github.com/NeosiaNexus/Warp/issues/93))\n([8489ba5](https://github.com/NeosiaNexus/Warp/commit/8489ba5112e17846555cc98275b1b9de5b299cfd))\n* **protocol:** write the 1.19 to 1.19.2 signature flag in login start\n([#69](https://github.com/NeosiaNexus/Warp/issues/69))\n([b2331ba](https://github.com/NeosiaNexus/Warp/commit/b2331ba1e92043233a37da11592bce1b4e12c1e7))\n* **proxy:** advertise the client's own protocol in the server list ping\n([#71](https://github.com/NeosiaNexus/Warp/issues/71))\n([aae25f5](https://github.com/NeosiaNexus/Warp/commit/aae25f503f5fed829d2881f9b17849a5a5801177))\n* **proxy:** fall back to another server when the first one refuses a\njoining player ([#33](https://github.com/NeosiaNexus/Warp/issues/33))\n([1ace2b8](https://github.com/NeosiaNexus/Warp/commit/1ace2b8f9ae0e23ec1c23c793be86f4537ae8088))\n* **proxy:** give backend connections their own read timeout,\ntimeouts.backend-read (30s)\n([#159](https://github.com/NeosiaNexus/Warp/issues/159))\n([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))\n* **proxy:** give every invalid encryption response the same reply\n([#162](https://github.com/NeosiaNexus/Warp/issues/162))\n([4bd75ff](https://github.com/NeosiaNexus/Warp/commit/4bd75ffb6d5f93374b615a72db37480163e91600))\n* **proxy:** keep players alive while they are held between servers\n([#141](https://github.com/NeosiaNexus/Warp/issues/141))\n([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))\n* **proxy:** keep players on their server when a switch fails\n([#124](https://github.com/NeosiaNexus/Warp/issues/124))\n([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))\n* **proxy:** let newer players join backends before 1.20.2 through the\nvia plugin ([#174](https://github.com/NeosiaNexus/Warp/issues/174))\n([e746adf](https://github.com/NeosiaNexus/Warp/commit/e746adf7bb582dbd7c9765455b2e23cb1a8b2563))\n* **proxy:** log a read time-out at INFO, with what the connection was\nwaiting for ([#100](https://github.com/NeosiaNexus/Warp/issues/100))\n([8e51996](https://github.com/NeosiaNexus/Warp/commit/8e519964b79e6a185d69691897aa0b0288163f1f))\n* **proxy:** log in 1.19 to 1.19.2 clients that sign the verify token\nwith their profile key\n([#83](https://github.com/NeosiaNexus/Warp/issues/83))\n([b9d3c9b](https://github.com/NeosiaNexus/Warp/commit/b9d3c9b5a1688559cb869e97a24225ca9752c41b))\n* **proxy:** never compress a 1.7 client's connection, Set Compression\nonly exists from 1.8\n([#88](https://github.com/NeosiaNexus/Warp/issues/88))\n([558cdde](https://github.com/NeosiaNexus/Warp/commit/558cdde71a5d85dc7f4327b7fda13931e12cc131))\n* **proxy:** prevent double fallback from handleDisconnect +\nchannelInactive ([#27](https://github.com/NeosiaNexus/Warp/issues/27))\n([a6d79e3](https://github.com/NeosiaNexus/Warp/commit/a6d79e38a15d4ffb80cab7bd65287e71fe8558d6))\n* **proxy:** refuse to share the port and stop logging lost connections\nas errors ([#34](https://github.com/NeosiaNexus/Warp/issues/34))\n([0ba5d1c](https://github.com/NeosiaNexus/Warp/commit/0ba5d1c6335650296302be14dbd7715807c5a96b))\n* **proxy:** register the BungeeCord channel while the server configures\nthe player ([#172](https://github.com/NeosiaNexus/Warp/issues/172))\n([e746adf](https://github.com/NeosiaNexus/Warp/commit/e746adf7bb582dbd7c9765455b2e23cb1a8b2563))\n* **proxy:** send disconnect reasons in the format of each state and\nversion ([#72](https://github.com/NeosiaNexus/Warp/issues/72))\n([dc1cfdc](https://github.com/NeosiaNexus/Warp/commit/dc1cfdcfcbe487916e8306e92146e398d08cf29b))\n* **proxy:** send keep-alive ids that fit the client's protocol version\n([#67](https://github.com/NeosiaNexus/Warp/issues/67))\n([a751088](https://github.com/NeosiaNexus/Warp/commit/a75108829a7d966e047ec72e8dc627b4d0e13830))\n* **proxy:** send readable login disconnect reasons and resolve code\nscanning findings ([#65](https://github.com/NeosiaNexus/Warp/issues/65))\n([1bc411f](https://github.com/NeosiaNexus/Warp/commit/1bc411f0ad7da9731104533b945c41886831b7c5))\n* **proxy:** separate handler deactivation from channel disconnect\n([#23](https://github.com/NeosiaNexus/Warp/issues/23))\n([f685eab](https://github.com/NeosiaNexus/Warp/commit/f685eabd064d18c8d108fa1716f568fc46823f84))\n* **proxy:** stop logging a stale read event on a connecting backend as\nan error ([#64](https://github.com/NeosiaNexus/Warp/issues/64))\n([1f9570b](https://github.com/NeosiaNexus/Warp/commit/1f9570b1b81781811789e5535135d182a78b24ab))\n* **proxy:** switch servers and fall back on clients older than 1.20.2\n([#73](https://github.com/NeosiaNexus/Warp/issues/73))\n([6581483](https://github.com/NeosiaNexus/Warp/commit/6581483518c8bf1d3dbe7fa8d4b0e37c5ece967d))\n\n\n### Performance\n\n* **bench:** guard allocation per packet in CI and track benchmark\ntimings ([#76](https://github.com/NeosiaNexus/Warp/issues/76))\n([ac92d01](https://github.com/NeosiaNexus/Warp/commit/ac92d014a624743a11551a1256678ae53edf5b8f))\n* **protocol:** encrypt and decrypt without allocating per frame\n([#152](https://github.com/NeosiaNexus/Warp/issues/152))\n([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))\n* **protocol:** watch tab list and boss bar packets in place instead of\nre-encoding them ([#91](https://github.com/NeosiaNexus/Warp/issues/91))\n([6bcc8ac](https://github.com/NeosiaNexus/Warp/commit/6bcc8ac49409b576f0640109bd22acc0748b262b))\n* **proxy:** serve each player's backend leg from its client event loop\n([#30](https://github.com/NeosiaNexus/Warp/issues/30))\n([878c2e0](https://github.com/NeosiaNexus/Warp/commit/878c2e0c48743eb19033617a619ef492cbe2c356))\n\n\n### Documentation\n\n* **project:** polish the public front page and community files\n([#39](https://github.com/NeosiaNexus/Warp/issues/39))\n([bd51a62](https://github.com/NeosiaNexus/Warp/commit/bd51a624c61c07d04bcf353d3edc14475c35c7bd))\n* **research:** catalogue the plugin API defects of other proxies\n([#133](https://github.com/NeosiaNexus/Warp/issues/133))\n([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))\n* **research:** correct citations that do not say what the documents\nclaimed ([#140](https://github.com/NeosiaNexus/Warp/issues/140))\n([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))\n* **research:** design a built-in anti-bot from Sonar, LimboFilter and\nthe proxies' trackers\n([#139](https://github.com/NeosiaNexus/Warp/issues/139))\n([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))\n\n\n### Build System\n\n* **api:** target Java 21 for plugins and number every non-release build\nas a snapshot ([#147](https://github.com/NeosiaNexus/Warp/issues/147))\n([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))\n* **project:** install the pre-commit hook from linked worktrees too\n([#131](https://github.com/NeosiaNexus/Warp/issues/131))\n([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))\n* **project:** publish the modules as warp-api, warp-protocol and so on\n([#138](https://github.com/NeosiaNexus/Warp/issues/138))\n([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))\n* **project:** upgrade Gradle from 8.12 to 9.8.0\n([#62](https://github.com/NeosiaNexus/Warp/issues/62))\n([295706c](https://github.com/NeosiaNexus/Warp/commit/295706cec9a9201303d63bada4256fed4591f09d))\n* require Java 25 LTS\n([#28](https://github.com/NeosiaNexus/Warp/issues/28))\n([a329739](https://github.com/NeosiaNexus/Warp/commit/a3297390667142147fd7534986f49efbddda853b))\n\n\n### CI\n\n* **deps:** bump the github-actions group across 1 directory with 12\nupdates ([#161](https://github.com/NeosiaNexus/Warp/issues/161))\n([e746adf](https://github.com/NeosiaNexus/Warp/commit/e746adf7bb582dbd7c9765455b2e23cb1a8b2563))\n* **deps:** submit the dependency graph of integration branches too\n([#154](https://github.com/NeosiaNexus/Warp/issues/154))\n([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))\n* **project:** allow error_prone_annotations in the licence policy\n([#60](https://github.com/NeosiaNexus/Warp/issues/60))\n([0365f34](https://github.com/NeosiaNexus/Warp/commit/0365f340589aaa9c16794f9a9e02d38ad15ac425))\n* **project:** attach signed provenance to releases\n([#61](https://github.com/NeosiaNexus/Warp/issues/61))\n([1bb628d](https://github.com/NeosiaNexus/Warp/commit/1bb628dff7c12c2a9a22c8e538d370d3325664a6))\n* **project:** harden workflows and report tests, coverage and workflow\nlint ([#36](https://github.com/NeosiaNexus/Warp/issues/36))\n([b497aec](https://github.com/NeosiaNexus/Warp/commit/b497aec3f55eaf78529f457eef1ef84eb84d909d))\n* **project:** mutate only the classes a pull request changes, every\nclass nightly ([#155](https://github.com/NeosiaNexus/Warp/issues/155))\n([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))\n* **project:** scan code, dependencies and workflows with GitHub's\nnative security tools\n([#40](https://github.com/NeosiaNexus/Warp/issues/40))\n([b5672fc](https://github.com/NeosiaNexus/Warp/commit/b5672fc2c557a371738c83ec2b7564d759365a36))\n\n\n### Tests\n\n* **bench:** add JMH suite with Velocity-native baselines\n([#29](https://github.com/NeosiaNexus/Warp/issues/29))\n([b334cf9](https://github.com/NeosiaNexus/Warp/commit/b334cf9622b38e9c92c55c98d84408bdd2e209c4))\n* **e2e:** cover forwarding, brand, chat and resource packs, and run the\nfull matrix on protocol changes\n([#82](https://github.com/NeosiaNexus/Warp/issues/82))\n([8bbd900](https://github.com/NeosiaNexus/Warp/commit/8bbd900ca3aa82adf56e400ca8468e80debdc3a6))\n* **e2e:** find the snapshot jars Gradle builds instead of version.txt's\nnames ([#164](https://github.com/NeosiaNexus/Warp/issues/164))\n([ca6dc6c](https://github.com/NeosiaNexus/Warp/commit/ca6dc6c6688324c62dde6e11f6fc463d33d0b5a4))\n* **e2e:** keep ViaProxy from compressing towards the bots, which stalls\nits logins ([#109](https://github.com/NeosiaNexus/Warp/issues/109))\n([ba8fea8](https://github.com/NeosiaNexus/Warp/commit/ba8fea8f86ab305c357cf4f553c29aebe5dc22ee))\n* **e2e:** promote the versions fixed by the protocol work\n([#74](https://github.com/NeosiaNexus/Warp/issues/74))\n([c5fcb68](https://github.com/NeosiaNexus/Warp/commit/c5fcb688813896741dc948f1b2369822b9276d65))\n* **e2e:** require the newest Minecraft versions on every pull request\n([#101](https://github.com/NeosiaNexus/Warp/issues/101))\n([022811b](https://github.com/NeosiaNexus/Warp/commit/022811b5066f98933c4f394b2962e4760969ece9))\n* **e2e:** run backends from 1.12 on Netty's epoll transport, whose NIO\nstops reading players\n([#157](https://github.com/NeosiaNexus/Warp/issues/157))\n([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))\n* **e2e:** run Minecraft 1.7 end to end, and check the tab list after a\nswitch on every version\n([#98](https://github.com/NeosiaNexus/Warp/issues/98))\n([062b323](https://github.com/NeosiaNexus/Warp/commit/062b3233de127e59ce29395e2056bbe51603ccac))\n* **e2e:** run real clients through Warp against every Minecraft version\n([#37](https://github.com/NeosiaNexus/Warp/issues/37))\n([67c0ba6](https://github.com/NeosiaNexus/Warp/commit/67c0ba618bf0e858ac07a78518c597efc596dcc6))\n* **e2e:** settle a process's exit once its last output is read\n([#144](https://github.com/NeosiaNexus/Warp/issues/144))\n([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))\n* **e2e:** soak Warp nightly under load and a degraded network\n([#77](https://github.com/NeosiaNexus/Warp/issues/77))\n([efc1b19](https://github.com/NeosiaNexus/Warp/commit/efc1b19b308114d68c8153a5ec4eb683fc54ef2c))\n* **e2e:** tell a bot why it was refused when the refusal beats\nmineflayer's plugins\n([#156](https://github.com/NeosiaNexus/Warp/issues/156))\n([be3c65f](https://github.com/NeosiaNexus/Warp/commit/be3c65f1a23be5b5e2ead368626c4b6634703e1a))\n* **protocol:** fuzz the protocol decoders with Jazzer\n([833b53f](https://github.com/NeosiaNexus/Warp/commit/833b53f982106b9ad796e96d9633e0aea0d1dbcf))\n* **protocol:** measure test strength with mutation testing and kill\nsurviving mutants ([#95](https://github.com/NeosiaNexus/Warp/issues/95))\n([ed9e29b](https://github.com/NeosiaNexus/Warp/commit/ed9e29b1a72ae745aac9901dcf21b861a8523a41))\n* **protocol:** verify packet ids against Mojang's generated reports\n([#75](https://github.com/NeosiaNexus/Warp/issues/75))\n([7921d79](https://github.com/NeosiaNexus/Warp/commit/7921d796bf5cddadaf13a241766b0d1aa5508dac))\n* **proxy:** make BackendPingerTest deterministic and stop the pinger\nreading before its handler is in place\n([#173](https://github.com/NeosiaNexus/Warp/issues/173))\n([e746adf](https://github.com/NeosiaNexus/Warp/commit/e746adf7bb582dbd7c9765455b2e23cb1a8b2563))\n\n---\nThis PR was generated with [Release\nPlease](https://github.com/googleapis/release-please). See\n[documentation](https://github.com/googleapis/release-please#release-please).\n\nCo-authored-by: github-actions[bot] <41898282+github-actions[bot]@users.noreply.github.com>",
          "timestamp": "2026-11-07T17:15:16+01:00",
          "tree_id": "baae593c4f81c805c5a2d9f776a85559067bc9ac",
          "url": "https://github.com/NeosiaNexus/Warp/commit/e699d6e0a586b7e9e7e8de10b7d9a25223ae21d3"
        },
        "date": 1794069977065,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "CipherBenchmark.decrypt[buffer=DIRECT,frameSize=1024]",
            "value": 26300.8,
            "range": "± 29.1",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.5, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "CipherBenchmark.decrypt[buffer=HEAP,frameSize=1024]",
            "value": 26247.7,
            "range": "± 6.7",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.5, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "CipherBenchmark.encrypt[buffer=DIRECT,frameSize=1024]",
            "value": 26440.2,
            "range": "± 10.6",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.5, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "CipherBenchmark.encrypt[buffer=HEAP,frameSize=1024]",
            "value": 26430.5,
            "range": "± 10.1",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.5, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "CodecCostBenchmark.deflate[connections=1,implementation=WARP_NATIVE,level=6,workload=CHUNK]",
            "value": 311053.9,
            "range": "± 211.8",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.5, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "CodecCostBenchmark.deflate[connections=1,implementation=WARP_NATIVE,level=6,workload=MIXED]",
            "value": 12639.3,
            "range": "± 7.3",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.5, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "CodecCostBenchmark.deflate[connections=1,implementation=WARP_NATIVE,level=6,workload=SMALL]",
            "value": 16158.3,
            "range": "± 16.6",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.5, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "CodecCostBenchmark.inflate[connections=1,implementation=WARP_NATIVE,level=6,workload=CHUNK]",
            "value": 22104.9,
            "range": "± 35.7",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.5, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "CodecCostBenchmark.inflate[connections=1,implementation=WARP_NATIVE,level=6,workload=MIXED]",
            "value": 1282.2,
            "range": "± 3.7",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.5, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "CodecCostBenchmark.inflate[connections=1,implementation=WARP_NATIVE,level=6,workload=SMALL]",
            "value": 5178.1,
            "range": "± 38.0",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.5, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=CHUNK]",
            "value": 2684.7,
            "range": "± 10.6",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.5, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=ENTITY_MOVE]",
            "value": 144.4,
            "range": "± 0.6",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.5, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=MIXED]",
            "value": 416.4,
            "range": "± 2.1",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.5, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=CHUNK]",
            "value": 103899.8,
            "range": "± 39.4",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.5, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=ENTITY_MOVE]",
            "value": 685.3,
            "range": "± 15",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.5, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=MIXED]",
            "value": 7458.1,
            "range": "± 32.5",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.5, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "PacketIdPeekBenchmark.peek[workload=CHUNK]",
            "value": 1938.7,
            "range": "± 13.7",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.5, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "PacketIdPeekBenchmark.peek[workload=MIXED]",
            "value": 1431,
            "range": "± 4.3",
            "unit": "ns/packet",
            "extra": "AMD EPYC 7763 64-Core Processor (4 CPUs)\nJDK 25.0.5, 3 forks × 10 iterations of 2 s"
          }
        ]
      }
    ],
    "Hot path on Intel(R) Xeon(R) Platinum 8370C CPU @ 2.80GHz (4 CPUs)": [
      {
        "commit": {
          "author": {
            "email": "63867369+NeosiaNexus@users.noreply.github.com",
            "name": "NeosiaNexus",
            "username": "NeosiaNexus"
          },
          "committer": {
            "email": "noreply@github.com",
            "name": "GitHub",
            "username": "web-flow"
          },
          "distinct": true,
          "id": "ba8fea8f86ab305c357cf4f553c29aebe5dc22ee",
          "message": "test(e2e): keep ViaProxy from compressing towards the bots, which stalls its logins (#109)\n\nViaProxy 3.4.14 restores the auto-read flag of its connection to the server from the client's event loop once its own Set Compression is written, while the server's event loop saves and clears that flag again for Login Success; when the two interleave, the connection is never read again and the bot waits until Warp times it out. The harness now starts ViaProxy with --compression-threshold -1, so that path never runs; Warp's compression towards ViaProxy is unchanged. Fixes #102.",
          "timestamp": "2026-10-07T03:53:21+02:00",
          "tree_id": "078002376cbb21eefaea0a8c98a3375db039a903",
          "url": "https://github.com/NeosiaNexus/Warp/commit/ba8fea8f86ab305c357cf4f553c29aebe5dc22ee"
        },
        "date": 1791338879688,
        "tool": "customSmallerIsBetter",
        "benches": [
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=CHUNK]",
            "value": 2864.7,
            "range": "± 41.8",
            "unit": "ns/packet",
            "extra": "Intel(R) Xeon(R) Platinum 8370C CPU @ 2.80GHz (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=ENTITY_MOVE]",
            "value": 172.7,
            "range": "± 0.8",
            "unit": "ns/packet",
            "extra": "Intel(R) Xeon(R) Platinum 8370C CPU @ 2.80GHz (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=false,mode=PASSTHROUGH,workload=MIXED]",
            "value": 449.9,
            "range": "± 10.0",
            "unit": "ns/packet",
            "extra": "Intel(R) Xeon(R) Platinum 8370C CPU @ 2.80GHz (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=CHUNK]",
            "value": 77218.1,
            "range": "± 87.4",
            "unit": "ns/packet",
            "extra": "Intel(R) Xeon(R) Platinum 8370C CPU @ 2.80GHz (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=ENTITY_MOVE]",
            "value": 640,
            "range": "± 2.1",
            "unit": "ns/packet",
            "extra": "Intel(R) Xeon(R) Platinum 8370C CPU @ 2.80GHz (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "ForwardingPathBenchmark.relayClientbound[encrypted=true,mode=PASSTHROUGH,workload=MIXED]",
            "value": 5610.6,
            "range": "± 19.4",
            "unit": "ns/packet",
            "extra": "Intel(R) Xeon(R) Platinum 8370C CPU @ 2.80GHz (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "PacketIdPeekBenchmark.peek[workload=CHUNK]",
            "value": 1638.7,
            "range": "± 2.0",
            "unit": "ns/packet",
            "extra": "Intel(R) Xeon(R) Platinum 8370C CPU @ 2.80GHz (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          },
          {
            "name": "PacketIdPeekBenchmark.peek[workload=MIXED]",
            "value": 1323.1,
            "range": "± 4.8",
            "unit": "ns/packet",
            "extra": "Intel(R) Xeon(R) Platinum 8370C CPU @ 2.80GHz (4 CPUs)\nJDK 25.0.4.1, 3 forks × 10 iterations of 2 s"
          }
        ]
      }
    ]
  }
}