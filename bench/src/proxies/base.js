// Shared behaviour for every proxy adapter: where its working directory and log go, how it is
// launched as a managed process, and how the orchestrator starts it and waits until it listens.
// Subclasses provide the artifact, the config, the launch command and the start-up lines that prove
// natives loaded.
import { mkdirSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';

import { ManagedProcess, waitForPort } from '../reuse.js';

export class Proxy {
  /**
   * @param {object} options
   * @param {string} options.id proxy id
   * @param {object} options.spec versions.json entry
   * @param {number} options.port the port the proxy listens on
   * @param {Record<string, number>} options.backends backend name -> port
   * @param {string} options.defaultBackend name tried first
   * @param {number} options.compressionThreshold
   * @param {number} options.compressionLevel
   * @param {string} options.secret forwarding secret
   * @param {string} options.cacheDir download cache
   * @param {string} options.workDir this proxy's working directory (wiped)
   * @param {string} options.logDir
   * @param {string[]} [options.cpus] CPU ids to pin the proxy to with taskset, or null
   * @param {string} [options.logName] base name of the log file (default: the proxy id)
   */
  constructor(options) {
    Object.assign(this, options);
    this.process = null;
    this.jar = null;
    this.bin = null;
    this.javaPath = null; // set by the JVM adapters: the java executable that runs the proxy
  }

  /** The jcmd of the JDK that runs the proxy, or null for a proxy that is not a JVM. */
  get jcmd() {
    return this.javaPath ? join(dirname(this.javaPath), 'jcmd') : null;
  }

  /**
   * Log lines that mean the proxy engaged a protection or took a routing decision of its own (a
   * circuit breaker, an anti-bot, a rate limit): the load then differs from what the other proxies
   * carried, so the validity gate rejects the measurement. Adapters override this.
   * @returns {RegExp[]}
   */
  get protectionPatterns() {
    return [];
  }

  /** A point in the proxy's log; pass it to {@link protections} to look only at what follows. */
  mark() {
    return this.process?.mark() ?? 0;
  }

  /** How many protection lines the proxy logged since `mark`, with the first as a sample. */
  protections(mark = 0) {
    const found = { protections: 0, protectionSample: null, dead: !this.process?.alive };
    const lines = this.process?.lines ?? [];
    const patterns = this.protectionPatterns;
    for (let i = mark; i < lines.length; i++) {
      if (!patterns.some((p) => p.test(lines[i]))) continue;
      found.protections++;
      found.protectionSample ??= lines[i].trim().slice(0, 160);
    }
    return found;
  }

  /** 'modern', 'legacy' or 'none': the forwarding this proxy uses (backends are configured to match). */
  get forwarding() {
    return 'modern';
  }

  /** Readies the working directory and returns it. */
  freshWorkDir() {
    rmSync(this.workDir, { recursive: true, force: true });
    mkdirSync(this.workDir, { recursive: true });
    return this.workDir;
  }

  /** Wraps argv in taskset when CPU pinning is requested. */
  pinned(command, args) {
    if (this.cpus?.length) return { command: 'taskset', args: ['-c', this.cpus.join(','), command, ...args] };
    return { command, args };
  }

  /**
   * Launches the proxy and resolves once it accepts connections on its port.
   * @returns {Promise<ManagedProcess>}
   */
  async launch() {
    const { command, args, env } = await this.spawnSpec();
    const pinned = this.pinned(command, args);
    this.process = new ManagedProcess(this.id, pinned.command, pinned.args, {
      cwd: this.workDir,
      env,
      logFile: join(this.logDir, `${this.logName ?? this.id}.log`),
      failures: this.failurePatterns ?? [],
    }).start();
    await Promise.race([
      waitForPort(this.port, 120_000),
      this.process.exited.then((exit) => {
        throw new Error(`${this.id} exited before listening (code ${exit.code ?? exit.signal})`);
      }),
    ]);
    return this.process;
  }

  /**
   * The start-up lines that prove natives and the right transport loaded, each a
   * `{label, pattern, required}`. A required check that finds no line marks the run unfair; an
   * optional one is reported for the record. Adapters override this.
   * @returns {{label: string, pattern: RegExp, required: boolean}[]}
   */
  get nativeChecks() {
    return [];
  }

  /**
   * Checks the start-up log for the lines that prove natives and the right transport loaded.
   * `checked` is false when the adapter requires nothing (a Go proxy has no natives to load), so the
   * report can say "none to check" instead of "confirmed".
   * @returns {{ok: boolean, checked: boolean, notes: string[]}}
   */
  verifyNatives() {
    const notes = [];
    let ok = true;
    const checks = this.nativeChecks;
    const checked = checks.some((c) => c.required);
    for (const { label, pattern, required } of checks) {
      const line = this.process?.lines.find((l) => pattern.test(l));
      if (line) notes.push(`${label}: ${line.trim()}`);
      else {
        notes.push(`${label}: NOT CONFIRMED (pattern ${pattern})`);
        if (required) ok = false;
      }
    }
    return { ok, checked, notes };
  }

  /** The PID the sampler watches. For a JVM proxy under taskset this is still the JVM. */
  get pid() {
    return this.process?.pid ?? null;
  }

  async stop() {
    if (!this.process) return null;
    return this.process.stop({ command: this.stopCommand ?? null, graceMs: 20_000 });
  }
}
