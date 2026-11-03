window.BENCHMARK_DATA = {
  "lastUpdate": 1793702180875,
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