window.BENCHMARK_DATA = {
  "lastUpdate": 1791303323629,
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
      }
    ]
  }
}