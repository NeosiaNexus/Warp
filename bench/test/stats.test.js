// The traffic window: which bytes count as forwarded, and the efficiency derived from them.
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { trafficWindow } from '../src/stats.js';

describe('trafficWindow', () => {
  it('should count bytes written only, so a JVM and a Go proxy are measured alike', () => {
    // A JVM proxy: its socket reads are not accounted in rchar. A Go proxy: they are.
    const jvm = [
      { readBytes: 0, writeBytes: 1000, cpuPct: 10 },
      { readBytes: 0, writeBytes: 1000, cpuPct: 10 },
    ];
    const go = [
      { readBytes: 1000, writeBytes: 1000, cpuPct: 10 },
      { readBytes: 1000, writeBytes: 1000, cpuPct: 10 },
    ];
    assert.deepEqual(trafficWindow(jvm), trafficWindow(go));
    assert.equal(trafficWindow(go).bytesPerSec, 1000);
  });

  it('should derive CPU seconds per MB written', () => {
    const rows = [
      { writeBytes: 1024 * 1024, cpuPct: 50 },
      { writeBytes: 1024 * 1024, cpuPct: 50 },
    ];
    const w = trafficWindow(rows);
    assert.equal(w.mbForwarded, 2);
    assert.equal(w.cpuSeconds, 1);
    assert.equal(w.cpuPerMb, 0.5);
  });

  it('should return nulls for an empty window', () => {
    const w = trafficWindow([]);
    assert.equal(w.bytesPerSec, null);
    assert.equal(w.cpuPerMb, null);
  });
});
