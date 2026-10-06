// End-to-end scenarios. Each one drives real-protocol bots through Warp and throws on the first
// sign of trouble. Backends are told apart by game mode, which every version announces when a
// player joins: lobby = creative, survival = adventure.
import { randomUUID } from 'node:crypto';

import { offlineUuid } from './mojang.js';
import { sleep } from './proc.js';
import { FIRST_PROTOCOL, LAST_PROTOCOL, createProfileKeys, createSigner } from './profile-keys.js';

export const LOBBY_MODE = 'creative';
export const SURVIVAL_MODE = 'adventure';

/** First protocol (1.20) whose servers can be made to trust the mock Mojang's key (see mojang.js). */
export const SIGNED_CHAT = 763;

export function features(protocol) {
  return {
    // Every version switches: through the configuration phase from 1.20.2, with the new server's
    // Join Game and a Respawn before.
    switching: true,
    // Before 1.20.2 Warp clears the tab list on a switch. The bots follow it from 1.8, where it is
    // keyed by UUID; 1.7 keys it by name, with a packet they do not read.
    tabList: protocol >= 47,
    // 1.19 to 1.19.2 clients send their chat signing key in Login Start.
    profileKeys: protocol >= FIRST_PROTOCOL && protocol <= LAST_PROTOCOL,
    // Bots can sign their chat: from 1.20 a server fetches the keys that verify chat sessions from
    // the services host, which the harness mocks. Before, it only trusts Mojang's own key, bundled in
    // authlib, so no bot can open a chat session there.
    signedChat: protocol >= SIGNED_CHAT,
  };
}

/**
 * Checks that the bots that did not switch are still on the lobby. One the lobby dropped, which
 * Warp then moved to the next server, would otherwise pass for a player left behind.
 */
export function checkStayed(stayers) {
  const moved = stayers.filter((bot) => bot.gameMode() !== LOBBY_MODE).map((bot) => `${bot.username} (${bot.gameMode()})`);
  if (moved.length) throw new Error(`left the lobby without switching, see the lobby's log: ${moved.join(', ')}`);
}

/**
 * Checks that the bots that switched server list none of the players that stayed behind. Before
 * 1.20.2 the game keeps its tab list across the Join Game of a switch, so Warp removes the previous
 * server's players itself; from 1.20.2 the client starts a new list after the configuration phase.
 */
export function checkTabLists(switchers, stayed) {
  const behind = new Set(stayed);
  const stale = switchers.flatMap((bot) => bot.listed().filter((name) => behind.has(name)).map((name) => `${bot.username} lists ${name}`));
  if (stale.length) throw new Error(`players of the previous server still listed: ${stale.join(', ')}`);
  return `no player left behind in ${switchers.length} tab lists`;
}

/** Asks Warp where the bot is ("Servers: [lobby], survival"); every version, through Warp only. */
async function proxyReportsServer(ctx, bot, expected) {
  if (!ctx.features.proxy) return;
  const reply = await bot.command('server', /^Servers:/);
  const current = /\[(\w+)\]/.exec(reply)?.[1];
  if (current !== expected) throw new Error(`/server says ${current}, expected ${expected}`);
}

async function joinWarp(ctx, username, target = ctx.warp, profileKeys = null) {
  return ctx.client.connect({ host: '127.0.0.1', port: target.port, username, profileKeys });
}

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
 * Pause after each chat line: a server kicks a player who chats too fast ("Kicked for spamming":
 * each line adds 20, each tick takes 1 off, over 200 is a kick), about 10 lines in a burst.
 */
const CHAT_PACE_MS = 250;

/**
 * Chat around the /server commands Warp answers itself, which never reach the backend.
 *
 * From 1.19.3 a server keeps, for each player, a window over the signed chat messages it sent them,
 * and every chat message or command the client sends moves it: by how many messages the client saw
 * since its last update (the offset), then checking which of the last 20 it acknowledges. A command
 * the proxy keeps from the backend must still pass its offset on, or the backend's window lags
 * behind the client's and refuses the next message: it kicks the player, whom Warp then moves to
 * its fallback (#81).
 *
 * The backend only tracks signed messages, so this checks something only where the bot signs its
 * chat: online variants (the bot opens a chat session over an encrypted connection only) from 1.20
 * (see {@link features}). Elsewhere the chat is unsigned and it only checks that chat still flows.
 */
async function chat(ctx) {
  const signed = ctx.features.signedChat && ctx.variant.online;
  const bot = await joinWarp(ctx, 'e2e_chat', ctx.warp, signed ? ctx.mojang.profileKeys('e2e_chat') : null);
  try {
    await bot.waitForGameMode(LOBBY_MODE, 10_000);
    if (signed) await bot.waitForChatSession(10_000);
    let lines = 0;
    // Every step must leave the bot on the lobby: a kicked player lands on Warp's fallback.
    const step = async (action) => {
      try {
        await action();
      } catch (e) {
        if (bot.gameMode() === LOBBY_MODE) throw e;
      }
      if (bot.gameMode() !== LOBBY_MODE) {
        throw new Error(`${bot.username} left the lobby after chat line ${lines}: the lobby dropped it (see its log)`);
      }
    };
    // Each line comes back from the lobby (signed, where the bot signs) before the next step, so
    // each command acknowledges the line before it.
    const say = (count) =>
      step(async () => {
        for (let i = 0; i < count; i++) {
          await bot.say(`chat line ${++lines}`);
          await sleep(CHAT_PACE_MS);
        }
      });
    await say(2);
    await step(() => bot.command('server', /^Servers:/));
    await say(2);
    await step(() => bot.command('server nowhere', /^Unknown server: nowhere/));
    await say(2);
    await step(() => bot.command('server lobby', /^Already connected to lobby/));
    await say(2);
    await step(() => sleep(500));
    bot.healthy();
    if (signed && bot.stats.signedChat < lines) {
      throw new Error(`${bot.stats.signedChat} of ${lines} chat lines came back signed: the lobby refused the bot's chat session`);
    }
    return `${lines} chat lines (${signed ? 'signed' : 'unsigned'}) around 3 /server commands Warp answered, still on the lobby`;
  } finally {
    bot.quit();
  }
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
    if (ctx.features.switching) {
      // Half of them switch server at the same moment.
      const switchers = bots.slice(0, Math.ceil(count / 2));
      for (const bot of switchers) bot.command('server survival');
      await Promise.all(switchers.map((bot) => bot.waitForGameMode(SURVIVAL_MODE, 30_000)));
      await sleep(2_000);
      for (const bot of bots) bot.healthy();
      const stayers = bots.slice(switchers.length);
      checkStayed(stayers);
      detail += `, ${switchers.length} switched at once`;
      if (ctx.features.tabList) {
        detail += `, ${checkTabLists(switchers, stayers.map((bot) => bot.username))}`;
      }
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

const DAY_MS = 86_400_000;
/**
 * Warp's reason for every refused key: the only profile key reason that 1.19, 1.19.1 and 1.19.2
 * clients all translate (Velocity's `invalid_public_key` is not in 1.19.2's language files).
 */
const REFUSED_KEY = 'multiplayer.disconnect.invalid_public_key_signature';

/**
 * 1.19 to 1.19.2: bots log in with a profile key, as every client of a Microsoft account does. The
 * mock Mojang signs the keys (`ctx.mojang.signer`, which Warp trusts). A valid key logs in (online,
 * the bot signs the verify token instead of encrypting it) and plays: chat, `/server`, a switch.
 * Online, Warp refuses a key Mojang did not sign, an expired one and, from 1.19.1, a key issued to
 * another player than the one authenticated. Offline, it ignores the key, as vanilla 1.19.1+ does,
 * and lets the first two in.
 */
async function profileKey(ctx) {
  const name = 'e2e_signed';
  const bot = await joinWarp(ctx, name, ctx.warp, ctx.mojang.profileKeys(name));
  try {
    await bot.waitForChunks(30, 30_000, 0);
    await bot.waitForGameMode(LOBBY_MODE, 5_000);
    await proxyReportsServer(ctx, bot, 'lobby');
    bot.chat('signed hello from e2e');
    await bot.command('server survival');
    await bot.waitForGameMode(SURVIVAL_MODE, 20_000);
    await proxyReportsServer(ctx, bot, 'survival');
    await sleep(500);
    bot.healthy();
  } finally {
    bot.quit();
  }

  // Each one otherwise issued to the player who logs in with it, so that it fails on one count only.
  const badKeys = [
    { what: 'a key Mojang did not sign', signer: createSigner(2048) },
    { what: 'an expired key', expiresAt: Date.now() - 60_000 },
  ];
  // The key is signed for the UUID the bot announces; the session server vouches for another one.
  if (ctx.variant.online && ctx.entry.protocol > FIRST_PROTOCOL) {
    badKeys.push({ what: 'a key issued to another player', uuid: randomUUID() });
  }
  for (const [i, bad] of badKeys.entries()) {
    const username = `e2e_signed_no${i}`;
    const profileKeys = createProfileKeys({
      signer: bad.signer ?? ctx.mojang.signer,
      uuid: bad.uuid ?? offlineUuid(username),
      expiresAt: bad.expiresAt ?? Date.now() + DAY_MS,
    });
    if (ctx.variant.online) await expectRefused(ctx, username, profileKeys, bad.what);
    else await expectLoggedIn(ctx, username, profileKeys, bad.what);
  }
  const outcome = ctx.variant.online ? 'refused' : 'ignored, offline,';
  return `logged in with a signed key, chatted and switched; ${outcome} ${badKeys.map((k) => k.what).join(', ')}`;
}

/** Joins with `profileKeys` and expects Warp to refuse the login over them, with `REFUSED_KEY`. */
async function expectRefused(ctx, username, profileKeys, what) {
  let bot;
  try {
    bot = await joinWarp(ctx, username, ctx.warp, profileKeys);
  } catch (e) {
    if (String(e.message).includes(`"translate":"${REFUSED_KEY}"`)) return;
    throw new Error(`${what}: expected a refusal with ${REFUSED_KEY}, got: ${e.message}`);
  }
  bot.quit();
  throw new Error(`${what}: logged in, expected a refusal with ${REFUSED_KEY}`);
}

/** Joins with `profileKeys` and expects the login to succeed: offline, Warp ignores them. */
async function expectLoggedIn(ctx, username, profileKeys, what) {
  let bot;
  try {
    bot = await joinWarp(ctx, username, ctx.warp, profileKeys);
  } catch (e) {
    throw new Error(`${what}: refused offline, where Warp ignores the key: ${e.message}`);
  }
  bot.quit();
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

/** Scenarios in execution order. `requires` names a feature: `proxy` (not a --direct run) or one from {@link features}. */
export const SCENARIOS = [
  { name: 'status', run: status },
  { name: 'login', run: login },
  { name: 'keepalive', background: true },
  { name: 'chat', run: chat, requires: 'proxy' },
  { name: 'switching', run: switching, requires: 'switching' },
  { name: 'profile-key', run: profileKey, requires: 'profileKeys' },
  { name: 'crowd', run: crowd },
  { name: 'fallback-unreachable', run: fallbackUnreachable, requires: 'proxy' },
  { name: 'fallback-rejected', run: fallbackRejected, requires: 'proxy' },
];
