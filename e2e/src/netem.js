// A degraded network between the bots and Warp: tc netem (latency, jitter, loss…) on the loopback
// interface, applied only to the TCP traffic of Warp's ports, both ways. Every other loopback
// connection (Warp to its backends, anything else on the machine) is untouched.
//
// lo's root qdisc becomes a prio qdisc with a fourth band, which only the port filters reach, and
// netem on that band. It is removed when the run ends, Ctrl-C and crashes included; what a killed
// run leaves behind is recognised by its handle and replaced by the next one.
import { execFileSync } from 'node:child_process';

import { run } from './proc.js';

const DEVICE = 'lo';
/** Handles of the qdiscs this harness installs: "e2e" in hex. */
const ROOT = 'e2e:';
const NETEM = 'e2e1:';
/** prio's default priority map: unfiltered traffic keeps the three default bands. */
const PRIOMAP = '1 2 2 2 1 2 0 0 1 1 1 1 1 1 1 1'.split(' ');

/**
 * Splits and checks a netem specification ("delay 50ms 20ms distribution normal loss 1%"). Every
 * word goes to tc as one argument, never through a shell.
 */
export function parseNetemSpec(spec) {
  const words = String(spec ?? '').trim().split(/\s+/).filter(Boolean);
  if (!words.length) throw new Error('--netem needs a specification, e.g. "delay 50ms 20ms loss 1%"');
  for (const word of words) {
    if (!/^[\w.%-]+$/.test(word)) throw new Error(`--netem: unexpected "${word}"`);
  }
  if (words.some((word) => word.startsWith('corrupt'))) {
    // Loopback does not verify TCP checksums: a corrupted segment reaches the application as is.
    throw new Error('--netem: corrupt is not supported on loopback (no TCP checksum check there)');
  }
  return words;
}

export class Netem {
  /**
   * @param {object} options
   * @param {string} options.spec netem parameters, see {@link parseNetemSpec}
   * @param {number[]} options.ports TCP ports whose traffic is degraded, both ways
   */
  constructor({ spec, ports }) {
    this.words = parseNetemSpec(spec);
    this.spec = this.words.join(' ');
    this.ports = ports;
    this.active = false;
    this.stopOnExit = () => this.stop();
  }

  async start() {
    if (process.platform !== 'linux') throw new Error('--netem needs Linux (tc)');
    const [root] = JSON.parse(await tc(['-j', 'qdisc', 'show', 'dev', DEVICE, 'root']));
    if (root && root.kind !== 'noqueue' && root.handle !== ROOT) {
      throw new Error(`--netem: ${DEVICE} already has a ${root.kind} qdisc, which this run would replace`);
    }
    this.active = true;
    process.on('exit', this.stopOnExit);
    if (root?.handle === ROOT) await tc(['qdisc', 'del', 'dev', DEVICE, 'root', 'handle', ROOT]);
    await tc(['qdisc', 'add', 'dev', DEVICE, 'root', 'handle', ROOT, 'prio', 'bands', '4', 'priomap', ...PRIOMAP]);
    await tc(['qdisc', 'add', 'dev', DEVICE, 'parent', `${ROOT}4`, 'handle', NETEM, 'netem', ...this.words]);
    for (const port of this.ports) {
      for (const direction of ['dport', 'sport']) {
        const match = ['u32', 'match', 'ip', direction, String(port), '0xffff'];
        await tc(['filter', 'add', 'dev', DEVICE, 'parent', ROOT, 'protocol', 'ip', 'prio', '1', ...match, 'flowid', `${ROOT}4`]);
      }
    }
  }

  /** Packets and bytes netem has handled so far, and how many it dropped. */
  async stats() {
    const qdiscs = JSON.parse(await tc(['-s', '-j', 'qdisc', 'show', 'dev', DEVICE]));
    const netem = qdiscs.find((q) => q.kind === 'netem' && q.handle === NETEM);
    return netem ? { packets: netem.packets, bytes: netem.bytes, drops: netem.drops } : null;
  }

  /** Removes the qdiscs. Synchronous, so that it also runs from a process 'exit' handler. */
  stop() {
    if (!this.active) return;
    this.active = false;
    process.off('exit', this.stopOnExit);
    try {
      execFileSync(...command(['qdisc', 'del', 'dev', DEVICE, 'root', 'handle', ROOT]), { stdio: 'ignore' });
    } catch {
      console.error(`warning: could not remove the netem qdisc: run "sudo tc qdisc del dev ${DEVICE} root"`);
    }
  }
}

function tc(args) {
  return run(...command(args)).catch((e) => {
    const why = String(e.stderr || e.message).trim().split('\n')[0];
    if (/password is required|a terminal is required/.test(why)) throw new Error('--netem needs root or passwordless sudo (tc)');
    throw new Error(`tc ${args.join(' ')}: ${why}`);
  });
}

/** tc itself as root (in a user namespace, see e2e/README.md), else through passwordless sudo (CI). */
function command(args) {
  return process.getuid?.() === 0 ? ['tc', args] : ['sudo', ['-n', 'tc', ...args]];
}
