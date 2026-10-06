// End-to-end scenarios. Each one drives real-protocol bots through Warp and throws on the first
// sign of trouble. Backends are told apart by game mode, which every version announces when a
// player joins: lobby = creative, survival = adventure.
import { sleep } from './proc.js';

export const LOBBY_MODE = 'creative';
export const SURVIVAL_MODE = 'adventure';

/** First protocol (1.20) whose servers can be made to trust the mock Mojang's key (see mojang.js). */
export const SIGNED_CHAT = 763;

export function features(protocol) {
  return {
    // Every version switches: through the configuration phase from 1.20.2, with the new server's
    // Join Game and a Respawn before.
    switching: true,
    // Bots can sign their chat: from 1.20 a server fetches the keys that verify chat sessions from
    // the services host, which the harness mocks. Before, it only trusts Mojang's own key, bundled in
    // authlib, so no bot can open a chat session there.
    signedChat: protocol >= SIGNED_CHAT,
  };
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
 * Chat around the /server commands Warp answers itself, which never reach the backend.
 *
 * From 1.19.3 a server keeps, for each player, a window over the signed chat messages it sent them,
 * and every chat message or command the client sends moves it: by how many messages the client saw
 * since its last update (the offset), then checking which of the last 20 it acknowledges. A command
 * the proxy keeps from the backend must still pass its offset on, or the backend's window lags
 * behind the client's and the next message is refused: the player is kicked (#81).
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
    let lines = 0;
    const say = async (count) => {
      for (let i = 0; i < count; i++) await bot.say(`chat line ${++lines}`);
    };
    // Each command acknowledges the line before it, which the lobby echoed (signed) to the bot.
    await say(3);
    await bot.command('server', /^Servers:/);
    await say(3);
    await bot.command('server nowhere', /^Unknown server: nowhere/);
    await say(3);
    await bot.command('server lobby', /^Already connected to lobby/);
    await say(3);
    await sleep(500);
    bot.healthy();
    await bot.waitForGameMode(LOBBY_MODE, 1_000);
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

/** Scenarios in execution order. `requires` names a feature: `proxy` (not a --direct run) or one from {@link features}. */
export const SCENARIOS = [
  { name: 'status', run: status },
  { name: 'login', run: login },
  { name: 'keepalive', background: true },
  { name: 'chat', run: chat, requires: 'proxy' },
  { name: 'switching', run: switching, requires: 'switching' },
  { name: 'crowd', run: crowd },
  { name: 'fallback-unreachable', run: fallbackUnreachable, requires: 'proxy' },
  { name: 'fallback-rejected', run: fallbackRejected, requires: 'proxy' },
];
