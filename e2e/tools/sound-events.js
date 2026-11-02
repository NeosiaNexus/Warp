// Writes the sound event table Warp names a sound following an entity with on 1.14 to 1.19.2
// (protocol/src/main/resources/dev/warp/protocol/packet/play/sound-events.txt), and the samples its
// test checks it against (sound-event-samples.txt in the matching test resources), from the data
// generator of Mojang's own server jars: each jar downloaded from Mojang's version manifest and
// checked against the sha1 it lists, one release per protocol (see src/sound-events.js).
//
//   npm run sound-events
import { writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join, relative } from 'node:path';
import { parseArgs } from 'node:util';

import { fetchRelease, generateReports } from '../src/packet-reports.js';
import { samples, soundEvents, table } from '../src/sound-events.js';
import { E2E_DIR } from '../src/versions.js';

const ROOT = join(E2E_DIR, '..');
const TABLE = join(ROOT, 'protocol/src/main/resources/dev/warp/protocol/packet/play/sound-events.txt');
const SAMPLES = join(ROOT, 'protocol/src/test/resources/dev/warp/protocol/packet/play/sound-event-samples.txt');

/** One release per protocol from 1.14 to 1.19.2, oldest first; releases of a protocol share ids. */
const RELEASES = [
  '1.14', '1.14.1', '1.14.2', '1.14.3', '1.14.4',
  '1.15', '1.15.1', '1.15.2',
  '1.16', '1.16.1', '1.16.2', '1.16.3', '1.16.5',
  '1.17', '1.17.1',
  '1.18.1', '1.18.2',
  '1.19', '1.19.2',
];

/** Sounds whose id the samples give, where a protocol has them. */
const SAMPLED = [
  'entity.experience_orb.pickup',
  'entity.player.levelup',
  'block.note_block.pling',
  'ui.button.click',
  'entity.bee.death',
  'music_disc.pigstep',
  'block.amethyst_block.chime',
  'entity.warden.heartbeat',
];

const { values: opts } = parseArgs({
  options: {
    cache: { type: 'string', default: process.env.WARP_E2E_CACHE ?? join(process.env.XDG_CACHE_HOME ?? join(homedir(), '.cache'), 'warp-e2e') },
  },
});

const lists = [];
for (const version of RELEASES) {
  const release = await fetchRelease(version);
  const list = await generateReports(release, opts.cache, (reports, protocol) => ({ protocol, version, names: soundEvents(reports) }));
  if (lists.some((l) => l.protocol === list.protocol)) throw new Error(`${version}: protocol ${list.protocol} listed twice`);
  console.log(`Minecraft ${version} (protocol ${list.protocol}): ${list.names.length} sound events`);
  lists.push(list);
}
writeFileSync(TABLE, table(lists));
writeFileSync(SAMPLES, samples(lists, SAMPLED));
console.log(`wrote ${relative(ROOT, TABLE)} and ${relative(ROOT, SAMPLES)}`);
