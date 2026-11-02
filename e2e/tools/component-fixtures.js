// Writes the fixtures Warp's ComponentFixturesTest checks its component encoder against: what real
// servers send their players for the same components, packet by packet, at each version where the
// format of components or of their packets changed.
//
//   npm run component-fixtures                   every version below
//   npm run component-fixtures -- 1.8.8 1.21.5   some of them (their lines are replaced)
//
// For each version it boots the server of versions.json (Paper, or vanilla where Paper never
// released), connects a bot straight to it, and types console commands: `tellraw` with each
// component of the corpus, `title` with titles, subtitles, times and action bars, and last `kick`.
// The bot keeps the body of every chat, title and disconnect packet it receives. The server parses
// each component from the command and writes it with its own serializer, so the bytes are the
// version's own: JSON before 1.20.3, NBT from 1.20.3.
//
// The commands use each version's syntax (hover `value` before 1.16, `contents` after, snake case
// events from 1.21.5). Where a version lacks a feature, the corpus gives it what Warp sends in its
// place (the nearest named colour for RGB, a translation for a keybind), so the fixture also checks
// Warp's substitutes. Selector, score and NBT contents are left out: the server resolves them
// before sending. The test builds the same components with Adventure and compares: the JSON byte
// for byte, the NBT tag by tag.
import { mkdirSync, readFileSync, rmSync, writeFileSync, existsSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { parseArgs } from 'node:util';

import { Backend, fetchPreseeded, fetchServerJar, serverCacheDir } from '../src/backend.js';
import { connect } from '../src/clients/mineflayer.js';
import { findJava } from '../src/java.js';
import { sleep } from '../src/proc.js';
import { E2E_DIR, findEntry, loadMatrix } from '../src/versions.js';

const ROOT = join(E2E_DIR, '..');
const FIXTURES = join(ROOT, 'protocol/src/test/resources/dev/warp/protocol/text/vanilla-components.txt');

/**
 * Versions on each side of every change to components or to the packets that carry them: 1.8
 * (insertion, show entity, titles), 1.9 (plain text no longer written as a bare string), 1.11 (the
 * Title packet's action bar), 1.12 (keybind), 1.13 (entity names as JSON), 1.14 (no more HTML
 * escaping), 1.15 (copy to clipboard), 1.16 (RGB, font, hover contents), 1.19 (System Chat
 * Message), 1.19.3 (translation arguments always components), 1.19.4 (translation fallback),
 * 1.20.2 (last JSON play state), 1.20.3 (NBT, codecs), 1.21.4 (shadow colour), 1.21.5 (snake case,
 * typed events), 26.1.
 */
const VERSIONS = [
  '1.7.10', '1.8.8', '1.9.4', '1.10.2', '1.11.2', '1.12.2', '1.13.2', '1.14.4', '1.15.2', '1.16.5', '1.19', '1.19.2',
  '1.19.3', '1.19.4', '1.20.2', '1.20.4', '1.21.4', '1.21.5', '26.1.2',
];

/** The packets that carry what the commands send, by minecraft-data name. */
const CAPTURED = new Set(['chat', 'system_chat', 'title', 'set_title_text', 'set_title_subtitle', 'set_title_time', 'action_bar', 'clear_titles', 'kick_disconnect']);

const UUID = '6a085b2c-19fb-4986-b453-231aa942bbec';
const BOT = 'e2e_fixtures';
const PORT = 26190;

const { values: opts, positionals } = parseArgs({
  allowPositionals: true,
  options: {
    cache: { type: 'string', default: process.env.WARP_E2E_CACHE ?? join(process.env.XDG_CACHE_HOME ?? join(homedir(), '.cache'), 'warp-e2e') },
  },
});

/** The features of a protocol that change how the corpus is written. */
function era(protocol) {
  return {
    insertion: protocol >= 47,
    entities: protocol >= 47,
    titles: protocol >= 47,
    actionBar: protocol >= 315,
    keybind: protocol >= 335,
    jsonNames: protocol >= 393,
    clipboard: protocol >= 573,
    modern: protocol >= 735, // RGB, font, hover contents
    fallback: protocol >= 762,
    shadow: protocol >= 769,
    snake: protocol >= 770, // snake case, typed events, SNBT commands
  };
}

/** A click event in the era's form. */
function click(e, action, field, value) {
  return e.snake ? { click_event: { action, [field]: value } } : { clickEvent: { action, value: String(value) } };
}

/** A hover event: `value` before 1.16, `contents` after, inlined with show_text's `value` from 1.21.5. */
function hover(e, action, modern, legacy) {
  if (e.snake) return { hover_event: action === 'show_text' ? { action, value: modern } : { action, ...modern } };
  return { hoverEvent: e.modern ? { action, contents: modern } : { action, value: legacy } };
}

/** Quotes a string for SNBT, as Warp's legacy tooltips do. */
const snbt = (s) => `"${s.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`;

/**
 * The corpus: the components the test rebuilds with Adventure, by name (ComponentFixturesTest has
 * the same names), each as the era writes it, or null where the version has no form for it.
 */
function corpus(e) {
  const entityName = e.jsonNames ? '{"text":"Bob"}' : 'Bob';
  return {
    plain: { text: 'Hello, world' },
    // No character outside the BMP: the consoles of older servers mangle them before parsing.
    escapes: { text: 'Quote " back \\ line\nnext\ttab \u0001 <>&=\' é € \u2028' },
    styled: { text: 'Styled', color: 'red', bold: true, italic: false, underlined: true, strikethrough: true, obfuscated: true },
    nested: { text: 'A', color: 'gold', extra: [{ text: 'b', bold: true }, 'c', { translate: 'chat.type.text', with: ['x', { text: 'y', color: 'aqua' }] }] },
    'plain-children': { text: '', extra: ['a', 'b'] },
    rgb: { text: 'rgb', color: e.modern ? '#FF5556' : 'red' },
    insertion: e.insertion ? { text: 'ins', insertion: '/server lobby' } : null,
    'click-run': { text: 'run', ...click(e, 'run_command', 'command', '/server lobby') },
    'click-url': { text: 'url', ...click(e, 'open_url', 'url', 'https://example.com/a?b=c') },
    'click-suggest': { text: 'suggest', ...click(e, 'suggest_command', 'command', '/msg ') },
    'click-copy': e.clipboard ? { text: 'copy', ...click(e, 'copy_to_clipboard', 'value', 'copied') } : null,
    'click-page': e.entities ? { text: 'page', ...click(e, 'change_page', 'page', 2) } : null,
    'hover-text': { text: 'hover', ...hover(e, 'show_text', { text: 'tip', color: 'green' }, { text: 'tip', color: 'green' }) },
    'hover-text-plain': { text: 'hover', ...hover(e, 'show_text', 'tip', { text: 'tip' }) },
    'hover-entity': e.entities
      ? {
          text: 'entity',
          ...hover(
            e,
            'show_entity',
            e.snake ? { id: 'minecraft:pig', uuid: UUID, name: 'Bob' } : { type: 'minecraft:pig', id: UUID, name: { text: 'Bob' } },
            { text: `{type:${snbt('minecraft:pig')},id:${snbt(UUID)},name:${snbt(entityName)}}` },
          ),
        }
      : null,
    'hover-item': {
      text: 'item',
      ...hover(e, 'show_item', { id: 'minecraft:diamond', count: 2 }, { text: `{id:${snbt('minecraft:diamond')},Count:2b}` }),
    },
    font: e.modern ? { text: 'font', font: 'minecraft:uniform' } : null,
    shadow: e.shadow ? { text: 'shadow', shadow_color: -16777216 } : null,
    keybind: e.keybind ? { keybind: 'key.jump' } : { translate: 'key.jump' },
    fallback: e.fallback ? { translate: 'warp.missing', fallback: 'Fallback' } : { translate: 'warp.missing' },
  };
}

/** The title commands, by name, each with the packet it makes the server send. */
function titles(e) {
  if (!e.titles) return [];
  const list = [
    ['title-times', 'times 10 70 20'],
    ['title-subtitle', `subtitle ${JSON.stringify({ text: 'Sub', color: 'gray' })}`],
    ['title-title', `title ${JSON.stringify({ text: 'Title', bold: true })}`],
  ];
  if (e.actionBar) list.push(['title-actionbar', `actionbar ${JSON.stringify({ text: 'Bar', color: 'yellow' })}`]);
  list.push(['title-clear', 'clear'], ['title-reset', 'reset']);
  return list;
}

const hex = (buffer) => buffer.toString('hex');

/** The packet body: the buffer minecraft-protocol hands over starts with the packet id VarInt. */
function body(buffer) {
  let i = 0;
  while (buffer[i] & 0x80) i++;
  return buffer.subarray(i + 1);
}

async function capture(version, matrix) {
  const entry = findEntry(matrix, version);
  if (entry.via) throw new Error(`${version}: the bots cannot speak it without ViaProxy, whose bytes would not be the server's`);
  const e = era(entry.protocol);
  const [java, jar, preseeded] = await Promise.all([findJava(entry.java, opts.cache), fetchServerJar(entry.server, opts.cache), fetchPreseeded(entry.server, opts.cache)]);
  const runDir = join(E2E_DIR, 'build', 'component-fixtures', version);
  rmSync(runDir, { recursive: true, force: true });
  mkdirSync(runDir, { recursive: true });
  const backend = new Backend({
    name: 'fixtures',
    dir: join(runDir, 'server'),
    server: entry.server,
    protocol: entry.protocol,
    jar,
    java,
    port: PORT,
    compressionThreshold: -1,
    gameMode: 1,
    resourcePack: { url: '', sha1: '', id: '' },
    forwarding: null,
    logDir: runDir,
    logSuffix: 'boot',
    mojangHost: null,
  });
  console.log(`${version}: booting ${entry.server.type} ${entry.server.version}`);
  await backend.boot(join(serverCacheDir(entry.server, opts.cache), 'template'), preseeded, { onHang: (reason) => console.log(`${version}: hung (${reason}), booting again`) });
  const lines = [];
  try {
    const bot = await connect({ host: '127.0.0.1', port: PORT, version: entry.client ?? entry.version, username: BOT });
    const received = [];
    bot.bot._client.on('packet', (data, meta, buffer) => {
      if (CAPTURED.has(meta.name)) received.push({ name: meta.name, body: body(buffer) });
    });
    // Lets the join settle: servers send their own chat lines (welcome, game mode) first.
    await sleep(2_000);
    const run = async (name, command, count = 1) => {
      received.length = 0;
      await backend.command(command);
      const deadline = Date.now() + 10_000;
      while (received.length < count) {
        if (Date.now() > deadline) throw new Error(`${version}: ${name}: no packet for "${command}"`);
        await sleep(50);
      }
      await sleep(200); // nothing else may follow
      if (received.length !== count) throw new Error(`${version}: ${name}: ${received.length} packets for "${command}", expected ${count}`);
      for (const packet of received) lines.push(`${version} ${entry.protocol} ${name} ${packet.name} ${hex(packet.body)}`);
    };
    for (const [name, component] of Object.entries(corpus(e))) {
      if (component) await run(name, `tellraw ${BOT} ${JSON.stringify(component)}`);
    }
    for (const [name, args] of titles(e)) await run(name, `title ${BOT} ${args}`);
    await run('kick', `kick ${BOT} Kicked by fixtures`);
    bot.quit();
  } finally {
    await backend.stop();
  }
  console.log(`${version}: ${lines.length} packets`);
  return lines;
}

const matrix = loadMatrix();
const versions = positionals.length ? positionals : VERSIONS;
const kept = existsSync(FIXTURES) && positionals.length
  ? readFileSync(FIXTURES, 'utf8').split('\n').filter((line) => line && !line.startsWith('#') && !versions.includes(line.split(' ')[0]))
  : [];
const captured = [];
for (const version of versions) captured.push(...(await capture(version, matrix)));
const order = (line) => Number(line.split(' ')[1]);
const header = [
  '# What real servers send for the corpus of ComponentFixturesTest: version, protocol, corpus entry,',
  '# packet (minecraft-data name) and the packet body in hex, after its id.',
  '# Generated by e2e/tools/component-fixtures.js (npm run component-fixtures): regenerate it, do not edit it.',
];
mkdirSync(join(FIXTURES, '..'), { recursive: true });
writeFileSync(FIXTURES, `${[...header, ...[...kept, ...captured].sort((a, b) => order(a) - order(b))].join('\n')}\n`);
console.log(`wrote ${FIXTURES}`);
process.exit(0);
