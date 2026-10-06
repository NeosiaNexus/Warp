// Samples a running JVM for the soak (src/soak.js): what the kernel reports in /proc (resident
// memory, file descriptors, TCP connections by peer and their queues, threads, CPU time), and what
// the JVM reports through jcmd (heap, live heap after a full GC, native memory by category). Linux
// only.
import { readdirSync, readFileSync, readlinkSync } from 'node:fs';

import { sleep } from './proc.js';

/** Clock ticks per second in /proc/<pid>/stat (USER_HZ: 100 on every Linux architecture). */
const USER_HZ = 100;
const MIB = 1024 * 1024;
/** TCP state of a listening socket in /proc/net/tcp. */
const TCP_LISTEN = 0x0a;

export class ProcessSampler {
  /**
   * @param {object} options
   * @param {number} options.pid process to sample
   * @param {(commands: string[]) => Promise<string>} options.jcmd runs diagnostic commands in the JVM
   * @param {number} options.port the port it serves: connections to it are clients
   * @param {number[]} options.backends ports it connects to: connections to them are backends
   * @param {number} options.origin time 0 of the samples (epoch milliseconds)
   * @param {number} options.intervalMs time between two samples
   * @param {number} options.gcEveryMs time between two samples that force a full GC first
   * @param {() => object} options.annotate fields added to each sample (phase, bots online)
   */
  constructor({ pid, jcmd, port, backends, origin, intervalMs, gcEveryMs, annotate = () => ({}) }) {
    Object.assign(this, { pid, jcmd, port, backends, origin, intervalMs, gcEveryMs, annotate });
    this.samples = [];
    this.lastGc = -Infinity;
    this.cpu = null;
    this.loop = null;
    this.stopped = false;
  }

  /** Samples every `intervalMs`, from one interval from now until {@link stop}. */
  start() {
    this.loop = (async () => {
      for (let next = Date.now() + this.intervalMs; !this.stopped; next = Math.max(next + this.intervalMs, Date.now())) {
        await sleep(Math.max(0, next - Date.now()));
        if (this.stopped) break;
        // A sample that fails is a gap in the series, not the end of it.
        await this.sample().catch((e) => console.error(`sampler: ${e.message}`));
      }
    })();
    return this;
  }

  async stop() {
    this.stopped = true;
    await this.loop;
  }

  /**
   * Takes one sample and records it. A full GC comes first when `gc` is set or one is due: the heap
   * it leaves is the live heap, the measure of a heap leak.
   * @returns {Promise<object|null>} the sample, or null once the process is gone
   */
  async sample({ gc = false } = {}) {
    const t = Math.round((Date.now() - this.origin) / 100) / 10;
    const context = this.annotate();
    const kernel = this.readProc();
    if (!kernel) return null;
    const collect = gc || Date.now() - this.lastGc >= this.gcEveryMs;
    if (collect) this.lastGc = Date.now();
    const commands = [...(collect ? ['GC.run'] : []), 'GC.heap_info', 'VM.native_memory summary'];
    const jvm = await this.jcmd(commands).then(parseJcmd, () => ({}));
    const sample = {
      t,
      ...context,
      ...kernel,
      heapUsed: jvm.heapUsed ?? null,
      heapCommitted: jvm.heapCommitted ?? null,
      heapLive: collect ? (jvm.heapUsed ?? null) : null,
      direct: jvm.direct ?? null,
      metaspace: jvm.metaspace ?? null,
    };
    this.samples.push(sample);
    return sample;
  }

  /** What the kernel says about the process now, or null once it is gone. */
  readProc() {
    try {
      const status = parseStatus(readFileSync(`/proc/${this.pid}/status`, 'utf8'));
      const cpuTicks = parseCpuTicks(readFileSync(`/proc/${this.pid}/stat`, 'utf8'));
      const { count, sockets } = this.readFds();
      const connections = classifyConnections(sockets, readTcpTables(), { port: this.port, backends: this.backends });
      const now = Date.now();
      const cpu = this.cpu ? (100 * (cpuTicks - this.cpu.ticks)) / USER_HZ / ((now - this.cpu.at) / 1000) : null;
      this.cpu = { ticks: cpuTicks, at: now };
      return {
        rss: status.rss,
        threads: status.threads,
        fds: count,
        sockets: sockets.size,
        clients: connections.clients,
        backends: connections.backends,
        sendQueue: kib(connections.sendQueue),
        receiveQueue: kib(connections.receiveQueue),
        cpu: cpu === null ? null : Math.round(cpu),
      };
    } catch (e) {
      if (e.code === 'ENOENT' || e.code === 'ESRCH') return null;
      throw e;
    }
  }

  /** Connections the process holds now, from /proc alone (cheap enough to poll). */
  connections() {
    return classifyConnections(this.readFds().sockets, readTcpTables(), { port: this.port, backends: this.backends });
  }

  /** How many file descriptors the process has open, and the inodes of those that are sockets. */
  readFds() {
    const dir = `/proc/${this.pid}/fd`;
    const fds = readdirSync(dir);
    const sockets = new Set();
    for (const fd of fds) {
      try {
        const inode = /^socket:\[(\d+)\]$/.exec(readlinkSync(`${dir}/${fd}`))?.[1];
        if (inode) sockets.add(inode);
      } catch {
        // closed between the listing and the read
      }
    }
    return { count: fds.length, sockets };
  }
}

// ---------------------------------------------------------------------------
// Parsers (exported for tests)
// ---------------------------------------------------------------------------

/** Resident memory (MiB) and thread count from /proc/<pid>/status. */
export function parseStatus(text) {
  const kib = (key) => Number(new RegExp(`^${key}:\\s+(\\d+) kB`, 'm').exec(text)?.[1]);
  return { rss: mib(kib('VmRSS') * 1024), threads: Number(/^Threads:\s+(\d+)/m.exec(text)?.[1]) };
}

/** User plus system CPU time, in clock ticks, from /proc/<pid>/stat. */
export function parseCpuTicks(text) {
  // The command name (field 2) is in parentheses and may contain anything: count after the last ')'.
  const fields = text.slice(text.lastIndexOf(')') + 2).split(' ');
  return Number(fields[11]) + Number(fields[12]); // utime and stime, fields 14 and 15
}

/**
 * Rows of /proc/net/tcp or /proc/net/tcp6, by socket inode. `sendQueue`: bytes written but not yet
 * acknowledged by the peer; `receiveQueue`: bytes received but not yet read by the process.
 * @returns {Map<string, {localPort: number, remotePort: number, state: number, sendQueue: number, receiveQueue: number}>}
 */
export function parseTcpTable(text, into = new Map()) {
  for (const line of text.split('\n').slice(1)) {
    const fields = line.trim().split(/\s+/);
    if (fields.length < 10) continue;
    const port = (address) => parseInt(address.slice(address.lastIndexOf(':') + 1), 16);
    const [sendQueue, receiveQueue] = fields[4].split(':').map((hex) => parseInt(hex, 16));
    into.set(fields[9], { localPort: port(fields[1]), remotePort: port(fields[2]), state: parseInt(fields[3], 16), sendQueue, receiveQueue });
  }
  return into;
}

function readTcpTables() {
  const table = new Map();
  for (const file of ['/proc/net/tcp', '/proc/net/tcp6']) {
    try {
      parseTcpTable(readFileSync(file, 'utf8'), table);
    } catch {
      // no IPv6
    }
  }
  return table;
}

/**
 * Sorts a process's sockets: connections accepted on `port` (clients), connections to one of
 * `backends`, and anything else (listeners, the session server, UDP). Also adds up what is queued
 * toward the clients (the network is slower than Warp) and what Warp has not read yet on either
 * side (it paused reading, or is behind).
 */
export function classifyConnections(inodes, table, { port, backends }) {
  const found = { clients: 0, backends: 0, sendQueue: 0, receiveQueue: 0 };
  for (const inode of inodes) {
    const row = table.get(inode);
    if (!row || row.state === TCP_LISTEN) continue;
    if (row.localPort === port) {
      found.clients++;
      found.sendQueue += row.sendQueue;
      found.receiveQueue += row.receiveQueue;
    } else if (backends.includes(row.remotePort)) {
      found.backends++;
      found.receiveQueue += row.receiveQueue;
    }
  }
  return found;
}

/**
 * Heap (G1's `GC.heap_info`) and native memory (`VM.native_memory summary`) from one jcmd output, in
 * MiB. NMT's "Other" is memory allocated outside the heap through Unsafe or ByteBuffer.allocateDirect:
 * Netty's direct buffers. Metaspace is what its two parts (metadata, class space) use.
 */
export function parseJcmd(text) {
  const kib = (pattern) => {
    const value = pattern.exec(text)?.[1];
    return value === undefined ? null : Number(value);
  };
  const heap = /committed (\d+)K, used (\d+)K/.exec(text);
  const direct = kib(/^-\s+Other \(reserved=\d+KB, committed=(\d+)KB/m);
  const metadata = kib(/\(\s*Metadata:\s*\)(?:\s*\(.*\))*?\s*\(\s*used=(\d+)KB\)/);
  const classSpace = kib(/\(\s*Class space:\s*\)(?:\s*\(.*\))*?\s*\(\s*used=(\d+)KB\)/);
  return {
    heapCommitted: heap ? mib(Number(heap[1]) * 1024) : null,
    heapUsed: heap ? mib(Number(heap[2]) * 1024) : null,
    direct: direct === null ? null : mib(direct * 1024),
    metaspace: metadata === null ? null : mib((metadata + (classSpace ?? 0)) * 1024),
  };
}

const mib = (bytes) => Math.round((bytes / MIB) * 10) / 10;
const kib = (bytes) => Math.round(bytes / 1024);
