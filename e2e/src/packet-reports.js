// Mojang's packet reports: the protocol id of every packet, in every state and direction, as the
// vanilla server's own data generator lists it. Warp's StateRegistryTest checks its packet ids
// against them (tools/packet-reports.js writes them as test fixtures).
import { createHash } from 'node:crypto';
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { download, USER_AGENT } from './download.js';
import { findJava } from './java.js';
import { run } from './proc.js';

const VERSION_MANIFEST = 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json';

/** Mojang's version manifest, fetched once per run. */
let manifest;

/** The first release whose data generator writes `reports/packets.json`. */
export const FIRST_REPORTED_RELEASE = '1.21';

/**
 * Mojang's metadata of a release: its server jar and the Java major version it needs. The
 * manifest lists a sha1 for each version's metadata, which is checked.
 * @param {string} id a version ("1.21.4", "26.3") or "latest" for the latest release
 */
export async function fetchRelease(id) {
  manifest ??= fetchJson(VERSION_MANIFEST);
  const { latest, versions } = await manifest;
  const wanted = id === 'latest' ? latest.release : id;
  const entry = versions.find((v) => v.id === wanted);
  if (!entry) throw new Error(`Mojang's version manifest has no version "${wanted}"`);
  return fetchJson(entry.url, entry.sha1);
}

/**
 * Runs the data generator of a release's server jar (downloaded into the cache, checked against
 * Mojang's sha1) on the Java major version Mojang lists for it.
 * @returns {Promise<{version: string, protocol: number, packets: object}>} its packet fixture
 */
export async function generateFixture(release, cacheDir) {
  const server = release.downloads?.server;
  if (!server) throw new Error(`Minecraft ${release.id} has no server jar`);
  const jar = await download(server.url, join(cacheDir, 'downloads', `minecraft-server-${release.id}.jar`), {
    sha1: server.sha1,
  });
  const java = await findJava(release.javaVersion.majorVersion, cacheDir);
  const workDir = mkdtempSync(join(tmpdir(), 'warp-packet-report-'));
  try {
    // Since 1.18 the server jar is a bundler: it unpacks the server and its libraries into the
    // working directory, then runs `bundlerMainClass` (here the data generator) instead.
    await run(java, ['-DbundlerMainClass=net.minecraft.data.Main', '-jar', jar, '--reports', '--output', 'generated'], {
      cwd: workDir,
    });
    const report = join(workDir, 'generated', 'reports', 'packets.json');
    if (!existsSync(report)) {
      throw new Error(`Minecraft ${release.id} writes no packets report (the first one that does is ${FIRST_REPORTED_RELEASE})`);
    }
    const info = JSON.parse(await run('unzip', ['-p', jar, 'version.json']));
    if (info.id !== release.id) throw new Error(`the ${release.id} server jar says it is ${info.id}`);
    return packetFixture(release.id, info.protocol_version, JSON.parse(readFileSync(report, 'utf8')));
  } finally {
    rmSync(workDir, { recursive: true, force: true });
  }
}

/**
 * Turns the data generator's `packets.json` (state → direction → name → `{protocol_id}`) into a
 * fixture: state → direction → name → id, every level sorted by key, so that the same report
 * always gives the same bytes and two versions' fixtures diff line by line.
 */
export function packetFixture(version, protocol, report) {
  if (!Number.isInteger(protocol)) throw new Error(`${version}: protocol ${protocol} is not a number`);
  const packets = {};
  for (const [state, directions] of sortedEntries(report)) {
    packets[state] = {};
    for (const [direction, named] of sortedEntries(directions)) {
      packets[state][direction] = Object.fromEntries(
        sortedEntries(named).map(([name, packet]) => {
          if (!Number.isInteger(packet?.protocol_id)) throw new Error(`${version}: ${state} ${direction} ${name} has no id`);
          return [name, packet.protocol_id];
        }),
      );
    }
  }
  return { version, protocol, packets };
}

/** One line per packet whose id differs between a checked-in fixture's `packets` and Mojang's. */
export function packetDifferences(fixture, mojang) {
  const lines = [];
  for (const state of keys(fixture, mojang)) {
    for (const direction of keys(fixture[state], mojang[state])) {
      const checkedIn = fixture[state]?.[direction] ?? {};
      const reported = mojang[state]?.[direction] ?? {};
      for (const name of keys(checkedIn, reported)) {
        if (checkedIn[name] !== reported[name]) {
          lines.push(`${state} ${direction} ${name}: fixture ${id(checkedIn[name])}, Mojang ${id(reported[name])}`);
        }
      }
    }
  }
  return lines;
}

/** The JSON text of a fixture, as checked in. */
export const fixtureJson = (fixture) => `${JSON.stringify(fixture, null, 2)}\n`;

const byKey = ([a], [b]) => (a < b ? -1 : a > b ? 1 : 0);
const sortedEntries = (object) => Object.entries(object).sort(byKey);
const keys = (a = {}, b = {}) => [...new Set([...Object.keys(a), ...Object.keys(b)])].sort();
const id = (value) => (value === undefined ? 'absent' : `0x${value.toString(16).toUpperCase().padStart(2, '0')}`);

async function fetchJson(url, sha1) {
  const response = await fetch(url, { headers: { 'User-Agent': USER_AGENT } });
  if (!response.ok) throw new Error(`GET ${url}: HTTP ${response.status}`);
  const body = Buffer.from(await response.arrayBuffer());
  if (sha1) {
    const actual = createHash('sha1').update(body).digest('hex');
    if (actual !== sha1) throw new Error(`${url}: sha1 mismatch (expected ${sha1}, got ${actual})`);
  }
  return JSON.parse(body.toString('utf8'));
}
