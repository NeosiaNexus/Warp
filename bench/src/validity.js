// The validity gate. A benchmark number is only worth printing when the load that was asked for was
// really applied and nothing other than the proxy limited it. Every scenario therefore records the
// offered and the achieved load, the health of the backends, the saturation of the load generators
// and whether the proxy engaged a protection, and the functions here turn those into a verdict: a
// list of reasons, empty when the measurement is valid. The report prints INVALID and the reasons in
// place of the figures of a measurement that fails the gate. The thresholds are the constants below
// and are documented in docs/benchmarks/README.md.

export const THRESHOLDS = Object.freeze({
  /** Share of the offered connections that must be established, and still be there at the end. */
  minAchievedRatio: 0.995,
  /** A Paper backend must hold at least this many ticks per second (20 is nominal). */
  minBackendTps: 19,
  /** 99th percentile of a load worker's event-loop delay, in ms, above which the generator is the limit. */
  maxLoadgenLagP99Ms: 50,
  /** The same for the stub backend's event loop. */
  maxStubLagP99Ms: 50,
  /** Median of the direct (no proxy) ping round trip on loopback, in ms: more means a loaded path. */
  maxDirectP50Ms: 5,
  /** Share of switch attempts that may fail. */
  maxSwitchFailureRatio: 0.02,
  /** Share of the flood's offered connections that must actually have been opened. */
  minStormDelivery: 0.9,
});

const pct = (ratio) => `${Math.floor(ratio * 1000) / 10}%`;

/** Offered against achieved load: connections established, and connections kept. */
export function checkLoad({ offered, onlineStart, onlineEnd }) {
  const reasons = [];
  if (!offered) return reasons;
  if (onlineStart / offered < THRESHOLDS.minAchievedRatio) {
    reasons.push(`only ${pct(onlineStart / offered)} of the ${offered} offered connections were established (${onlineStart})`);
  } else if (onlineEnd / offered < THRESHOLDS.minAchievedRatio) {
    reasons.push(`only ${onlineEnd} of the ${offered} connections were still there at the end of the window`);
  }
  return reasons;
}

/** Backend health: a backend that fell behind, refused players or failed is the bottleneck. */
export function checkBackends(backends = []) {
  const reasons = [];
  for (const b of backends) {
    if (b.dead) reasons.push(`backend ${b.name} was not running`);
    else if (b.kind === 'stub') {
      if (b.unresponsive) reasons.push(`backend ${b.name} did not answer`);
      if (b.refused > 0) reasons.push(`backend ${b.name} refused ${b.refused} logins`);
      if (b.keepAliveTimeouts > 0) reasons.push(`backend ${b.name} timed out ${b.keepAliveTimeouts} players`);
      if (b.lagP99Ms > THRESHOLDS.maxStubLagP99Ms) reasons.push(`backend ${b.name} was saturated (event loop ${b.lagP99Ms} ms late at p99)`);
    } else {
      if (b.tps === null) reasons.push(`backend ${b.name} did not report its tick rate`);
      else if (b.tps < THRESHOLDS.minBackendTps) reasons.push(`backend ${b.name} below ${THRESHOLDS.minBackendTps} TPS (${b.tps})`);
      if (b.overloads > 0) reasons.push(`backend ${b.name} could not keep up (${b.overloads} overload warnings)`);
      if (b.errors > 0) reasons.push(`backend ${b.name} logged ${b.errors} errors (${b.errorSample})`);
    }
  }
  return reasons;
}

/** Load generators: a worker whose event loop ran late offered less than asked. */
export function checkLoadgen({ lagP99Ms } = {}) {
  return lagP99Ms > THRESHOLDS.maxLoadgenLagP99Ms ? [`load generator saturated (event loop ${lagP99Ms} ms late at p99)`] : [];
}

/** Proxy protections: a breaker, an anti-bot or a rate limit that engaged changed the load. */
export function checkProxy({ protections = 0, protectionSample = null, dead = false } = {}) {
  const reasons = [];
  if (dead) reasons.push('the proxy was not running at the end of the measurement');
  if (protections > 0) reasons.push(`the proxy engaged a protection ${protections} times (${protectionSample})`);
  return reasons;
}

/** Everything a measured window records, checked at once. */
export function checkWindow({ load, backends, loadgen, proxy }) {
  return [...(load ? checkLoad(load) : []), ...checkBackends(backends), ...checkLoadgen(loadgen), ...checkProxy(proxy)];
}

/** The latency probe: every round answered, and a direct path that is really unloaded. */
export function checkLatency({ samples, viaProxy, direct }) {
  const reasons = [];
  for (const [name, r] of [['through the proxy', viaProxy], ['direct', direct]]) {
    if (r.n < samples) reasons.push(`only ${r.n} of ${samples} rounds ran ${name}`);
    if (r.timeouts > 0) reasons.push(`${r.timeouts} rounds timed out ${name}`);
    if (!r.settled) reasons.push(`the probe's join burst never ended ${name}`);
  }
  if (direct.p50 !== null && direct.p50 > THRESHOLDS.maxDirectP50Ms) {
    reasons.push(`direct round trip of ${direct.p50.toFixed(2)} ms at p50 (limit ${THRESHOLDS.maxDirectP50Ms} ms): the path was loaded`);
  }
  return reasons;
}

/** The switch storm: almost every attempted switch must succeed. */
export function checkSwitches({ switches, failures }) {
  const attempts = switches + failures;
  if (attempts === 0) return ['no switch was attempted'];
  return failures / attempts > THRESHOLDS.maxSwitchFailureRatio ? [`${failures} of ${attempts} switches failed (${pct(failures / attempts)})`] : [];
}

/** The flood: the generator must have delivered the offered rate. */
export function checkStorm({ rate, durationMs, attempted, opened }) {
  const offered = (rate * durationMs) / 1000;
  const reasons = [];
  if (attempted / offered < THRESHOLDS.minStormDelivery) reasons.push(`the generator offered only ${pct(attempted / offered)} of ${rate} connections/s`);
  return reasons;
}

/** Wraps reasons into the verdict stored with a result. */
export function verdict(reasons) {
  const unique = [...new Set(reasons)];
  return { valid: unique.length === 0, reasons: unique };
}
