// Child processes (Paper, Warp, client drivers) with their output captured to a log file and
// searchable line by line. Every process is tracked so that the harness can always stop what it
// started, even when a scenario throws or the run is interrupted.
import { spawn, execFile } from 'node:child_process';
import { createWriteStream } from 'node:fs';
import { connect } from 'node:net';
import { promisify } from 'node:util';

const execFileAsync = promisify(execFile);
const running = new Set();
// eslint-disable-next-line no-control-regex
const ANSI = /\x1b\[[0-9;?]*[A-Za-z]/g;

export class ManagedProcess {
  /**
   * @param {string} name label used in logs and error messages
   * @param {string} command executable
   * @param {string[]} args arguments
   * @param {{cwd: string, env?: object, logFile: string}} options
   */
  constructor(name, command, args, { cwd, env, logFile }) {
    this.name = name;
    this.command = command;
    this.args = args;
    this.cwd = cwd;
    this.env = env;
    this.logFile = logFile;
    this.lines = [];
    this.waiters = [];
    this.child = null;
    this.exit = null;
  }

  start() {
    const log = createWriteStream(this.logFile, { flags: 'a' });
    // Spawn the executable itself (no shell): `this.child.pid` is the JVM, not a wrapper.
    const child = spawn(this.command, this.args, {
      cwd: this.cwd,
      env: { ...process.env, ...this.env },
      stdio: ['pipe', 'pipe', 'pipe'],
    });
    this.child = child;
    running.add(this);
    let partial = '';
    const onData = (chunk) => {
      // Colour codes make logs unreadable once uploaded as CI artifacts, and break line matching.
      const text = partial + chunk.toString('utf8').replace(ANSI, '');
      log.write(text.slice(partial.length));
      const parts = text.split(/\r?\n/);
      partial = parts.pop();
      for (const line of parts) this.onLine(line);
    };
    child.stdout.on('data', onData);
    child.stderr.on('data', onData);
    child.stdin.on('error', () => {}); // writing to a process that just died is not our error
    this.exited = new Promise((resolve) => {
      child.on('exit', (code, signal) => {
        if (partial) this.onLine(partial);
        this.exit = { code, signal };
        running.delete(this);
        log.end();
        for (const w of this.waiters) w.reject(new Error(`${this.name} exited (${code ?? signal}) while waiting for ${w.pattern}`));
        this.waiters = [];
        resolve(this.exit);
      });
      child.on('error', (e) => {
        this.exit = { code: -1, signal: null, error: e };
        running.delete(this);
        for (const w of this.waiters) w.reject(e);
        this.waiters = [];
        resolve(this.exit);
      });
    });
    return this;
  }

  onLine(line) {
    this.lines.push(line);
    this.waiters = this.waiters.filter((w) => {
      if (w.pattern.test(line)) {
        w.resolve(line);
        return false;
      }
      return true;
    });
  }

  get pid() {
    return this.child?.pid;
  }

  get alive() {
    return this.child !== null && this.exit === null;
  }

  /** Index to pass to {@link waitFor} so that only lines printed after this call match. */
  mark() {
    return this.lines.length;
  }

  /** Resolves with the first line matching `pattern` printed at or after line `from`. */
  waitFor(pattern, timeoutMs, from = 0) {
    for (let i = from; i < this.lines.length; i++) {
      if (pattern.test(this.lines[i])) return Promise.resolve(this.lines[i]);
    }
    if (!this.alive) return Promise.reject(new Error(`${this.name} is not running (waiting for ${pattern})`));
    return new Promise((resolve, reject) => {
      const waiter = { pattern, resolve, reject };
      const timer = setTimeout(() => {
        this.waiters = this.waiters.filter((w) => w !== waiter);
        const tail = this.lines.slice(-15).join('\n  ');
        reject(new Error(`${this.name}: no line matching ${pattern} within ${timeoutMs / 1000}s. Last lines:\n  ${tail}`));
      }, timeoutMs);
      waiter.resolve = (line) => { clearTimeout(timer); resolve(line); };
      waiter.reject = (e) => { clearTimeout(timer); reject(e); };
      this.waiters.push(waiter);
    });
  }

  /** Writes a line to the process's standard input (a server console command). */
  send(line) {
    if (this.alive) this.child.stdin.write(`${line}\n`);
  }

  /**
   * Stops the process: optional console command first, then SIGTERM, then SIGKILL.
   * @returns {Promise<{code: number|null, signal: string|null, forced: boolean}>} `forced` is true
   *   when the process ignored the polite requests and had to be killed.
   */
  async stop({ command = null, graceMs = 30_000, beforeKill = null } = {}) {
    if (!this.alive) return { ...this.exit, forced: false };
    if (command) {
      this.send(command);
      if (await settlesWithin(this.exited, graceMs)) return { ...this.exit, forced: false };
    }
    this.child.kill('SIGTERM');
    if (await settlesWithin(this.exited, graceMs)) return { ...this.exit, forced: false };
    if (beforeKill) await beforeKill(this).catch(() => {});
    this.child.kill('SIGKILL');
    await this.exited;
    return { ...this.exit, forced: true };
  }
}

function settlesWithin(promise, ms) {
  let timer;
  return Promise.race([
    promise.then(() => true),
    new Promise((resolve) => { timer = setTimeout(() => resolve(false), ms); }),
  ]).finally(() => clearTimeout(timer));
}

/** Kills every process still running; used on interruption and as a last-resort cleanup. */
export function killAll(signal = 'SIGKILL') {
  for (const p of running) p.child.kill(signal);
}

/** Runs a short-lived command and returns its stdout. */
export async function run(command, args, options = {}) {
  const { stdout } = await execFileAsync(command, args, { maxBuffer: 64 * 1024 * 1024, ...options });
  return stdout;
}

export const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** Resolves once something accepts TCP connections on 127.0.0.1:`port`. */
export async function waitForPort(port, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const open = await new Promise((resolve) => {
      const socket = connect(port, '127.0.0.1');
      socket.once('connect', () => { socket.destroy(); resolve(true); });
      socket.once('error', () => resolve(false));
    });
    if (open) return;
    if (Date.now() > deadline) throw new Error(`nothing listening on port ${port} after ${timeoutMs / 1000}s`);
    await sleep(200);
  }
}
