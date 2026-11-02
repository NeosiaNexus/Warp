// Writes the fixtures the protocol module checks its commands codec against, from Mojang's own
// server jars (protocol/src/test/resources/dev/warp/protocol/command/):
//
//   argument-types.json      the argument types each protocol from 1.13 knows: from 1.19 the
//                            minecraft:command_argument_type registry of the data generator's
//                            registries.json, in id order; before, the identifiers the server's
//                            code registers, in registration order
//   trees/<version>.bin.gz   the payload (after the packet id) of the commands packet a vanilla
//                            server of that release sends to an operator, captured live, gzipped
//   trees/<version>.json.gz  the data generator's commands.json report of that release
//
// One release per protocol: the last ProtocolVersion registers. Each server boots on a fresh flat
// world, offline, with the capturing player opped at level 4, on a port nothing else uses, so the
// tool can run beside the e2e suite.
//
//   npm run command-trees                    every protocol from 1.13 (last release of each)
//   npm run command-trees -- 1.21.4 26.3     only these releases
//   npm run command-trees -- --no-capture    argument types and reports only, no server boot
import { mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { createServer } from 'node:net';
import { homedir } from 'node:os';
import { join, relative } from 'node:path';
import { parseArgs } from 'node:util';
import { gzipSync } from 'node:zlib';

import {
  COMMANDS_SINCE,
  REGISTRY_SINCE,
  captureCommandsPacket,
  fetchServer,
  generateReports,
  packetIds,
  registeredArgumentTypes,
} from '../src/command-trees.js';
import { fetchRelease } from '../src/packet-reports.js';
import { run } from '../src/proc.js';
import { E2E_DIR } from '../src/versions.js';

const require = createRequire(import.meta.url);
const mcData = require('minecraft-data');

const ROOT = join(E2E_DIR, '..');
const FIXTURES = join(ROOT, 'protocol/src/test/resources/dev/warp/protocol/command');
const TYPES = join(FIXTURES, 'argument-types.json');
const PACKET_REPORTS = join(ROOT, 'protocol/src/test/resources/dev/warp/protocol/packet/reports');
const PROTOCOL_VERSION = join(ROOT, 'protocol/src/main/java/dev/warp/protocol/ProtocolVersion.java');

const { values: opts, positionals } = parseArgs({
  allowPositionals: true,
  options: {
    'no-capture': { type: 'boolean', default: false },
    cache: { type: 'string', default: process.env.WARP_E2E_CACHE ?? join(process.env.XDG_CACHE_HOME ?? join(homedir(), '.cache'), 'warp-e2e') },
  },
});

/** Every release ProtocolVersion registers, by name, with its protocol. */
function registeredReleases() {
  const source = readFileSync(PROTOCOL_VERSION, 'utf8');
  return new Map([...source.matchAll(/register\((\d+), "([^"]+)"\)/g)].map(([, protocol, name]) => [name, Number(protocol)]));
}

/** The last release of each protocol Warp registers from 1.13, as ProtocolVersion lists them. */
function lastReleasePerProtocol() {
  const byProtocol = new Map();
  for (const [name, protocol] of registeredReleases()) {
    if (protocol >= COMMANDS_SINCE) byProtocol.set(protocol, name);
  }
  return [...byProtocol.entries()].sort(([a], [b]) => a - b).map(([, name]) => name);
}

/**
 * The protocol of a release: as its server jar declares it in `version.json` from 1.14; before,
 * jars have none, and the protocol is the one ProtocolVersion registers for the release.
 */
async function protocolOf(release, jar) {
  const listing = await run('unzip', ['-Z1', jar]);
  if (!listing.split('\n').includes('version.json')) {
    const protocol = registeredReleases().get(release.id);
    if (protocol === undefined) throw new Error(`${release.id}: no version.json, and ProtocolVersion does not register it`);
    return protocol;
  }
  const info = JSON.parse(await run('unzip', ['-p', jar, 'version.json']));
  // 1.14 to 1.14.2 append their build hash to the id: "1.14 / 5dac5567…".
  if (info.id.split(' / ')[0] !== release.id) throw new Error(`the ${release.id} server jar says it is ${info.id}`);
  return info.protocol_version;
}

/** Mojang's packet report of a protocol, if the packet guard has one checked in. */
function packetReport(protocol) {
  for (const file of readdirSync(PACKET_REPORTS).filter((f) => f.endsWith('.json'))) {
    const report = JSON.parse(readFileSync(join(PACKET_REPORTS, file), 'utf8'));
    if (report.protocol === protocol) return report;
  }
  return null;
}

/**
 * minecraft-data's packet definitions of this protocol or, when it has none (1.14.2), of the
 * closest older one it has: the capture only needs the ids of the login, configuration and commands
 * packets, and the tests then decode the packet captured, so a wrong id cannot go unnoticed.
 */
function mcDataProtocol(protocol) {
  for (let p = protocol; p > protocol - 16; p--) {
    for (const candidate of mcData.postNettyVersionsByProtocolVersion.pc[p] ?? []) {
      const data = mcData(candidate.minecraftVersion);
      if (data?.protocol && data.version.version === p) return data.protocol;
    }
  }
  return null;
}

/** A packet id lookup for one protocol: Mojang's report first, minecraft-data otherwise. */
function idLookup(protocol) {
  const report = packetReport(protocol);
  const data = mcDataProtocol(protocol);
  const mcStates = { login: 'login', configuration: 'configuration', play: 'play' };
  const mcDirections = { clientbound: 'toClient', serverbound: 'toServer' };
  return (state, direction, mojang, mcName) => {
    if (report) return report.packets[state]?.[direction]?.[mojang];
    const mappings = data?.[mcStates[state]]?.[mcDirections[direction]]?.types?.packet?.[1]?.[0]?.type?.[1]?.mappings ?? {};
    const entry = Object.entries(mappings).find(([, name]) => name === mcName);
    return entry ? Number.parseInt(entry[0], 16) : undefined;
  };
}

/** A local port nothing listens on. */
function freePort() {
  return new Promise((resolve, reject) => {
    const server = createServer();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address();
      server.close(() => resolve(port));
    });
  });
}

let types = {};
try {
  types = JSON.parse(readFileSync(TYPES, 'utf8')).protocols ?? {};
} catch {
  // first run
}
mkdirSync(join(FIXTURES, 'trees'), { recursive: true });

const versions = positionals.length > 0 ? positionals : lastReleasePerProtocol();
let failed = false;
for (const version of versions) {
  try {
    const release = await fetchRelease(version);
    const { jar, java } = await fetchServer(release, opts.cache);
    const protocol = await protocolOf(release, jar);
    const label = `Minecraft ${release.id} (protocol ${protocol})`;

    const { argumentTypes, commands } = await generateReports(release, jar, java, protocol);
    const entry =
      protocol >= REGISTRY_SINCE
        ? { version: release.id, source: 'registries.json', types: argumentTypes }
        : { version: release.id, source: 'server code', types: await registeredArgumentTypes(release, jar, protocol) };
    types[protocol] = entry;
    console.log(`${label}: ${entry.types.length} argument types (${entry.source})`);
    const report = join(FIXTURES, 'trees', `${release.id}.json.gz`);
    writeFileSync(report, gzipSync(`${JSON.stringify(commands)}\n`, { level: 9 }));
    console.log(`${label}: wrote ${relative(ROOT, report)}`);

    if (!opts['no-capture']) {
      const ids = packetIds(protocol, idLookup(protocol));
      const payload = await captureCommandsPacket(release, jar, java, protocol, ids, await freePort());
      const target = join(FIXTURES, 'trees', `${release.id}.bin.gz`);
      writeFileSync(target, gzipSync(payload, { level: 9 }));
      console.log(`${label}: captured ${payload.length} bytes of commands packet (id 0x${ids.commands.toString(16)}) to ${relative(ROOT, target)}`);
    }
  } catch (e) {
    console.error(`Minecraft ${version}: ${e.stack ?? e}`);
    failed = true;
  }
  const sorted = Object.fromEntries(Object.entries(types).sort(([a], [b]) => Number(a) - Number(b)));
  const header = {
    comment:
      'Argument types of each protocol from 1.13. Generated by e2e/tools/command-trees.js (npm run command-trees): regenerate it, do not edit it. ' +
      'From 1.19 (protocol 759): the minecraft:command_argument_type registry of the vanilla data generator (registries.json), in id order. ' +
      'Before: the identifiers the vanilla server registers, read from its code, in registration order.',
    protocols: sorted,
  };
  writeFileSync(TYPES, `${JSON.stringify(header, null, 2)}\n`);
}
process.exitCode = failed ? 1 : 0;
