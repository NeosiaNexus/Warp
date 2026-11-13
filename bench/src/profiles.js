// Scenario sizing. The full profile is the credible macro benchmark (thousands of idle connections,
// hundreds of movers, minute-long windows, three repetitions). The smoke profile runs the identical
// code in a couple of minutes with a handful of bots, so the harness is exercised end to end in CI
// and never rots. Durations are milliseconds. Every size here is a load the backends and the load
// generators must carry without being the limit: the validity gate (validity.js) says so when they
// do not, and the report then prints INVALID instead of the figure.

// Backend JVM heap per Paper server. Smaller in smoke so four backends plus the proxy fit on a
// modest or shared machine; the full profile gives each backend room so it never becomes the
// bottleneck.
export const BACKEND_HEAP_MB = { full: 4096, smoke: 1024 };

export const FULL = {
  // Raw idle clients against the stub backend. Idle connections exchange a keep-alive every 15 s,
  // so a 60 s window holds four of them per connection.
  idle: { levels: [1000, 5000, 10000], warmupMs: 30_000, measureMs: 60_000, reps: 3, ratePerSec: 200 },
  // Raw movers on one Paper backend, each streaming chunks as it walks.
  traffic: { bots: 300, warmupMs: 60_000, measureMs: 120_000, reps: 3, ratePerSec: 20 },
  // 2000 pings at 50 per second, with a light background of movers on the same backend.
  latency: { samples: 2000, warmup: 500, intervalMs: 20, timeoutMs: 2_000, backgroundBots: 50, ratePerSec: 10 },
  // Mineflayer bots, each switching backend then resting: the pause sets the offered switch rate.
  switch: { bots: 60, durationMs: 120_000, pauseMs: 3_000, ratePerSec: 5 },
  storm: { rates: [1000, 5000, 10000], durationMs: 30_000 },
};

export const SMOKE = {
  idle: { levels: [50, 100], warmupMs: 3_000, measureMs: 8_000, reps: 1, ratePerSec: 100 },
  traffic: { bots: 10, warmupMs: 4_000, measureMs: 12_000, reps: 1, ratePerSec: 10 },
  latency: { samples: 100, warmup: 20, intervalMs: 20, timeoutMs: 2_000, backgroundBots: 5, ratePerSec: 10 },
  switch: { bots: 4, durationMs: 15_000, pauseMs: 1_000, ratePerSec: 4 },
  storm: { rates: [500], durationMs: 8_000 },
};

export const ALL_SCENARIOS = ['idle', 'traffic', 'latency', 'switch', 'storm'];

/**
 * Applies `--set scenario.key=value` overrides to a profile (a copy is returned). A value with
 * commas becomes a list of numbers; anything else must be a number.
 * @param {object} profile FULL or SMOKE
 * @param {string[]} overrides e.g. ["idle.levels=1000,2000", "traffic.bots=150"]
 */
export function applyOverrides(profile, overrides) {
  const copy = Object.fromEntries(Object.entries(profile).map(([k, v]) => [k, { ...v }]));
  for (const item of overrides) {
    const m = /^(\w+)\.(\w+)=([\d.,]+)$/.exec(item);
    if (!m || !copy[m[1]] || !(m[2] in copy[m[1]])) throw new Error(`invalid --set: ${item}`);
    const numbers = m[3].split(',').map(Number);
    if (numbers.some(Number.isNaN)) throw new Error(`invalid --set: ${item}`);
    copy[m[1]][m[2]] = Array.isArray(copy[m[1]][m[2]]) ? numbers : numbers[0];
  }
  return copy;
}
