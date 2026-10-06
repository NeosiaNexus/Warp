// ViaProxy (https://github.com/ViaVersion/ViaProxy) as a protocol bridge in front of Warp.
import { mkdirSync, rmSync } from 'node:fs';
import { join } from 'node:path';

import { download } from '../download.js';
import { ManagedProcess, waitForPort } from '../proc.js';

/** ViaProxy lines that mean it could not translate something Warp sent or expected. */
const VIA_FAILURES = [/\/(ERROR|FATAL)\]/, /Exception in thread /];

export class ViaProxy {
  /**
   * @param {{name: string, port: number, target: number, version: string,
   *          env: {tools: object, cache: string, java: string, logDir: string, runDir: string}}} options
   */
  constructor({ name, port, target, version, env }) {
    Object.assign(this, { name, port, target, version, env });
    this.process = null;
  }

  async start() {
    const tool = this.env.tools.viaproxy;
    const jar = await download(tool.url, join(this.env.cache, 'tools', `ViaProxy-${tool.version}.jar`), { sha256: tool.sha256 });
    const dir = join(this.env.runDir, this.name);
    rmSync(dir, { recursive: true, force: true });
    mkdirSync(dir, { recursive: true });
    const args = [
      '-Xmx512m',
      '-jar',
      jar,
      'cli',
      '--bind-address', `127.0.0.1:${this.port}`,
      '--target-address', `127.0.0.1:${this.target}`,
      '--target-version', this.version,
      '--auth-method', 'NONE',
      '--log-ips', 'false',
      '--chat-signing', 'false',
    ];
    this.process = new ManagedProcess(this.name, this.env.java, args, {
      cwd: dir,
      logFile: join(this.env.logDir, `${this.name}.log`),
      failures: VIA_FAILURES,
    }).start();
    await this.process.waitFor(/Binding proxy server to/, 90_000);
    await waitForPort(this.port, 30_000);
  }

  stop() {
    return this.process?.stop({ graceMs: 10_000 });
  }

  failures() {
    return (this.process?.failures() ?? []).map((line) => `${this.name}: ${line}`);
  }
}
