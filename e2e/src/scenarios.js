// End-to-end scenarios. Each one drives real-protocol bots through Warp and throws on the first
// sign of trouble. Backends are told apart by game mode, which every version announces when a
// player joins: lobby = creative, survival = adventure.
import { randomUUID } from 'node:crypto';

import { sleep } from './proc.js';
import { FIRST_PROTOCOL, LAST_PROTOCOL, createProfileKeys, createSigner } from './profile-keys.js';
import { mockUuid } from './session.js';

export const LOBBY_MODE = 'creative';
export const SURVIVAL_MODE = 'adventure';

export function features(protocol) {
  return {
    // Every version switches: through the configuration phase from 1.20.2, with the new server's
    // Join Game and a Respawn before.
    switching: true,
    // 1.19 to 1.19.2 clients send their chat signing key in Login Start.
    profileKeys: protocol >= FIRST_PROTOCOL && protocol <= LAST_PROTOCOL,
  };
}

/** Asks Warp where the bot is ("Servers: [lobby], survival"); every version, through Warp only. */
async function proxyReportsServer(ctx, bot, expected) {
  if (!ctx.features.proxy) return;
  const reply = await bot.command('server', /^Servers:/);
  const current = /\[(\w+)\]/.exec(reply)?.[1];
  if (current !== expected) throw new Error(`/server says ${current}, expected ${expected}`);
}

async function joinWarp(ctx, username, target = ctx.warp, options = {}) {
  return ctx.client.connect({ host: '127.0.0.1', port: target.port, username, ...options });
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

const DAY_MS = 86_400_000;
/**
 * Warp's reason for every refused key: the only profile key reason that 1.19, 1.19.1 and 1.19.2
 * clients all translate (Velocity's `invalid_public_key` is not in 1.19.2's language files).
 */
const REFUSED_KEY = 'multiplayer.disconnect.invalid_public_key_signature';

/**
 * 1.19 to 1.19.2: bots log in with a profile key, as every client of a Microsoft account does. The
 * harness signs the keys in Mojang's place (`ctx.profileKeySigner`, which Warp trusts). A valid key
 * logs in (online, the bot signs the verify token instead of encrypting it) and plays: chat,
 * `/server`, a switch. Online, Warp refuses a key the signer did not sign, an expired one and, from
 * 1.19.1, a key issued to another player than the one authenticated. Offline, it ignores the key,
 * as vanilla 1.19.1+ does, and lets the first two in.
 */
async function profileKey(ctx) {
  const name = 'e2e_signed';
  const uuid = mockUuid(name);
  const profileKeys = createProfileKeys({ signer: ctx.profileKeySigner, uuid, expiresAt: Date.now() + DAY_MS });
  const bot = await joinWarp(ctx, name, ctx.warp, { profileKeys, uuid });
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

  const badKeys = [
    { what: 'a key the signer did not sign', signer: createSigner(2048), uuid },
    { what: 'an expired key', expiresAt: Date.now() - 60_000, uuid },
  ];
  // The key is signed for the UUID the bot announces; the session server vouches for another one.
  if (ctx.variant.online && ctx.entry.protocol > FIRST_PROTOCOL) {
    badKeys.push({ what: 'a key issued to another player', uuid: randomUUID() });
  }
  for (const [i, bad] of badKeys.entries()) {
    const keys = createProfileKeys({
      signer: bad.signer ?? ctx.profileKeySigner,
      uuid: bad.uuid,
      expiresAt: bad.expiresAt ?? Date.now() + DAY_MS,
    });
    const options = { profileKeys: keys, uuid: bad.uuid };
    if (ctx.variant.online) await expectRefused(ctx, `e2e_signed_no${i}`, options, bad.what);
    else await expectLoggedIn(ctx, `e2e_signed_no${i}`, options, bad.what);
  }
  const outcome = ctx.variant.online ? 'refused' : 'ignored, offline,';
  return `logged in with a signed key, chatted and switched; ${outcome} ${badKeys.map((k) => k.what).join(', ')}`;
}

/** Joins with `options` and expects Warp to refuse the login over the key, with `REFUSED_KEY`. */
async function expectRefused(ctx, username, options, what) {
  let bot;
  try {
    bot = await joinWarp(ctx, username, ctx.warp, options);
  } catch (e) {
    if (String(e.message).includes(`"translate":"${REFUSED_KEY}"`)) return;
    throw new Error(`${what}: expected a refusal with ${REFUSED_KEY}, got: ${e.message}`);
  }
  bot.quit();
  throw new Error(`${what}: logged in, expected a refusal with ${REFUSED_KEY}`);
}

/** Joins with `options` and expects the login to succeed: offline, Warp ignores the key. */
async function expectLoggedIn(ctx, username, options, what) {
  let bot;
  try {
    bot = await joinWarp(ctx, username, ctx.warp, options);
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
  { name: 'switching', run: switching, requires: 'switching' },
  { name: 'profile-key', run: profileKey, requires: 'profileKeys' },
  { name: 'crowd', run: crowd },
  { name: 'fallback-unreachable', run: fallbackUnreachable, requires: 'proxy' },
  { name: 'fallback-rejected', run: fallbackRejected, requires: 'proxy' },
];
