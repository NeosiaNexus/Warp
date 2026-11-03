// End-to-end scenarios. Each one drives real-protocol bots through Warp and throws on the first
// sign of trouble. Backends are told apart by game mode, which every version announces when a
// player joins: lobby = creative, survival = adventure.
import { randomBytes, randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { connect as tcpConnect } from 'node:net';
import { dirname, join } from 'node:path';

import { flood } from './flood.js';
import { FORWARDED_PLAYER, mojangUuid, offlineUuid } from './mojang.js';
import { sha1 } from './packs.js';
import { sleep } from './proc.js';
import { FIRST_PROTOCOL, LAST_PROTOCOL, createProfileKeys, createSigner } from './profile-keys.js';
import { Warp } from './warp.js';

export const LOBBY_MODE = 'creative';
export const SURVIVAL_MODE = 'adventure';

/** How servers of each type announce themselves: "Paper" ("PaperSpigot" on 1.8), "vanilla". */
const BRANDS = { paper: /Paper/, vanilla: /vanilla/ };

/** First protocol (1.20) whose servers can be made to trust the mock Mojang's key (see mojang.js). */
export const SIGNED_CHAT = 763;

/** The first protocol with transfers: 1.20.5. */
export const TRANSFER_PROTOCOL = 766;

/** Vanilla's reason for refusing a transfer, which Warp gives when `accepts-transfers` is off. */
const TRANSFERS_DISABLED = 'multiplayer.disconnect.transfers_disabled';

/**
 * The message of the day both Warp instances show (`status.motd`, MiniMessage), and its text: a
 * gradient, so that clients before 1.16 get it downsampled to named colors.
 */
export const STATUS_MOTD = '<gradient:#38bdf8:#a78bfa>warp-e2e</gradient> <gray>status';
export const STATUS_MOTD_TEXT = 'warp-e2e status';

/** The maximum the main Warp shows: its default, with no player limit set. */
export const WARP_MAX_PLAYERS = 500;

/** The player limit of warp-alt, which `player-limit` fills. */
export const ALT_MAX_PLAYERS = 1;

/**
 * What a run offers the scenarios that require it: `true`, or why the run lacks it, which the
 * skipped scenario reports. `proxy` and `forwarding` depend on the run, the others on the protocol.
 * @param {number} protocol
 * @param {{direct?: boolean, forwarding?: string, online?: boolean, plugin?: boolean, bridged?: boolean, paper?: boolean}} run
 *   `direct`: a control run, without Warp; `forwarding` and `online`: the variant's forwarding mode
 *   and online mode; `plugin`: Warp runs the probe plugin; `bridged`: bots go through ViaProxy;
 *   `paper`: the backends are Paper servers
 * @returns {Record<string, true|string>}
 */
export function features(protocol, { direct = false, forwarding = 'none', online = false, plugin = false, bridged = false, paper = false } = {}) {
  const proxy = direct ? 'no proxy in a control run' : true;
  return {
    proxy,
    // Every version switches: through the configuration phase from 1.20.2, with the new server's
    // Join Game and a Respawn before.
    switching: proxy,
    // Offline, Warp forwards the UUID a backend derives from the name anyway: nothing to tell apart.
    forwarding: all(
      proxy,
      forwarding !== 'none' || 'the variant forwards no player info',
      online || 'offline, Warp forwards the UUID a backend derives itself',
    ),
    // Offers carry the pack's SHA-1 in a packet of their own from 1.8 (a plugin message before).
    resourcePack: protocol >= 47 || 'no Resource Pack Send packet before 1.8',
    // A plugin of Warp's own shows a boss bar, plays sounds and sends a pack: the bot checks each as
    // its own version receives it, which a bridge would translate.
    audience: all(
      proxy,
      plugin || 'no audience probe plugin (--jar without --plugin)',
      protocol >= 47 || 'the 1.7 bot does not follow boss bars, sounds or plugin message packs',
      !bridged || 'ViaProxy translates what the bot receives',
    ),
    // The probe plugin grants a permission and tells Warp, which re-sends the command list.
    commandRefresh: all(
      proxy,
      plugin || 'no probe plugin (--jar without --plugin)',
      protocol >= COMMANDS_PACKET || 'no command list before 1.13: clients ask for completions',
      !bridged || 'ViaProxy translates the command list the bot receives',
    ),
    // 1.19 to 1.19.2 clients send their chat signing key in Login Start.
    profileKeys: (protocol >= FIRST_PROTOCOL && protocol <= LAST_PROTOCOL) || 'only 1.19 to 1.19.2 clients send a profile key in Login Start',
    // Bots can sign their chat: from 1.20 a server fetches the keys that verify chat sessions from
    // the services host, which the harness mocks. Before, it only trusts Mojang's own key, bundled in
    // authlib, so no bot can open a chat session there.
    signedChat: protocol >= SIGNED_CHAT || "before 1.20 a server trusts only Mojang's own key to verify chat",
    // Transfers arrived in 1.20.5. The bot declares the transfer intent in its own handshake, which
    // a bridge would replace with the one it opens to Warp.
    transfers: all(
      proxy,
      protocol >= TRANSFER_PROTOCOL || 'no Transfer packet before 1.20.5',
      !bridged || 'through ViaProxy, Warp sees the bridge’s handshake, not the bot’s',
    ),
    // The drain runs on a Warp of its own, which no ViaProxy bridge leads to.
    drain: all(proxy, !bridged || 'through ViaProxy, the bridges only lead to warp and warp-alt'),
    // A backend plugin speaks the BungeeCord channel, which Warp answers: Paper loads it, vanilla
    // servers load no plugin.
    bungeeChannel: all(proxy, paper || 'vanilla servers load no plugin to speak the channel'),
    // The e2e plugin grants the admin bot Warp's command permissions: nobody holds them without it.
    adminCommands: all(proxy, plugin || 'no e2e plugin to grant the command permissions (--jar without --plugin)'),
  };
}

/** `true` if every condition is, else the reason of the first one that is not. */
const all = (...conditions) => conditions.find((condition) => condition !== true) ?? true;

const has = (ctx, feature) => ctx.features[feature] === true;

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
  if (!has(ctx, 'proxy')) return;
  const reply = await bot.command('server', /^Servers:/);
  const current = /\[(\w+)\]/.exec(reply)?.[1];
  if (current !== expected) throw new Error(`/server says ${current}, expected ${expected}`);
  checkServerList(bot.componentOf(reply), expected);
}

/**
 * Checks the styles of Warp's answer to /server, a component it encodes for the bot's version (JSON,
 * or NBT from 1.20.3): the current server in green, every other one a link that runs /server <name>,
 * with the click event of the version (`clickEvent` and its `value`, `click_event` and its
 * `command` from 1.21.5).
 */
export function checkServerList(component, current) {
  const nodes = [];
  const walk = (node) => {
    if (node === null || typeof node !== 'object') return;
    nodes.push(node);
    for (const child of [].concat(node.extra ?? [])) walk(child);
  };
  walk(component);
  const text = (node) => node.text ?? node[''];
  const here = nodes.find((n) => text(n) === `[${current}]`);
  if (here?.color !== 'green') throw new Error(`/server: [${current}] is not green in ${JSON.stringify(component)}`);
  const others = nodes.slice(1).filter((n) => /^[\w-]+$/.test(text(n) ?? ''));
  if (others.length === 0) throw new Error(`/server: no other server in ${JSON.stringify(component)}`);
  for (const link of others) {
    const click = link.clickEvent ?? link.click_event;
    const command = click?.value ?? click?.command;
    if (click?.action !== 'run_command' || command !== `/server ${text(link)}`) {
      throw new Error(`/server: ${text(link)} does not link to /server ${text(link)} in ${JSON.stringify(component)}`);
    }
  }
}

async function joinWarp(ctx, username, target = ctx.warp, profileKeys = null, transfer = false) {
  return ctx.client.connect({ host: '127.0.0.1', port: target.port, username, profileKeys, transfer });
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

/** The text of a chat component as a status answer carries it: a string, an object or an array. */
export function plainText(component) {
  if (typeof component === 'string') return component;
  if (Array.isArray(component)) return component.map(plainText).join('');
  if (component === null || typeof component !== 'object') return '';
  return `${component.text ?? ''}${(component.extra ?? []).map(plainText).join('')}`;
}

/**
 * Checks a server list ping answer: Warp's name (any name in a --direct run), and the protocol the
 * bot speaks, without which a vanilla client lists the server as incompatible. With `motd` and
 * `maxPlayers`, also the configured message of the day (its text, whatever the colors became for
 * the client's version) and player limit, and a player count.
 */
export function checkStatus(response, { direct, protocol, motd = null, maxPlayers = null }) {
  const name = response?.version?.name ?? '';
  const advertised = response?.version?.protocol;
  if (!direct && !name.startsWith('Warp')) throw new Error(`unexpected status response: ${JSON.stringify(response)}`);
  if (advertised !== protocol) {
    throw new Error(`advertised protocol ${advertised} to a client of protocol ${protocol}, which lists the server as incompatible`);
  }
  const players = response?.players;
  if (!direct && motd !== null) {
    const text = plainText(response?.description);
    if (!text.includes(motd)) throw new Error(`description "${text}" instead of the configured "${motd}": ${JSON.stringify(response?.description)}`);
  }
  if (!direct && maxPlayers !== null) {
    if (players?.max !== maxPlayers) throw new Error(`players ${JSON.stringify(players)} instead of a maximum of ${maxPlayers}`);
    if (!Number.isInteger(players?.online)) throw new Error(`no player count: ${JSON.stringify(players)}`);
  }
  const counts = players ? `, ${players.online}/${players.max} players` : '';
  return `answered as "${name}" (protocol ${advertised})${counts} in ${response.latency} ms`;
}

async function status(ctx) {
  const response = await ctx.client.ping({ host: '127.0.0.1', port: ctx.warp.port });
  return checkStatus(response, { direct: ctx.direct, protocol: ctx.client.protocol, motd: STATUS_MOTD_TEXT, maxPlayers: WARP_MAX_PLAYERS });
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
 * Modern forwarding: the backends (offline-mode servers) trust Warp's word on who the player is, so
 * each one lists the player under the UUID Warp authenticated, the one the mock Mojang vouched for,
 * rather than the one it would derive from the name ({@link FORWARDED_PLAYER} has one of its own).
 */
async function forwarding(ctx) {
  const name = FORWARDED_PLAYER;
  const expected = mojangUuid(name);
  const bot = await joinWarp(ctx, name);
  try {
    if (undashed(bot.uuid) !== expected) throw new Error(`Warp logged ${name} in as ${bot.uuid}, not ${expected} from the mock Mojang`);
    const servers = ['lobby'];
    await expectListedAs(bot, 0, expected, 'lobby');
    if (has(ctx, 'switching')) {
      const listed = bot.listedAs.length;
      await switchToSurvival(bot);
      await expectListedAs(bot, listed, expected, 'survival');
      servers.push('survival');
    }
    bot.healthy();
    return `${servers.join(' and ')} know ${name} as ${expected}, the UUID from the mock Mojang`;
  } finally {
    bot.quit();
  }
}

/**
 * Checks the `index`-th UUID a backend listed the bot under (its Player Info entry), against
 * `expected` (32 hex digits).
 */
async function expectListedAs(bot, index, expected, server) {
  const uuid = undashed(await bot.waitForEntry('listedAs', index, `${server} listing ${bot.username}`, 10_000));
  if (uuid === expected) return;
  const own = uuid === offlineUuid(bot.username) ? ', the UUID it derives from the name: it took no forwarded identity' : '';
  throw new Error(`${server} knows ${bot.username} as ${uuid}, not ${expected}${own}`);
}

/** A UUID as 32 hex digits: packets carry them dashed. */
const undashed = (uuid) => String(uuid).replaceAll('-', '');

/**
 * The backend's brand reaches the player through Warp, on joining and after a switch. Both backends
 * run the same server, so their brands read the same. Survival's is told apart by when it arrives:
 * in the configuration phase the switch opens (from 1.20.2), or once survival has put the player in
 * adventure mode (before). A brand from the lobby arriving after the switch fails the scenario.
 */
async function brand(ctx) {
  const expected = BRANDS[ctx.entry.server.type];
  const bot = await joinWarp(ctx, 'e2e_brand');
  try {
    const lobby = await bot.waitForEntry('brands', 0, 'the lobby brand', 10_000);
    checkBrand(lobby, expected, 'lobby');
    let detail = `"${lobby.brand}" from lobby`;
    if (has(ctx, 'switching')) {
      const before = bot.brands.length;
      await switchToSurvival(bot);
      await bot.waitForEntry('brands', before, 'the survival brand', 10_000);
      for (const received of bot.brands.slice(before)) {
        if (!fromSurvival(received)) {
          throw new Error(`brand "${received.brand}" arrived after the switch but from lobby (${received.state}, game mode ${received.gameMode})`);
        }
        checkBrand(received, expected, 'survival');
      }
      detail += ', then from survival after a switch';
    }
    bot.healthy();
    return detail;
  } finally {
    bot.quit();
  }
}

/**
 * Whether a brand the bot received after asking to switch to survival is survival's: it arrived in
 * the configuration phase of the switch, or once the bot was in survival's game mode.
 * @param {{state: string, gameMode: string|undefined}} received an entry of the bot's `brands`
 */
export function fromSurvival({ state, gameMode }) {
  return state === 'configuration' || gameMode === SURVIVAL_MODE;
}

function checkBrand({ brand }, expected, server) {
  if (!expected.test(brand)) throw new Error(`${server} brand "${brand}" does not match ${expected}`);
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
  const signed = has(ctx, 'signedChat') && ctx.variant.online;
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

/**
 * Chat between two players on the lobby, which only the backend delivers: a chat line, which it
 * shows every player, and a `/tell`, a command Warp does not own. Each bot first lists the other, so
 * both are in the lobby's player list, and the line must also come back to its sender: one the lobby
 * never sent anyone fails apart from one that only missed the other player.
 */
async function playerChat(ctx) {
  const bots = [];
  try {
    for (const name of ['e2e_chat_a', 'e2e_chat_b']) bots.push(await joinWarp(ctx, name));
    const [alice, bob] = bots;
    await waitForListed(alice, [bob.username]);
    await waitForListed(bob, [alice.username]);
    const from = bob.messages.length;
    const line = `e2e ${token()}`;
    await alice.say(line);
    await bob.waitForMessage(new RegExp(line), `${alice.username}'s chat line`, 10_000, from);
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

/** First protocols where the client: has boss bars (1.9), plays a sound following an entity by its registry id (1.14), by its name (1.19.3), configures (1.20.2), holds several packs by UUID (1.20.3). */
const BOSS_BARS = 107;
const ENTITY_SOUNDS = 477;
const NAMED_ENTITY_SOUNDS = 761;
const CONFIGURATION = 764;
/** The first protocol with a command list (Declare Commands): 1.13. */
const COMMANDS_PACKET = 393;
const MULTIPLE_PACKS = 765;

/**
 * A permission granted, then taken, while the player stays on its server: the probe plugin's
 * /e2egrant flips the permission /e2eperm requires and tells Warp, which must send the command list
 * again with /e2eperm, then without it (Velocity #352: the list stays stale until a switch).
 */
async function commandRefresh(ctx) {
  const bot = await joinWarp(ctx, 'e2e_commands');
  // Whether each command list from here on offers /e2eperm. The name is in the packet as is,
  // whatever minecraft-protocol makes of the version's argument types.
  const lists = [];
  bot.bot._client.on('raw.declare_commands', (buffer) => lists.push(buffer.includes('e2eperm')));
  try {
    let from = lists.length;
    await bot.command('e2egrant', /^e2egrant on$/);
    await bot.waitFor(() => lists.slice(from).includes(true), 'a command list offering /e2eperm once granted', 10_000);
    from = lists.length;
    await bot.command('e2egrant', /^e2egrant off$/);
    await bot.waitFor(() => lists.length > from && !lists.at(-1), 'a command list without /e2eperm once taken', 10_000);
    bot.healthy();
    return `command list re-sent with /e2eperm once granted, without it once taken (${lists.length} lists)`;
  } finally {
    bot.quit();
  }
}

/** What the audience probe plugin shows: its bar, and the sound both its plays name. */
const PROBE_BAR = 'Warp e2e';
const PROBE_SOUND = /entity\.experience_orb\.pickup$/;

/**
 * The id of the probe's sound in a client's registry, 1.14 to 1.19.2: read from Mojang's data
 * generator reports through the samples tools/sound-events.js writes for Warp's tests, never from
 * the table Warp loads.
 */
function probeSoundId(protocol) {
  const samples = readFileSync(new URL('../../protocol/src/test/resources/dev/warp/protocol/packet/play/sound-event-samples.txt', import.meta.url), 'utf8');
  const line = samples.split('\n').find((l) => l.startsWith(`${protocol} id entity.experience_orb.pickup `));
  if (!line) throw new Error(`no sound event sample for protocol ${protocol}`);
  return Number(line.split(' ')[3]);
}

/**
 * A plugin of Warp's own (e2e/plugin) shows the player a boss bar, plays it a sound at a position
 * and one that follows it, and sends the proxy's resource pack: the bot receives each as its version
 * has it (no boss bar before 1.9, the sound following the player by its registry id from 1.14, by its
 * name from 1.19.3, not at all before 1.14), and the plugin
 * hears the pack's statuses. After a switch the bar is still on the bot's screen, whose model clears
 * it in the configuration phase from 1.20.2 as the vanilla client does, the plugin's pack is not sent
 * again, and from 1.20.3 the lobby's own pack is removed, the next server not sending it. The bar is
 * animated ten times a second for the whole run: an update the bot gets for a bar it does not have
 * fails the run, as it disconnects a vanilla client.
 */
async function audience(ctx) {
  // What the bot receives is in its own version: the backends' in most runs, another one when Warp
  // translates (--client), the plugin's packets going straight to the bot.
  const protocol = (ctx.clientEntry ?? ctx.entry).protocol;
  const bot = await joinWarp(ctx, 'e2e_audience');
  const removed = [];
  bot.bot._client.on('remove_resource_pack', (data) => removed.push(String(data.uuid ?? 'all')));
  try {
    // The lobby's own offer first: before 1.20.2 it comes in play, where the bot answers it itself;
    // a client holds one pack then, and Warp sends the next once the previous one is answered.
    await bot.waitForEntry('resourcePacks', 0, "the lobby's resource pack", 10_000);
    if (protocol < CONFIGURATION) bot.bot.acceptResourcePack();

    await bot.command('audience', /^audience shown$/);
    const details = [];
    if (protocol >= BOSS_BARS) {
      await bot.waitFor(() => bot.bossBars.names().includes(PROBE_BAR), 'the boss bar shown', 10_000);
      details.push('boss bar shown');
    }
    await bot.waitFor(() => bot.sounds.some((s) => s.packet !== 'entity_sound_effect' && PROBE_SOUND.test(String(s.sound))), 'the sound played at a position', 10_000);
    details.push('positioned sound played');
    if (protocol >= NAMED_ENTITY_SOUNDS) {
      await bot.waitFor(() => bot.sounds.some((s) => s.packet === 'entity_sound_effect' && PROBE_SOUND.test(String(s.sound))), 'the sound following the player', 10_000);
      details.push('sound following the player played');
    } else if (protocol >= ENTITY_SOUNDS) {
      const id = probeSoundId(protocol);
      await bot.waitFor(() => bot.sounds.some((s) => s.packet === 'entity_sound_effect' && s.sound === id), `the sound following the player (registry id ${id})`, 10_000);
      details.push(`sound following the player played by registry id ${id}`);
    } else if (bot.sounds.some((s) => s.packet === 'entity_sound_effect')) {
      throw new Error('a sound following the player reached a client before 1.14');
    }
    const offer = await bot.waitFor(() => bot.resourcePacks.some((p) => p.url === ctx.packs.proxy.url), "the plugin's resource pack", 10_000)
      .then(() => bot.resourcePacks.find((p) => p.url === ctx.packs.proxy.url));
    if (offer.hash !== ctx.packs.proxy.sha1 || (offer.id !== undefined && offer.id !== ctx.packs.proxy.id)) {
      throw new Error(`the plugin's pack came as ${offer.url} (SHA-1 ${offer.hash}, id ${offer.id}), expected SHA-1 ${ctx.packs.proxy.sha1}, id ${ctx.packs.proxy.id}`);
    }
    const from = bot.messages.length;
    bot.bot.acceptResourcePack();
    await bot.waitForMessage(/^audience pack ACCEPTED$/, 'the ACCEPTED status at the plugin', 10_000, from);
    await bot.waitForMessage(/^audience pack SUCCESSFULLY_LOADED$/, 'the SUCCESSFULLY_LOADED status at the plugin', 10_000, from);
    details.push('pack statuses heard by the plugin');
    if (protocol < BOSS_BARS && bot.bossBars.added > 0) throw new Error('a boss bar reached a client before 1.9, which has no boss bar packet');

    if (has(ctx, 'switching')) {
      const offers = bot.resourcePacks.length;
      await switchToSurvival(bot);
      await bot.waitFor(() => bot.resourcePacks.slice(offers).some((p) => p.url === ctx.packs.survival.url), "survival's resource pack", 10_000);
      if (protocol < CONFIGURATION) bot.bot.acceptResourcePack();
      if (protocol >= BOSS_BARS) {
        await bot.waitFor(() => bot.bossBars.names().includes(PROBE_BAR), 'the boss bar after the switch', 10_000);
        await sleep(1_500); // updates keep coming, never for a bar the bot does not have
        details.push(`boss bar on screen after a switch${protocol >= CONFIGURATION ? ' (shown again after the configuration phase)' : ''}`);
      }
      // Once: kept by the client from 1.20.3, replaced by survival's own before (1.20.2 unloads it
      // in the configuration phase, and gets it again only from a server that sends none).
      const sent = bot.resourcePacks.filter((p) => p.url === ctx.packs.proxy.url).length;
      if (sent !== 1) throw new Error(`the plugin's pack was sent ${sent} times, expected once`);
      if (protocol >= MULTIPLE_PACKS) {
        // The lobby's pack belongs to the lobby: removed once survival had its time to send it.
        await bot.waitFor(() => removed.includes(ctx.packs.lobby.id), "the lobby's pack removed after the switch", 20_000);
        if (removed.includes(ctx.packs.proxy.id) || removed.includes('all')) throw new Error(`packs removed: ${removed.join(', ')}; the plugin's must stay`);
        details.push("plugin's pack kept, the lobby's removed");
      } else {
        details.push("plugin's pack not sent again");
      }
    }
    bot.healthy();
    return details.join(', ');
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

/**
 * After a switch the tab list shows the new server's players, none of the previous server's. Before
 * 1.20.2 the client keeps its tab list across the new server's Join Game, so Warp removes what the
 * previous server listed (by UUID, by name on 1.7); from 1.20.2 the configuration phase clears it.
 * A witness stays on the lobby: listed there, it must be gone once the other bot is on survival.
 */
async function tabList(ctx) {
  const witness = await joinWarp(ctx, 'e2e_tab_witness');
  try {
    const bot = await joinWarp(ctx, 'e2e_tab_mover');
    try {
      const onLobby = await waitForListed(bot, [witness.username, bot.username]);
      await bot.command('server survival');
      await bot.waitForGameMode(SURVIVAL_MODE, 20_000);
      // Warp removes the leftovers before the new server's Join Game, which comes before its own
      // entries: once survival has listed the bot, anything else from the lobby is a leftover.
      const onSurvival = await waitForListed(bot, [bot.username]);
      if (onSurvival.includes(witness.username)) {
        throw new Error(`the tab list on survival still lists ${witness.username}, a lobby player: ${onSurvival.join(', ')}`);
      }
      witness.healthy();
      bot.healthy();
      return `lobby listed ${onLobby.join(', ')}; survival lists ${onSurvival.join(', ')}`;
    } finally {
      bot.quit();
    }
  } finally {
    witness.quit();
  }
}

/** Waits until every name in `names` is in the bot's tab list, and returns all it lists. */
async function waitForListed(bot, names, timeoutMs = 10_000) {
  try {
    await bot.waitFor(() => names.every((name) => bot.listed().includes(name)), `${names.join(' and ')} in the tab list`, timeoutMs);
  } catch (e) {
    throw new Error(`${e.message}; it lists ${bot.listed().join(', ') || 'nobody'}`);
  }
  return bot.listed();
}

/** How far apart the crowd's switches go on a server with the `concurrentLogins` quirk. */
const STAGGERED_LOGINS_MS = 50;

/**
 * What the harness does differently on a server with each quirk (versions.json `server.quirks`,
 * each with the reason and its issue), for the reports.
 */
export const QUIRKS = {
  concurrentLogins: `the crowd's switches go ${STAGGERED_LOGINS_MS} ms apart instead of at once`,
};

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
    if (!ctx.direct) detail += `, ${await checkCrowdCount(ctx, count)}`;
    if (has(ctx, 'switching')) {
      // Half of them switch server at the same moment, unless the server cannot log them in at once.
      const switchers = bots.slice(0, Math.ceil(count / 2));
      const stagger = ctx.entry.server.quirks?.concurrentLogins ? STAGGERED_LOGINS_MS : 0;
      for (const bot of switchers) {
        bot.command('server survival');
        if (stagger) await sleep(stagger);
      }
      await Promise.all(switchers.map((bot) => bot.waitForGameMode(SURVIVAL_MODE, 30_000)));
      await sleep(2_000);
      for (const bot of bots) bot.healthy();
      const stayers = bots.slice(switchers.length);
      checkStayed(stayers);
      const how = stagger ? `${stagger} ms apart (server quirk concurrentLogins)` : 'at once';
      detail += `, ${switchers.length} switched ${how}, ${checkTabLists(switchers, stayers.map((bot) => bot.username))}`;
    }
    const packets = bots.reduce((sum, bot) => sum + bot.stats.packets, 0);
    const chunks = bots.reduce((sum, bot) => sum + bot.stats.chunks, 0);
    return `${detail}; ${packets} packets / ${chunks} chunks parsed`;
  } finally {
    for (const bot of bots) bot.quit();
  }
}

/**
 * Checks that the server list counts the crowd (and the players of other scenarios still online)
 * and lists a sample of them, as Warp's default sample size allows.
 */
async function checkCrowdCount(ctx, count) {
  const response = await ctx.client.ping({ host: '127.0.0.1', port: ctx.warp.port });
  const players = response?.players;
  if (!Number.isInteger(players?.online) || players.online < count) {
    throw new Error(`the server list counts ${JSON.stringify(players)} with ${count} bots online`);
  }
  const sample = players.sample ?? [];
  if (sample.length === 0 || sample.length > 12) throw new Error(`a sample of ${sample.length} players with ${players.online} online`);
  return `the server list counts ${players.online} online and lists ${sample.length}`;
}

/** Waits until a Warp instance counts no player, so that a scenario starts with it empty. */
async function waitForEmpty(ctx, port, timeoutMs = 10_000) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const response = await ctx.client.ping({ host: '127.0.0.1', port });
    if (response?.players?.online === 0) return;
    if (Date.now() > deadline) throw new Error(`still ${JSON.stringify(response?.players)} after ${timeoutMs / 1000} s`);
    await sleep(250);
  }
}

async function playerLimit(ctx) {
  // warp-alt lets one player in (player-limit = 1, shown as the maximum); the next one is refused.
  await waitForEmpty(ctx, ctx.warpAlt.port);
  const first = await joinWarp(ctx, 'e2e_limit_in', ctx.warpAlt);
  try {
    const status = await ctx.client.ping({ host: '127.0.0.1', port: ctx.warpAlt.port });
    if (status?.players?.online !== 1 || status?.players?.max !== ALT_MAX_PLAYERS) {
      throw new Error(`warp-alt answers ${JSON.stringify(status?.players)} with one player in, for a limit of ${ALT_MAX_PLAYERS}`);
    }
    const refused = await joinWarp(ctx, 'e2e_limit_out', ctx.warpAlt).then(
      (bot) => {
        bot.quit();
        return null;
      },
      (e) => e,
    );
    if (refused === null) throw new Error(`a second player got in past player-limit = ${ALT_MAX_PLAYERS}`);
    if (!/The server is full\./.test(refused.message)) throw new Error(`the second player was refused for another reason: ${refused.message}`);
    first.healthy();
    return `one player in (the server list shows 1/${ALT_MAX_PLAYERS}), the next refused: "The server is full."`;
  } finally {
    first.quit();
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
 * A /server to a server that refuses the player (an empty whitelist, like a full server) leaves it
 * where it was: it keeps playing on the lobby and is told why. Warp logs the player in to the new
 * server before it leaves the current one, as Velocity does.
 */
async function switchRefused(ctx) {
  await ctx.survival.command('whitelist on', /white-?list/i);
  try {
    const bot = await joinWarp(ctx, 'e2e_switch_no');
    try {
      await bot.waitForChunks(20, 30_000, 0);
      await bot.command('server survival', /Could not connect to survival/);
      await sleep(1_000);
      await bot.waitForGameMode(LOBBY_MODE, 5_000);
      await proxyReportsServer(ctx, bot, 'lobby');
      bot.healthy();
      return 'survival refused the switch: player stayed on lobby';
    } finally {
      bot.quit();
    }
  } finally {
    await ctx.survival.command('whitelist off', /white-?list/i);
  }
}

/**
 * Warp's administration commands, typed by a bot the e2e plugin grants every `warp.command.*`
 * permission (`e2e_admin`): `/glist` counts the lobby and lists its players by name, `/send` moves
 * another bot to survival through Warp's normal connection path and answers on one line, a player
 * already there is not moved, an unknown player or server is named, and `/send <server>` brings
 * everyone back. The other bot holds no permission: its `/glist` goes to its backend, which does not
 * know it, rather than to Warp.
 */
async function adminCommands(ctx) {
  const admin = await joinWarp(ctx, 'e2e_admin');
  try {
    const sent = await joinWarp(ctx, 'e2e_sent');
    try {
      await admin.waitForChunks(20, 30_000, 0);
      await sent.waitForChunks(20, 30_000, 0);
      const overview = await admin.command('glist', /players? online/);
      if (!/^- lobby \d+$/m.test(overview)) throw new Error(`/glist does not count the lobby: ${overview}`);
      const names = await admin.command('glist lobby', /^lobby · \d+ players?/);
      for (const name of ['e2e_admin', 'e2e_sent']) {
        if (!names.includes(name)) throw new Error(`/glist lobby does not list ${name}: ${names}`);
      }
      await admin.command('send e2e_sent survival', /^Sent e2e_sent to survival\./);
      await sent.waitForGameMode(SURVIVAL_MODE, 20_000);
      await admin.command('send e2e_sent survival', /^Nothing to do: 1 player is already on survival\./);
      await admin.command('send e2e_nobody survival', /^No player or server is named e2e_nobody\./);
      await admin.command('send all nowhere', /^Unknown server: nowhere/);
      const back = await admin.command('send survival lobby', /^Sent \d+ of \d+ from survival to lobby/);
      await sent.waitForGameMode(LOBBY_MODE, 20_000);
      await sent.command('glist', /unknown (or incomplete )?command/i);
      await sleep(500);
      admin.healthy();
      sent.healthy();
      return `/glist and /send answered (${back.trim()}); /glist without permission reached the backend`;
    } finally {
      sent.quit();
    }
  } finally {
    admin.quit();
  }
}

/**
 * The BungeeCord plugin messaging channel, as thousands of Paper plugins speak it: the lobby's probe
 * plugin (plugins/bungee-probe) sends requests through the bot's connection with
 * `Player#sendPluginMessage` on `BungeeCord` (Bukkit's `bungeecord:main` from 1.13), which Paper
 * only sends once the channel is registered: Warp registers it on the client's behalf. The plugin
 * checks the queries' replies (GetServers, PlayerCount, GetServer), then a Connect moves the bot
 * to survival, where the same plugin learns it is now on survival.
 */
async function bungeeCord(ctx) {
  const name = 'e2e_bungee';
  const bot = await joinWarp(ctx, name);
  try {
    await bot.waitForChunks(20, 30_000, 0);
    await bot.waitForGameMode(LOBBY_MODE, 5_000);
    const [servers] = await probe(ctx.lobby, name, 'GetServers');
    if (servers !== 'lobby, survival') throw new Error(`GetServers answered '${servers}', not 'lobby, survival'`);
    const [counted, count] = await probe(ctx.lobby, name, 'PlayerCount', 'lobby');
    if (counted !== 'lobby' || !(Number(count) >= 1)) throw new Error(`PlayerCount lobby answered ${counted} ${count}`);
    const [onLobby] = await probe(ctx.lobby, name, 'GetServer');
    if (onLobby !== 'lobby') throw new Error(`GetServer answered '${onLobby}' on the lobby`);

    await ctx.lobby.command(`bungeeprobe ${name} Connect survival`, /\[BungeeProbe\] sent Connect/);
    await bot.waitForGameMode(SURVIVAL_MODE, 20_000);
    await proxyReportsServer(ctx, bot, 'survival');
    const [onSurvival] = await probe(ctx.survival, name, 'GetServer');
    if (onSurvival !== 'survival') throw new Error(`GetServer answered '${onSurvival}' on survival`);
    bot.healthy();
    return `GetServers, PlayerCount (${count}) and GetServer answered; Connect moved the player to survival`;
  } finally {
    bot.quit();
  }
}

/**
 * Has a backend's probe plugin send a BungeeCord sub-channel through a player, and returns the
 * fields of the reply it logs.
 */
async function probe(backend, player, subchannel, ...args) {
  const request = [subchannel, ...args].join(' ');
  const reply = new RegExp(`\\[BungeeProbe\\] (reply ${subchannel} .*|no player .*)$`);
  const line = await backend.command(`bungeeprobe ${player} ${request}`, reply, 10_000);
  const match = new RegExp(`reply ${subchannel} (.*) on \\S+$`).exec(line ?? '');
  if (!match) throw new Error(`${backend.name}: ${line}`);
  return match[1].split('|');
}

/**
 * Transfers, as a proxy drained for a restart sends its players to the next one: the lobby sends a
 * player away with its own Transfer packet, which must reach the client through Warp exactly as
 * sent; the client comes back with a transfer handshake, which Warp lets in (`accepts-transfers` is
 * on for warp) onto the lobby. warp-alt, where it is off, refuses such a player with vanilla's
 * translated reason.
 */
async function transfer(ctx) {
  await expectTransferRefused(ctx, 'e2e_tr_refused');

  const bot = await joinWarp(ctx, 'e2e_transfer');
  let sent;
  try {
    const received = new Promise((resolve) => bot.bot._client.once('transfer', resolve));
    await ctx.lobby.command(`transfer 127.0.0.1 ${ctx.warp.port} e2e_transfer`, /Transferring/i);
    sent = await Promise.race([received, sleep(10_000).then(() => null)]);
    if (sent === null) throw new Error('the lobby transferred the player, but no Transfer packet reached it');
    if (sent.host !== '127.0.0.1' || sent.port !== ctx.warp.port) {
      throw new Error(`Transfer packet altered: ${JSON.stringify(sent)}, sent to 127.0.0.1:${ctx.warp.port}`);
    }
  } finally {
    await bot.quit();
  }

  const back = await joinWarp(ctx, 'e2e_transfer', ctx.warp, null, true);
  try {
    await back.waitForGameMode(LOBBY_MODE, 10_000);
    await proxyReportsServer(ctx, back, 'lobby');
    back.healthy();
    return `lobby's Transfer to ${sent.host}:${sent.port} reached the player as sent; it came back by transfer onto the lobby; warp-alt refused one with ${TRANSFERS_DISABLED}`;
  } finally {
    back.quit();
  }
}

/** Joins warp-alt, whose `accepts-transfers` is off, by transfer, and expects vanilla's refusal. */
async function expectTransferRefused(ctx, username) {
  let bot;
  try {
    bot = await joinWarp(ctx, username, ctx.warpAlt, null, true);
  } catch (e) {
    if (String(e.message).includes(`"translate":"${TRANSFERS_DISABLED}"`)) return;
    throw new Error(`expected warp-alt to refuse a transfer with ${TRANSFERS_DISABLED}, got: ${e.message}`);
  }
  bot.quit();
  throw new Error('warp-alt let a transferred player in with accepts-transfers off');
}

/** The bearer token of warp-drain's management server. */
const DRAIN_TOKEN = 'e2e-drain-token';

/**
 * A drain, as a rolling update asks for one: a Warp of its own, warp-drain, with its management
 * server and HAProxy agent check, draining towards warp (which accepts transfers). From 1.20.5,
 * SIGTERM drains it, as Kubernetes would with `drain.on-sigterm`: readiness and the agent check
 * say so at once, then the player gets a Transfer packet to warp, comes back there by transfer,
 * and warp-drain, empty, exits with 0. Before, a client cannot be transferred: `POST /drain?wait`
 * keeps the player until it leaves, then answers, and warp-drain exits with 0.
 */
async function drain(ctx) {
  const base = ctx.warp.instance;
  const name = `warp-drain-${ctx.variant.name}`;
  const instance = new Warp({
    ...base,
    name,
    dir: join(dirname(base.dir), name),
    port: ctx.ports.drain,
    acceptsTransfers: false,
    jvmArgs: [],
    management: { http: ctx.ports.drainHttp, agent: ctx.ports.drainAgent, token: DRAIN_TOKEN },
    drain: {
      'transfer-to': `127.0.0.1:${ctx.warp.port}`,
      'on-sigterm': true,
      'propagation-delay': '2s',
      'transfer-interval': '100ms',
      timeout: '1m',
    },
  });
  instance.prepare();
  await instance.start();
  let detail;
  try {
    detail = has(ctx, 'transfers') ? await drainBySigterm(ctx, instance) : await drainByHttp(ctx, instance);
  } finally {
    await instance.stop();
  }
  const failures = instance.failures();
  if (failures.length) throw new Error(`${name}: ${failures[0]}`);
  return detail;
}

async function drainBySigterm(ctx, instance) {
  const bot = await joinWarp(ctx, 'e2e_drained', { port: instance.port });
  let sent;
  try {
    await bot.waitForGameMode(LOBBY_MODE, 20_000);
    await expectReadiness(instance, 200, 'up ready');
    const received = new Promise((resolve) => bot.bot._client.once('transfer', resolve));
    instance.process.signal('SIGTERM');
    await instance.process.waitFor(/Drain started by SIGTERM/, 5_000);
    // Within the propagation delay: load balancers are told before anyone moves.
    await expectReadiness(instance, 503, 'drain');
    sent = await Promise.race([received, sleep(15_000).then(() => null)]);
    if (sent === null) throw new Error('warp-drain drained, but no Transfer packet reached the player');
    if (sent.host !== '127.0.0.1' || sent.port !== ctx.warp.port) {
      throw new Error(`Transfer to ${sent.host}:${sent.port}, expected warp at 127.0.0.1:${ctx.warp.port}`);
    }
  } finally {
    // As a vanilla client does on a Transfer packet.
    await bot.quit();
  }
  const back = await joinWarp(ctx, 'e2e_drained', ctx.warp, null, true);
  try {
    await back.waitForGameMode(LOBBY_MODE, 20_000);
    back.healthy();
  } finally {
    back.quit();
  }
  await expectExit(instance, /Drain complete after .*: 1 transferred, 0 kept/);
  return 'SIGTERM: readiness 503 and agent "drain" at once; the player was transferred to warp and came back by transfer; warp-drain exited with 0';
}

async function drainByHttp(ctx, instance) {
  const url = `http://127.0.0.1:${instance.management.http}/drain`;
  const bot = await joinWarp(ctx, 'e2e_kept', { port: instance.port });
  let answer;
  try {
    await bot.waitForGameMode(LOBBY_MODE, 20_000);
    await expectReadiness(instance, 200, 'up ready');
    const refused = await fetch(url, { method: 'POST', headers: { Authorization: 'Bearer wrong' } });
    if (refused.status !== 401) throw new Error(`POST /drain with a wrong token answered ${refused.status}, expected 401`);
    answer = fetch(`${url}?wait=true`, { method: 'POST', headers: { Authorization: `Bearer ${DRAIN_TOKEN}` } });
    await instance.process.waitFor(/Drain started by POST \/drain/, 5_000);
    await expectReadiness(instance, 503, 'drain');
    // Past the propagation delay and the transfer phase: the player is kept, never kicked.
    await sleep(4_000);
    const status = await (await fetch(url)).json();
    if (status.phase !== 'waiting' || status.kept !== 1 || status.players !== 1) {
      throw new Error(`expected the player kept in phase 2, got ${JSON.stringify(status)}`);
    }
    bot.healthy();
  } finally {
    await bot.quit();
  }
  const response = await answer;
  const body = await response.json();
  if (response.status !== 200 || body.outcome !== 'drained') {
    throw new Error(`POST /drain?wait answered ${response.status} ${JSON.stringify(body)}, expected 200 drained`);
  }
  await expectExit(instance, /Drain complete after .*: 0 transferred, 1 kept/);
  return 'POST /drain?wait: the player, whose client cannot be transferred, was kept until it left; then the drain answered "drained" and warp-drain exited with 0';
}

/** Checks warp-drain's readiness probe and HAProxy agent check. */
async function expectReadiness(instance, status, agent) {
  const response = await fetch(`http://127.0.0.1:${instance.management.http}/readyz`);
  const body = await response.json();
  if (response.status !== status) throw new Error(`/readyz answered ${response.status} ${JSON.stringify(body)}, expected ${status}`);
  const reply = await agentCheck(instance.management.agent);
  if (reply !== agent) throw new Error(`the agent check answered "${reply}", expected "${agent}"`);
}

/** Reads the HAProxy agent check's one line, as HAProxy does. */
function agentCheck(port) {
  return new Promise((resolve, reject) => {
    const socket = tcpConnect(port, '127.0.0.1');
    let data = '';
    socket.setEncoding('ascii');
    socket.setTimeout(5_000, () => {
      socket.destroy();
      reject(new Error('the agent check did not answer'));
    });
    socket.on('data', (chunk) => { data += chunk; });
    socket.on('end', () => resolve(data.trim()));
    socket.on('error', reject);
  });
}

/** Waits for warp-drain to stop by itself with 0, having logged `summary`. */
async function expectExit(instance, summary) {
  const exit = await instance.exitedWithin(30_000);
  if (exit.code !== 0) throw new Error(`warp-drain exited with ${exit.code ?? exit.signal}, expected 0 once empty`);
  if (!instance.process.lines.some((line) => summary.test(line))) throw new Error(`warp-drain never logged ${summary}`);
  if (!instance.process.lines.some((line) => /Exiting with code 0/.test(line))) throw new Error('warp-drain never logged its exit code');
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
      uuid: bad.uuid ?? mojangUuid(username),
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

/** Scenarios in execution order. `requires` names one of the {@link features}: without it, the scenario is skipped. */
export const SCENARIOS = [
  { name: 'status', run: status },
  { name: 'login', run: login },
  { name: 'forwarding', run: forwarding, requires: 'forwarding' },
  { name: 'keepalive', background: true },
  { name: 'brand', run: brand },
  { name: 'chat', run: chat, requires: 'proxy' },
  { name: 'player-chat', run: playerChat },
  { name: 'resource-pack', run: resourcePack, requires: 'resourcePack' },
  { name: 'audience', run: audience, requires: 'audience' },
  { name: 'command-refresh', run: commandRefresh, requires: 'commandRefresh' },
  { name: 'switching', run: switching, requires: 'switching' },
  { name: 'tab-list', run: tabList, requires: 'switching' },
  { name: 'profile-key', run: profileKey, requires: 'profileKeys' },
  { name: 'crowd', run: crowd },
  { name: 'fallback-unreachable', run: fallbackUnreachable, requires: 'proxy' },
  { name: 'fallback-rejected', run: fallbackRejected, requires: 'proxy' },
  { name: 'switch-refused', run: switchRefused, requires: 'switching' },
  { name: 'admin-commands', run: adminCommands, requires: 'adminCommands' },
  { name: 'bungeecord', run: bungeeCord, requires: 'bungeeChannel' },
  { name: 'transfer', run: transfer, requires: 'transfers' },
  { name: 'drain', run: drain, requires: 'drain' },
  { name: 'player-limit', run: playerLimit, requires: 'proxy' },
  { name: 'flood', run: (ctx) => flood(ctx, joinWarp, LOBBY_MODE), requires: 'proxy' },
];
