// End-to-end scenarios. Each one drives real-protocol bots through Warp and throws on the first
// sign of trouble. Backends are told apart by game mode, which every version announces when a
// player joins: lobby = creative, survival = adventure.
import { randomBytes } from 'node:crypto';

import { sha1 } from './packs.js';
import { sleep } from './proc.js';
import { offlineUuid, sessionUuid } from './session.js';

export const LOBBY_MODE = 'creative';
export const SURVIVAL_MODE = 'adventure';

/** How servers of each type announce themselves: "Paper" ("PaperSpigot" on 1.8), "vanilla". */
const BRANDS = { paper: /Paper/, vanilla: /vanilla/ };

/**
 * What a run offers the scenarios that require it: `true`, or why the run lacks it, which the
 * skipped scenario reports. `proxy` and `forwarding` depend on the run, the others on the protocol.
 * @param {number} protocol
 * @param {{direct?: boolean, forwarding?: string}} run `direct`: a control run, without Warp;
 *   `forwarding`: the variant's forwarding mode
 * @returns {Record<string, true|string>}
 */
export function features(protocol, { direct = false, forwarding = 'none' } = {}) {
  const proxy = direct ? 'no proxy in a control run' : true;
  return {
    proxy,
    // Every version switches: through the configuration phase from 1.20.2, with the new server's
    // Join Game and a Respawn before.
    switching: proxy,
    forwarding: proxy !== true ? proxy : forwarding !== 'none' || 'the variant forwards no player info',
    // Offers carry the pack's SHA-1 in a packet of their own from 1.8 (a plugin message before).
    resourcePack: protocol >= 47 || 'no Resource Pack Send packet before 1.8',
  };
}

const has = (ctx, feature) => ctx.features[feature] === true;

/** Asks Warp where the bot is ("Servers: [lobby], survival"); every version, through Warp only. */
async function proxyReportsServer(ctx, bot, expected) {
  if (!has(ctx, 'proxy')) return;
  const reply = await bot.command('server', /^Servers:/);
  const current = /\[(\w+)\]/.exec(reply)?.[1];
  if (current !== expected) throw new Error(`/server says ${current}, expected ${expected}`);
}

async function joinWarp(ctx, username, target = ctx.warp) {
  return ctx.client.connect({ host: '127.0.0.1', port: target.port, username });
}

/** Moves the bot to survival with Warp's /server, and waits until it plays there. */
async function switchToSurvival(bot) {
  await bot.command('server survival');
  await bot.waitForGameMode(SURVIVAL_MODE, 20_000);
}

const token = () => randomBytes(4).toString('hex');

// ---------------------------------------------------------------------------
// Scenarios
// ---------------------------------------------------------------------------

/**
 * Checks a server list ping answer: Warp's name (any name in a --direct run), and the protocol the
 * bot speaks, without which a vanilla client lists the server as incompatible.
 */
export function checkStatus(response, { direct, protocol }) {
  const name = response?.version?.name ?? '';
  const advertised = response?.version?.protocol;
  if (!direct && !name.startsWith('Warp')) throw new Error(`unexpected status response: ${JSON.stringify(response)}`);
  if (advertised !== protocol) {
    throw new Error(`advertised protocol ${advertised} to a client of protocol ${protocol}, which lists the server as incompatible`);
  }
  return `answered as "${name}" (protocol ${advertised}) in ${response.latency} ms`;
}

async function status(ctx) {
  const response = await ctx.client.ping({ host: '127.0.0.1', port: ctx.warp.port });
  return checkStatus(response, { direct: ctx.direct, protocol: ctx.client.protocol });
}

async function login(ctx) {
  const bot = await joinWarp(ctx, 'e2e_login');
  try {
    const chunks = await bot.waitForChunks(30, 30_000, 0);
    await bot.waitForGameMode(LOBBY_MODE, 5_000);
    await proxyReportsServer(ctx, bot, 'lobby');
    bot.chat('hello from e2e');
    await sleep(500);
    bot.healthy();
    return `spawned on lobby, ${chunks} chunks, ${bot.stats.packets} packets parsed`;
  } finally {
    bot.quit();
  }
}

/**
 * Modern forwarding: the backends trust Warp's word on who the player is, so each one lists the
 * player under the UUID Warp authenticated, not the one an offline-mode server derives from the
 * name.
 */
async function forwarding(ctx) {
  const name = 'e2e_forwarded';
  // ViaProxy-bridged versions run online variants offline (see adaptVariants in cli.js).
  const [expected, source] = ctx.variant.online ? [sessionUuid(name), 'the session server'] : [offlineUuid(name), 'offline mode'];
  const bot = await joinWarp(ctx, name);
  try {
    if (bot.uuid !== expected) throw new Error(`Warp logged ${name} in as ${bot.uuid}, expected ${expected} from ${source}`);
    const servers = ['lobby'];
    await expectListedAs(bot, 0, expected, 'lobby');
    if (has(ctx, 'switching')) {
      const listed = bot.listedAs.length;
      await switchToSurvival(bot);
      await expectListedAs(bot, listed, expected, 'survival');
      servers.push('survival');
    }
    bot.healthy();
    return `${servers.join(' and ')} know ${name} as ${expected}, the UUID from ${source}`;
  } finally {
    bot.quit();
  }
}

/** Checks the `index`-th UUID a backend listed the bot under (its Player Info entry). */
async function expectListedAs(bot, index, expected, server) {
  const uuid = await bot.waitForEntry('listedAs', index, `${server} listing ${bot.username}`, 10_000);
  if (uuid !== expected) throw new Error(`${server} knows ${bot.username} as ${uuid}, not ${expected}: it did not get the forwarded identity`);
}

/** The backend's brand reaches the player through Warp, on joining and on every switch. */
async function brand(ctx) {
  const expected = BRANDS[ctx.entry.server.type];
  const bot = await joinWarp(ctx, 'e2e_brand');
  try {
    const brands = [await bot.waitForEntry('brands', 0, 'the lobby brand', 10_000)];
    if (has(ctx, 'switching')) {
      await switchToSurvival(bot);
      brands.push(await bot.waitForEntry('brands', 1, 'the survival brand', 10_000));
    }
    const wrong = brands.find((b) => !expected.test(b));
    if (wrong !== undefined) throw new Error(`got brand "${wrong}", not the ${ctx.entry.server.type} backend's (${expected})`);
    bot.healthy();
    return `"${brands[0]}" from ${brands.length > 1 ? 'lobby, then from survival after a switch' : 'lobby'}`;
  } finally {
    bot.quit();
  }
}

/**
 * Chat that is not a Warp command reaches the backend: a chat line, which the backend shows every
 * player on it, and a private message, which only the backend can deliver.
 */
async function chat(ctx) {
  const bots = [];
  try {
    for (const name of ['e2e_chat_a', 'e2e_chat_b']) bots.push(await joinWarp(ctx, name));
    const [alice, bob] = bots;
    const line = `e2e ${token()}`;
    alice.chat(line);
    await bob.waitForMessage(new RegExp(line), `${alice.username}'s chat line`, 10_000);
    // `/tell` on every version: 1.8 Paper does not know `/msg`, 1.13+ redirects `/tell` to it. The
    // sender is told what was sent ("You whisper to …"), the recipient receives it.
    const whisper = token();
    await bob.command(`tell ${alice.username} ${whisper}`, new RegExp(whisper));
    await alice.waitForMessage(new RegExp(whisper), `${bob.username}'s private message`, 10_000);
    for (const bot of bots) bot.healthy();
    return 'a chat line and a /tell went from one player to the other through lobby';
  } finally {
    for (const bot of bots) bot.quit();
  }
}

/**
 * The backend's resource pack offer reaches the player through Warp, on joining and on every
 * switch, and the pack it points to is the one it announces.
 */
async function resourcePack(ctx) {
  const bot = await joinWarp(ctx, 'e2e_pack');
  try {
    await expectPack(bot, 0, ctx.packs.lobby, 'lobby');
    let detail = 'lobby offered its pack';
    if (has(ctx, 'switching')) {
      await switchToSurvival(bot);
      await expectPack(bot, 1, ctx.packs.survival, 'survival');
      detail += ', survival its own after a switch';
    }
    bot.healthy();
    return `${detail}; each download has the SHA-1 offered`;
  } finally {
    bot.quit();
  }
}

/**
 * Checks the `index`-th pack offer: `server`'s pack (and its UUID, sent from 1.20.3), whose download
 * matches the SHA-1 offered.
 */
async function expectPack(bot, index, pack, server) {
  const offer = await bot.waitForEntry('resourcePacks', index, `${server}'s resource pack`, 10_000);
  if (offer.url !== pack.url || offer.hash !== pack.sha1 || (offer.id !== undefined && offer.id !== pack.id)) {
    const id = offer.id === undefined ? '' : `, id ${offer.id}`;
    throw new Error(`offered ${offer.url} (SHA-1 ${offer.hash}${id}), expected ${server}'s ${pack.url} (SHA-1 ${pack.sha1}, id ${pack.id})`);
  }
  const response = await fetch(offer.url);
  const downloaded = sha1(Buffer.from(await response.arrayBuffer()));
  if (downloaded !== offer.hash) throw new Error(`${offer.url}: HTTP ${response.status}, SHA-1 ${downloaded} instead of ${offer.hash}`);
}

async function switching(ctx, rounds = 6) {
  const bot = await joinWarp(ctx, 'e2e_switch');
  try {
    await bot.waitForChunks(30, 30_000, 0);
    let target = 'survival';
    const timings = [];
    for (let i = 0; i < rounds; i++) {
      const started = Date.now();
      const chunksBefore = bot.stats.chunks;
      await bot.command(`server ${target}`);
      await bot.waitForGameMode(target === 'survival' ? SURVIVAL_MODE : LOBBY_MODE, 20_000);
      const chunks = await bot.waitForChunks(20, 20_000, chunksBefore);
      await proxyReportsServer(ctx, bot, target);
      bot.healthy();
      timings.push(`${target} ${Date.now() - started}ms/${chunks}ch`);
      target = target === 'survival' ? 'lobby' : 'survival';
    }
    return `${rounds} switches: ${timings.join(', ')}`;
  } finally {
    bot.quit();
  }
}

async function crowd(ctx) {
  const count = ctx.bots;
  const bots = [];
  try {
    for (let i = 0; i < count; i++) {
      bots.push(await joinWarp(ctx, `e2e_crowd${i}`));
      await sleep(100);
    }
    await sleep(15_000);
    for (const bot of bots) bot.healthy();
    let detail = `${count} bots for 15 s`;
    if (has(ctx, 'switching')) {
      // Half of them switch server at the same moment.
      const switchers = bots.slice(0, Math.ceil(count / 2));
      for (const bot of switchers) bot.command('server survival');
      await Promise.all(switchers.map((bot) => bot.waitForGameMode(SURVIVAL_MODE, 30_000)));
      await sleep(2_000);
      for (const bot of bots) bot.healthy();
      detail += `, ${switchers.length} switched at once`;
    }
    const packets = bots.reduce((sum, bot) => sum + bot.stats.packets, 0);
    const chunks = bots.reduce((sum, bot) => sum + bot.stats.chunks, 0);
    return `${detail}; ${packets} packets / ${chunks} chunks parsed`;
  } finally {
    for (const bot of bots) bot.quit();
  }
}

async function fallbackUnreachable(ctx) {
  // warp-alt's default server points at a closed port.
  const bot = await joinWarp(ctx, 'e2e_fb_down', ctx.warpAlt);
  try {
    await bot.waitForGameMode(SURVIVAL_MODE, 10_000);
    await bot.waitForChunks(20, 20_000, 0);
    await proxyReportsServer(ctx, bot, 'survival');
    bot.healthy();
    return 'default server down: player landed on survival';
  } finally {
    bot.quit();
  }
}

async function fallbackRejected(ctx) {
  // An empty whitelist makes the lobby refuse the login, exactly like a full server would.
  await ctx.lobby.command('whitelist on', /white-?list/i);
  try {
    const bot = await joinWarp(ctx, 'e2e_fb_full');
    try {
      await bot.waitForGameMode(SURVIVAL_MODE, 10_000);
      await bot.waitForChunks(20, 20_000, 0);
      await proxyReportsServer(ctx, bot, 'survival');
      bot.healthy();
      return 'lobby refused the login: player landed on survival';
    } finally {
      bot.quit();
    }
  } finally {
    await ctx.lobby.command('whitelist off', /white-?list/i);
  }
}

/**
 * Background soak: one bot stays connected while every other scenario runs, then must still be
 * healthy. Warp sends a keep-alive every 15 s and drops a player whose answer is more than 30 s
 * late, a check made on those same 15 s ticks: a bad answer to the first keep-alive is only acted
 * on 60 s after joining. The bot stays past that point.
 */
export const KEEPALIVE_MIN_MS = 65_000;

export async function startKeepAlive(ctx) {
  const bot = await joinWarp(ctx, 'e2e_keepalive');
  const started = Date.now();
  return async function finish() {
    try {
      const remaining = KEEPALIVE_MIN_MS - (Date.now() - started);
      if (remaining > 0) await sleep(remaining);
      bot.healthy();
      bot.chat('still here');
      await sleep(500);
      bot.healthy();
      return `connected for ${Math.round((Date.now() - started) / 1000)} s across the whole run, ${bot.stats.packets} packets`;
    } finally {
      bot.quit();
    }
  };
}

// ---------------------------------------------------------------------------
// Registry
// ---------------------------------------------------------------------------

/** Scenarios in execution order. `requires` names one of the {@link features}: without it, the scenario is skipped. */
export const SCENARIOS = [
  { name: 'status', run: status },
  { name: 'login', run: login },
  { name: 'forwarding', run: forwarding, requires: 'forwarding' },
  { name: 'keepalive', background: true },
  { name: 'brand', run: brand },
  { name: 'chat', run: chat },
  { name: 'resource-pack', run: resourcePack, requires: 'resourcePack' },
  { name: 'switching', run: switching, requires: 'switching' },
  { name: 'crowd', run: crowd },
  { name: 'fallback-unreachable', run: fallbackUnreachable, requires: 'proxy' },
  { name: 'fallback-rejected', run: fallbackRejected, requires: 'proxy' },
];
