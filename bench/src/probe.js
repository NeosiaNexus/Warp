// The latency probe's clock and bookkeeping, with no bot in sight so the unit tests can drive it with
// a fake round. The probe has three rules. It waits for the join burst to end before it times
// anything: a freshly spawned player is still receiving its chunks, and a round timed inside that
// burst measures the backend's world streaming, not the proxy. It then discards a warm-up of rounds,
// so the proxy's own hot path (JIT for a JVM, connection caches for any runtime) is warm before the
// first counted sample; the schedule is re-anchored when the counted rounds begin, so a stall during
// warm-up, which intended-time accounting would otherwise charge to every following sample, cannot
// colour the window (a stall inside the window is real and is charged). Finally, a round that times out is not dropped: it is
// counted at the timeout, the worst case it was known to be at least as bad as, and reported as a
// timeout, so the tail cannot vanish from the percentiles.

/**
 * Waits until `read()` has not changed for `quietMs`, or `maxMs` has passed.
 * @param {() => number} read a monotonic counter (chunks received)
 * @param {{quietMs: number, maxMs: number, sleep: (ms: number) => Promise<void>, now?: () => number}} options
 * @returns {Promise<boolean>} true if the counter settled, false on the deadline
 */
export async function settle(read, { quietMs, maxMs, sleep, now = Date.now }) {
  const deadline = now() + maxMs;
  let last = read();
  let since = now();
  while (now() < deadline) {
    await sleep(Math.min(100, quietMs));
    const current = read();
    if (current !== last) {
      last = current;
      since = now();
    } else if (now() - since >= quietMs) {
      return true;
    }
  }
  return false;
}

/**
 * Runs `warmup + samples` rounds at `intervalMs`, timing each from its send time, or from its
 * intended start when the loop is behind schedule (the coordinated-omission guard), discarding the warm-up rounds, and counting a timed-out or failed
 * round at `timeoutMs`.
 * @param {object} options
 * @param {() => Promise<unknown>} options.round one request-response
 * @param {number} options.samples counted rounds
 * @param {number} options.warmup rounds run and discarded first
 * @param {number} options.intervalMs cadence
 * @param {number} options.timeoutMs a round slower than this counts as this
 * @param {(ms: number) => Promise<void>} options.sleep
 * @param {() => number} [options.now]
 * @returns {Promise<{latencies: number[], timeouts: number, warmup: number}>}
 */
export async function probeRounds({ round, samples, warmup, intervalMs, timeoutMs, sleep, now = Date.now }) {
  const latencies = [];
  let timeouts = 0;
  const run = async (count, counted) => {
    const start = now();
    for (let i = 0; i < count; i++) {
      const intended = start + i * intervalMs;
      const wait = intended - now();
      if (wait > 0) await sleep(wait);
      // On schedule, the round is timed from the moment it is sent: the timer that woke this loop
      // is up to a millisecond late, and charging that to the round would drown a sub-millisecond
      // hop. Behind schedule (the previous round overran its slot), it is timed from the moment it
      // should have been sent, so the stall is charged and not hidden.
      const from = wait > 0 ? now() : intended;
      let latency;
      try {
        await withTimeout(round(), timeoutMs);
        latency = Math.min(timeoutMs, now() - from);
      } catch {
        latency = timeoutMs;
        if (counted) timeouts++;
      }
      if (counted) latencies.push(latency);
    }
  };
  await run(warmup, false);
  await run(samples, true);
  return { latencies, timeouts, warmup };
}

export function withTimeout(promise, ms) {
  let timer;
  return Promise.race([
    Promise.resolve(promise).finally(() => clearTimeout(timer)),
    new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error('timeout')), ms);
    }),
  ]);
}
