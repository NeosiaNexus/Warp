// Scenario sizing. The full profile is the credible macro benchmark (many bots, minute-long windows,
// three repetitions). The smoke profile runs the identical code in about two minutes with a handful
// of bots, so the harness is exercised end to end in CI and never rots. Durations are milliseconds.
// Backend JVM heap per Paper server. Smaller in smoke so four backends plus the proxy fit on a
// modest or shared machine; the full profile gives each backend room so it never becomes the
// bottleneck.
export const BACKEND_HEAP_MB = { full: 2048, smoke: 1024 };

export const FULL = {
  idle: { levels: [500, 2000, 5000], warmupMs: 60_000, measureMs: 60_000, reps: 3, ratePerSec: 200 },
  traffic: { bots: 300, warmupMs: 60_000, measureMs: 120_000, reps: 3, ratePerSec: 50 },
  latency: { samples: 300, intervalMs: 200, backgroundBots: 100, ratePerSec: 50 },
  switch: { bots: 200, durationMs: 120_000, ratePerSec: 50 },
  storm: { rates: [1000, 5000, 10000], durationMs: 30_000 },
};

export const SMOKE = {
  idle: { levels: [50], warmupMs: 4_000, measureMs: 8_000, reps: 1, ratePerSec: 100 },
  traffic: { bots: 10, warmupMs: 4_000, measureMs: 12_000, reps: 1, ratePerSec: 20 },
  latency: { samples: 20, intervalMs: 200, backgroundBots: 5, ratePerSec: 20 },
  switch: { bots: 5, durationMs: 15_000, ratePerSec: 20 },
  storm: { rates: [500], durationMs: 8_000 },
};

export const ALL_SCENARIOS = ['idle', 'traffic', 'latency', 'switch', 'storm'];
