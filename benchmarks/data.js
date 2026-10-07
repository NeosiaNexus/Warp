window.BENCHMARK_DATA = {
  "lastUpdate": 1791331693690,
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
      }
    ]
  }
}