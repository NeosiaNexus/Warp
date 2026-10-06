// What the soak reads from /proc and jcmd (src/sampler.js), parsed from real outputs.
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { classifyConnections, parseCpuTicks, parseJcmd, parseStatus, parseTcpTable } from '../src/sampler.js';

describe('/proc parsers', () => {
  it('reads resident memory and threads from /proc/<pid>/status', () => {
    const status = 'Name:\tjava\nVmPeak:\t 4000000 kB\nVmRSS:\t  614400 kB\nRssAnon:\t  500000 kB\nThreads:\t52\n';

    assert.deepEqual(parseStatus(status), { rss: 600, threads: 52 });
  });

  it('reads user and system CPU ticks, whatever the command name holds', () => {
    const stat = '4242 (java (warp) x) S 1 4242 4242 0 -1 4194560 120 0 0 0 1500 250 0 0 20 0 52 0 900 1 2';

    assert.equal(parseCpuTicks(stat), 1750);
  });

  it('sorts connections into clients and backends by port, ignoring listeners and strangers, and adds up their queues', () => {
    // Warp on 29202 (0x7212), backends on 29200 (0x7210) and 29201 (0x7211).
    const tcp = [
      '  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode',
      '   0: 0100007F:7212 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 101 1 0 100 0 0 10 0',
      '   1: 0100007F:7212 0100007F:D431 01 00002000:00000000 00:00000000 00000000  1000        0 102 1 0 20 4 30 10 -1',
      '   2: 0100007F:C350 0100007F:7210 01 00000000:00000400 00:00000000 00000000  1000        0 103 1 0 20 4 30 10 -1',
      '   3: 0100007F:C351 0100007F:7211 01 00000000:00000000 00:00000000 00000000  1000        0 104 1 0 20 4 30 10 -1',
      '   4: 0100007F:C352 0100007F:7214 01 00000000:00000000 00:00000000 00000000  1000        0 105 1 0 20 4 30 10 -1',
      '   5: 0100007F:7212 0100007F:D432 01 00000000:00000000 00:00000000 00000000  1000        0 999 1 0 20 4 30 10 -1',
    ].join('\n');
    const table = parseTcpTable(tcp);
    const warpSockets = new Set(['101', '102', '103', '104', '105', '106']); // 999: another process

    assert.deepEqual(table.get('102'), { localPort: 29202, remotePort: 54321, state: 1, sendQueue: 8192, receiveQueue: 0 });
    assert.deepEqual(classifyConnections(warpSockets, table, { port: 29202, backends: [29200, 29201] }), { clients: 1, backends: 2, sendQueue: 8192, receiveQueue: 1024 });
  });
});

describe('jcmd parser', () => {
  it('reads the G1 heap and the native memory categories, in MiB', () => {
    const output = `124452:
garbage-first heap   total reserved 524288K, committed 524288K, used 24708K [0x00000000e0000000, 0x0000000100000000)
 region size 1024K, 23 young (23552K), 0 survivors (0K)

Native Memory Tracking:

Total: reserved=1606838KB, committed=231910KB
-                 Java Heap (reserved=524288KB, committed=524288KB)
-                     Class (reserved=1048766KB, committed=1406KB)
                            (classes #2815)
                            (  instance classes #2519, array classes #296)
                            (malloc=190KB tag=Class #4625) (at peak)
                            (mmap: reserved=1048576KB, committed=1216KB, at peak)
                            (  Metadata:   )
                            (    reserved=65536KB, committed=8256KB)
                            (    used=8178KB)
                            (    waste=78KB =0.95%)
                            (  Class space:)
                            (    reserved=1048576KB, committed=1216KB)
                            (    used=1062KB)
                            (    waste=89KB =7.36%)

-                    Thread (reserved=28758KB, committed=1118KB)
-                     Other (reserved=36864KB, committed=36864KB)
                            (malloc=10KB tag=Other #2) (at peak)
`;

    assert.deepEqual(parseJcmd(output), { heapCommitted: 512, heapUsed: 24.1, direct: 36, metaspace: 9 });
  });

  it('leaves out what the output lacks (no native memory tracking)', () => {
    const output = 'garbage-first heap   total reserved 524288K, committed 524288K, used 102400K [0x0, 0x1)\n';

    assert.deepEqual(parseJcmd(output), { heapCommitted: 512, heapUsed: 100, direct: null, metaspace: null });
  });
});
