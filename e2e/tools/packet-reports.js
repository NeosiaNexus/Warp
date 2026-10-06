// Writes the fixtures StateRegistryTest checks Warp's packet ids against, from Mojang's own data
// generator: protocol/src/test/resources/dev/warp/protocol/packet/reports/<version>.json, the id of
// every packet, in every state and direction, of one release. Game versions sharing a protocol
// share every packet id, so there is one fixture per protocol, from 1.21 (the first release whose
// data generator lists packets) on.
//
//   npm run packet-reports                       regenerate every fixture
//   npm run packet-reports -- 26.3               write the fixture of a new protocol's release
//   npm run packet-reports -- --check latest     compare Mojang's latest release with its fixture
//
// `--check` writes nothing: it fails when a release's packet ids differ from the fixture of its
// protocol, or when no fixture has its protocol (a new Minecraft protocol Warp does not know yet).
import { readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join, relative } from 'node:path';
import { parseArgs } from 'node:util';

import { fetchRelease, fixtureJson, generateFixture, packetDifferences } from '../src/packet-reports.js';
import { E2E_DIR } from '../src/versions.js';

const ROOT = join(E2E_DIR, '..');
const FIXTURES = join(ROOT, 'protocol/src/test/resources/dev/warp/protocol/packet/reports');

const { values: opts, positionals } = parseArgs({
  allowPositionals: true,
  options: {
    check: { type: 'boolean', default: false },
    cache: { type: 'string', default: process.env.WARP_E2E_CACHE ?? join(process.env.XDG_CACHE_HOME ?? join(homedir(), '.cache'), 'warp-e2e') },
  },
});

/** The checked-in fixtures, each named after the release it was generated from. */
const checkedIn = readdirSync(FIXTURES)
  .filter((file) => file.endsWith('.json'))
  .map((file) => {
    const fixture = JSON.parse(readFileSync(join(FIXTURES, file), 'utf8'));
    if (file !== `${fixture.version}.json`) throw new Error(`${file} holds the fixture of ${fixture.version}`);
    return fixture;
  })
  .sort((a, b) => a.protocol - b.protocol);
const versions = positionals.length > 0 ? positionals : checkedIn.map((f) => f.version);
let failed = false;
for (const version of versions) {
  const release = await fetchRelease(version);
  const generated = await generateFixture(release, opts.cache);
  const label = `Minecraft ${generated.version} (protocol ${generated.protocol})`;
  const existing = checkedIn.find((f) => f.protocol === generated.protocol);
  if (opts.check) {
    if (!existing) {
      console.error(`${label}: no fixture has this protocol. Add it to ProtocolVersion, then run npm run packet-reports -- ${generated.version}`);
      failed = true;
      continue;
    }
    const differences = packetDifferences(existing.packets, generated.packets);
    if (differences.length > 0) {
      console.error(`${label}: packet ids differ from ${existing.version}.json:\n  ${differences.join('\n  ')}`);
      failed = true;
    } else {
      console.log(`${label}: same packet ids as ${existing.version}.json`);
    }
    continue;
  }
  if (existing && existing.version !== generated.version) {
    console.error(`${label}: ${existing.version}.json already has this protocol, whose versions share every packet id`);
    failed = true;
    continue;
  }
  const target = join(FIXTURES, `${generated.version}.json`);
  writeFileSync(target, fixtureJson(generated));
  if (!existing) checkedIn.push(generated);
  console.log(`${label}: wrote ${relative(ROOT, target)}`);
}
process.exitCode = failed ? 1 : 0;
