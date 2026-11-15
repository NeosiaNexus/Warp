// Samples a running proxy once a second, from /proc alone so it works the same for a JVM proxy and a
// Go proxy: resident memory, threads, open file descriptors, CPU percent, bytes read and written at
// the syscall boundary, and the count of client and backend TCP connections. Only the written bytes
// (`wchar`) stand in for bytes forwarded: a JVM's Netty socket reads do not show up in `rchar` while
// a Go runtime's do, so `rchar` is recorded for the record but never compared across proxies.
// Nothing here reads the proxy's own metrics: the methodology measures the proxy from the outside,
// never from inside it. Rows accumulate and are written to CSV at the end
// of each scenario, with the scenario's phase and the offered load annotated on every row.
import { readdirSync, readFileSync, readlinkSync } from 'node:fs';

import { parseCpuTicks, parseStatus, sleep } from './reuse.js';

const USER_HZ = 100;
const MIB = 1024 * 1024;
const TCP_LISTEN = 0x0a;

export class Sampler {
  /**
   * @param {object} options
   * @param {number} options.pid the proxy process
   * @param {number} options.port the proxy's listen port (connections to it are clients)
   * @param {number[]} options.backends backend ports (connections to them are backend links)
   * @param {number} options.origin epoch ms of t=0
   * @param {() => object} options.annotate fields added to each row (phase, offered load, bots)
   */
  constructor({ pid, port, backends, origin, annotate = () => ({}) }) {
    Object.assign(this, { pid, port, backends, origin, annotate });
    this.rows = [];
    this.cpu = null;
    this.io = null;
    this.stopped = false;
    this.loop = null;
  }

  start() {
    this.stopped = false;
    this.loop = (async () => {
      for (let next = Date.now() + 1000; !this.stopped; next += 1000) {
        await sleep(Math.max(0, next - Date.now()));
        if (this.stopped) break;
        try {
          this.sample();
        } catch (e) {
          if (e.code !== 'ENOENT' && e.code !== 'ESRCH') console.error(`sampler: ${e.message}`);
        }
      }
    })();
    return this;
  }

  async stop() {
    this.stopped = true;
    await this.loop;
  }

  /** Reads one row from /proc, or returns null once the process is gone. */
  sample() {
    const row = this.read();
    if (row) this.rows.push(row);
    return row;
  }

  read() {
    let status;
    let stat;
    try {
      status = parseStatus(readFileSync(`/proc/${this.pid}/status`, 'utf8'));
      stat = parseCpuTicks(readFileSync(`/proc/${this.pid}/stat`, 'utf8'));
    } catch (e) {
      if (e.code === 'ENOENT' || e.code === 'ESRCH') return null;
      throw e;
    }
    const now = Date.now();
    const cpu = this.cpu ? (100 * (stat - this.cpu.ticks)) / USER_HZ / ((now - this.cpu.at) / 1000) : null;
    this.cpu = { ticks: stat, at: now };

    const io = this.readIo();
    const bytes =
      this.io && io
        ? { rchar: io.rchar - this.io.rchar, wchar: io.wchar - this.io.wchar }
        : { rchar: 0, wchar: 0 };
    this.io = io;

    const { fds, sockets } = this.readFds();
    const conns = classify(sockets, readTcpTables(), { port: this.port, backends: this.backends });

    return {
      t: Math.round((now - this.origin) / 100) / 10,
      ...this.annotate(),
      rssMiB: Math.round((status.rss / 1) * 10) / 10,
      threads: status.threads,
      fds,
      cpuPct: cpu === null ? null : Math.round(cpu),
      clients: conns.clients,
      backendConns: conns.backends,
      readBytes: Math.max(0, bytes.rchar),
      writeBytes: Math.max(0, bytes.wchar),
    };
  }

  readIo() {
    try {
      const text = readFileSync(`/proc/${this.pid}/io`, 'utf8');
      const num = (key) => Number(new RegExp(`^${key}:\\s+(\\d+)`, 'm').exec(text)?.[1] ?? 0);
      return { rchar: num('rchar'), wchar: num('wchar') };
    } catch {
      return null;
    }
  }

  readFds() {
    const dir = `/proc/${this.pid}/fd`;
    const entries = readdirSync(dir);
    const sockets = new Set();
    for (const fd of entries) {
      try {
        const inode = /^socket:\[(\d+)\]$/.exec(readlinkSync(`${dir}/${fd}`))?.[1];
        if (inode) sockets.add(inode);
      } catch {
        // closed between listing and read
      }
    }
    return { fds: entries.length, sockets };
  }
}

// parseStatus returns rss already in MiB (e2e sampler.js); keep it as is.

function readTcpTables() {
  const table = new Map();
  for (const file of ['/proc/net/tcp', '/proc/net/tcp6']) {
    try {
      parseTcp(readFileSync(file, 'utf8'), table);
    } catch {
      // no IPv6
    }
  }
  return table;
}

function parseTcp(text, into) {
  for (const line of text.split('\n').slice(1)) {
    const f = line.trim().split(/\s+/);
    if (f.length < 10) continue;
    const port = (a) => parseInt(a.slice(a.lastIndexOf(':') + 1), 16);
    into.set(f[9], { localPort: port(f[1]), remotePort: port(f[2]), state: parseInt(f[3], 16) });
  }
  return into;
}

function classify(inodes, table, { port, backends }) {
  const found = { clients: 0, backends: 0 };
  for (const inode of inodes) {
    const row = table.get(inode);
    if (!row || row.state === TCP_LISTEN) continue;
    if (row.localPort === port) found.clients++;
    else if (backends.includes(row.remotePort)) found.backends++;
  }
  return found;
}

/** Column order for the CSV. */
export const COLUMNS = ['t', 'proxy', 'scenario', 'phase', 'offered', 'bots', 'rssMiB', 'threads', 'fds', 'cpuPct', 'clients', 'backendConns', 'readBytes', 'writeBytes'];

/** Serialises rows to CSV text. */
export function toCsv(rows, extra = {}) {
  const header = COLUMNS.join(',');
  const textColumns = new Set(['proxy', 'scenario', 'phase']);
  const cell = (r, c) => {
    const v = r[c] ?? extra[c];
    if (v === undefined || v === null) return textColumns.has(c) ? '' : '0';
    return String(v);
  };
  const body = rows.map((r) => COLUMNS.map((c) => cell(r, c)).join(',')).join('\n');
  return `${header}\n${body}\n`;
}

export { MIB };
