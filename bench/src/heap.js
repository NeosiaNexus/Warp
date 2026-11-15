// Heap actually used by a JVM proxy. The JVM proxies run with a fixed, pre-touched heap
// (-Xms2g -Xmx2g -XX:+AlwaysPreTouch), so their resident memory is about 2.3 GiB whatever they do
// and says nothing about what a connection costs. What does is the heap still in use after a full
// collection: the live set. The harness asks the JVM for it with jcmd (GC.run, then GC.heap_info),
// once per measurement and always after the measured window, so the collection and the attach never
// touch a CPU or throughput figure. A Go proxy has no such split: its resident memory follows its
// live set, and is what the report shows for it.
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const exec = promisify(execFile);

/**
 * Reads the used and committed heap from `jcmd GC.heap_info` output.
 * @param {string} text
 * @returns {{usedMiB: number, committedMiB: number|null}|null} null when the output has no heap line
 */
export function parseHeapInfo(text) {
  const m = /heap\s+total(?: reserved)? \d+K,(?: committed (\d+)K,)? used (\d+)K/.exec(text);
  if (!m) return null;
  const mib = (k) => Math.round((Number(k) / 1024) * 10) / 10;
  return { usedMiB: mib(m[2]), committedMiB: m[1] ? mib(m[1]) : null };
}

/**
 * Forces a full collection in the JVM `pid` and returns the heap still in use, or null when the
 * process is not a JVM (`jcmd` is null) or jcmd fails.
 * @param {string|null} jcmd path of the jcmd of the JDK that runs the proxy
 * @param {number} pid
 * @returns {Promise<number|null>} MiB
 */
export async function liveHeapMiB(jcmd, pid) {
  if (!jcmd || !pid) return null;
  try {
    await exec(jcmd, [String(pid), 'GC.run'], { timeout: 20_000 });
    const { stdout } = await exec(jcmd, [String(pid), 'GC.heap_info'], { timeout: 20_000 });
    return parseHeapInfo(stdout)?.usedMiB ?? null;
  } catch {
    return null;
  }
}

/**
 * The slope of `y` over `x` between the lowest and the highest point: what one more unit of `x`
 * costs. Used for memory per connection, between idle levels.
 * @param {{x: number, y: number|null}[]} points
 * @returns {number|null} null with fewer than two measured points
 */
export function slope(points) {
  const measured = points.filter((p) => typeof p.y === 'number').sort((a, b) => a.x - b.x);
  if (measured.length < 2) return null;
  const first = measured[0];
  const last = measured.at(-1);
  return last.x === first.x ? null : (last.y - first.y) / (last.x - first.x);
}
