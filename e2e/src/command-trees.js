// The command tree data Warp's protocol tests check its commands codec against, from Mojang's own
// server jars: the argument types each release knows (their registry ids from 1.19), the vanilla
// command tree as the data generator reports it, and the commands packet as a vanilla server really
// sends it to an operator. tools/command-trees.js writes them as test fixtures.
import { mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { createConnection } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { download } from './download.js';
import { findJava } from './java.js';
import { offlineUuid } from './mojang.js';
import { ManagedProcess, run } from './proc.js';

/** The first protocol whose argument types are a registry, sent as ids: 1.19. */
export const REGISTRY_SINCE = 759;

/** The first protocol with a commands packet: 1.13. */
export const COMMANDS_SINCE = 393;

/** The first protocol whose server jar is a bundler (the server and its libraries inside): 1.18. */
const BUNDLER_SINCE = 757;

/** The operator whose command tree is captured: an offline player, opped at level 4. */
const PLAYER = 'warp_commands';

/** How long a vanilla server may take to boot a fresh flat world. */
const BOOT_TIMEOUT_MS = 300_000;

/** How long the capture waits for the commands packet once the player is in. */
const CAPTURE_TIMEOUT_MS = 60_000;

// ---------------------------------------------------------------------------
// Data generator
// ---------------------------------------------------------------------------

/**
 * Runs the data generator of a release's server jar with `--reports` and returns what the command
 * tests need: the argument types registry (1.19+, `registries.json`) and the command tree
 * (`commands.json`).
 * @returns {Promise<{argumentTypes: string[]|null, commands: object}>} `argumentTypes` in id order
 */
export async function generateReports(release, jar, java, protocol) {
  const workDir = mkdtempSync(join(tmpdir(), 'warp-command-reports-'));
  try {
    // Before 1.18 the jar is the server itself; from 1.18 a bundler unpacks it and runs
    // `bundlerMainClass` (here the data generator) instead.
    const args =
      protocol >= BUNDLER_SINCE
        ? ['-DbundlerMainClass=net.minecraft.data.Main', '-jar', jar, '--reports', '--output', 'generated']
        : ['-cp', jar, 'net.minecraft.data.Main', '--reports', '--output', 'generated'];
    await run(java, args, { cwd: workDir });
    const reports = join(workDir, 'generated', 'reports');
    const commands = JSON.parse(readFileSync(join(reports, 'commands.json'), 'utf8'));
    let argumentTypes = null;
    if (protocol >= REGISTRY_SINCE) {
      const registries = JSON.parse(readFileSync(join(reports, 'registries.json'), 'utf8'));
      argumentTypes = registryInIdOrder(registries['minecraft:command_argument_type'], release.id);
    }
    return { argumentTypes, commands };
  } finally {
    rmSync(workDir, { recursive: true, force: true });
  }
}

/** A registry of `registries.json` (`{entries: {name: {protocol_id}}}`) as names in id order. */
export function registryInIdOrder(registry, version) {
  if (!registry?.entries) throw new Error(`${version}: registries.json has no minecraft:command_argument_type`);
  const names = [];
  for (const [name, entry] of Object.entries(registry.entries)) {
    const id = entry?.protocol_id;
    if (!Number.isInteger(id) || id < 0 || names[id] !== undefined) throw new Error(`${version}: ${name} has a bad or duplicate id ${id}`);
    names[id] = name;
  }
  if (names.some((name) => name === undefined) || names.length !== Object.keys(registry.entries).length) {
    throw new Error(`${version}: the argument type ids are not dense`);
  }
  return names;
}

// ---------------------------------------------------------------------------
// Argument types before 1.19, from the server's own code
// ---------------------------------------------------------------------------

/**
 * Argument types registered only when the game runs in Mojang's IDE (`SharedConstants
 * .IS_RUNNING_IN_IDE`): a released server never sends them, and no report lists them from 1.19.
 */
const IDE_ONLY = new Set(['minecraft:test_argument', 'minecraft:test_class']);

/**
 * The argument types a release from 1.13 to 1.18.2 registers, read from the bytecode of the
 * classes that register them: before 1.19 they were no registry, so no report lists them. Each
 * registration passes the type's name, then its class (`register("entity", EntityArgument.class,
 * ...)`), so a name is the last string constant a method loads before a class constant. Brigadier's
 * types are registered under their full name (`brigadier:bool`), the game's under a name
 * `minecraft:` is prefixed to, or already carries.
 * @returns {Promise<string[]>} the identifiers, in registration order
 */
export async function registeredArgumentTypes(release, jar, protocol) {
  const workDir = mkdtempSync(join(tmpdir(), 'warp-argument-types-'));
  try {
    let serverJar = jar;
    if (protocol >= BUNDLER_SINCE) {
      const inner = `META-INF/versions/${release.id}/server-${release.id}.jar`;
      await run('unzip', ['-q', '-o', jar, inner, '-d', workDir]);
      serverJar = join(workDir, inner);
    }
    const classes = join(workDir, 'classes');
    await run('unzip', ['-q', '-o', serverJar, '*.class', '-x', 'com/*', 'it/*', 'io/*', 'org/*', 'javax/*', '-d', classes]);
    const registrations = [];
    for (const file of walk(classes)) {
      const names = registeredNames(readFileSync(file));
      const ids = names.map((name) => (name.includes(':') ? name : `minecraft:${name}`));
      if (ids.includes('brigadier:bool') || ids.includes('minecraft:game_profile')) registrations.push(ids);
    }
    // Brigadier's types first, then the game's, as the game registers them.
    registrations.sort((a, b) => Number(b.includes('brigadier:bool')) - Number(a.includes('brigadier:bool')));
    const identifiers = [];
    for (const id of registrations.flat()) {
      if (!IDE_ONLY.has(id) && !identifiers.includes(id)) identifiers.push(id);
    }
    if (!identifiers.includes('brigadier:bool') || !identifiers.includes('minecraft:entity')) {
      throw new Error(`${release.id}: found no argument type registrations in the server's code`);
    }
    return identifiers;
  } finally {
    rmSync(workDir, { recursive: true, force: true });
  }
}

function* walk(dir) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) yield* walk(path);
    else if (entry.name.endsWith('.class')) yield path;
  }
}

/**
 * The names a class file registers: in each method, the last string constant loaded before each
 * class constant, when it looks like an identifier.
 */
export function registeredNames(bytes) {
  if (bytes.length < 10 || bytes.readUInt32BE(0) !== 0xcafebabe) return [];
  const { pool, end } = constantPool(bytes);
  const names = [];
  let at = end + 6; // access flags, this class, super class
  at += 2 + 2 * bytes.readUInt16BE(at); // interfaces
  at = skipMembers(bytes, at, pool, null); // fields
  skipMembers(bytes, at, pool, (code) => {
    let lastString = null;
    for (const { opcode, operand } of instructions(code)) {
      if (opcode !== 0x12 && opcode !== 0x13) continue; // ldc, ldc_w
      const constant = pool[operand];
      if (constant?.tag === 8) {
        lastString = constant.value;
      } else if (constant?.tag === 7 && lastString !== null) {
        if (/^[a-z0-9_]+(:[a-z0-9_/]+)?$/.test(lastString)) names.push(lastString);
        lastString = null;
      }
    }
  });
  return names;
}

/** Walks the fields or methods at `at`, handing each Code attribute's bytecode to `onCode`. */
function skipMembers(bytes, at, pool, onCode) {
  const count = bytes.readUInt16BE(at);
  at += 2;
  for (let i = 0; i < count; i++) {
    at += 6; // access flags, name, descriptor
    const attributes = bytes.readUInt16BE(at);
    at += 2;
    for (let j = 0; j < attributes; j++) {
      const name = pool[bytes.readUInt16BE(at)]?.value;
      const length = bytes.readUInt32BE(at + 2);
      if (onCode && name === 'Code') {
        const codeLength = bytes.readUInt32BE(at + 10);
        onCode(bytes.subarray(at + 14, at + 14 + codeLength));
      }
      at += 6 + length;
    }
  }
  return at;
}

/** A class file's constant pool: index → {tag, value} (value: the text of Utf8 and String). */
function constantPool(bytes) {
  const count = bytes.readUInt16BE(8);
  const pool = [];
  const stringRefs = [];
  let at = 10;
  for (let i = 1; i < count; i++) {
    const tag = bytes[at];
    switch (tag) {
      case 1: {
        const length = bytes.readUInt16BE(at + 1);
        pool[i] = { tag, value: bytes.toString('utf8', at + 3, at + 3 + length) };
        at += 3 + length;
        break;
      }
      case 8:
        pool[i] = { tag };
        stringRefs.push([i, bytes.readUInt16BE(at + 1)]);
        at += 3;
        break;
      case 7: case 16: case 19: case 20:
        pool[i] = { tag };
        at += 3;
        break;
      case 15:
        pool[i] = { tag };
        at += 4;
        break;
      case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18:
        pool[i] = { tag };
        at += 5;
        break;
      case 5: case 6:
        pool[i] = { tag };
        at += 9;
        i++; // longs and doubles take two entries
        break;
      default:
        throw new Error(`unknown constant pool tag ${tag}`);
    }
  }
  for (const [index, utf8] of stringRefs) pool[index].value = pool[utf8]?.value;
  return { pool, end: at };
}

/** The instructions of a method's bytecode, with the constant index of ldc and ldc_w. */
function* instructions(code) {
  let pc = 0;
  while (pc < code.length) {
    const opcode = code[pc];
    let length;
    if (opcode === 0x12) length = 2;
    else if (opcode === 0x13) length = 3;
    else if (opcode === 0xaa) {
      const base = pc + 1 + ((4 - ((pc + 1) % 4)) % 4);
      const low = code.readInt32BE(base + 4);
      const high = code.readInt32BE(base + 8);
      length = base - pc + 12 + 4 * (high - low + 1);
    } else if (opcode === 0xab) {
      const base = pc + 1 + ((4 - ((pc + 1) % 4)) % 4);
      length = base - pc + 8 + 8 * code.readInt32BE(base + 4);
    } else if (opcode === 0xc4) length = code[pc + 1] === 0x84 ? 6 : 4;
    else length = OPCODE_LENGTHS[opcode] ?? 1;
    const operand = opcode === 0x12 ? code[pc + 1] : opcode === 0x13 ? code.readUInt16BE(pc + 1) : -1;
    yield { opcode, operand };
    pc += length;
  }
}

/** The length of every fixed-length opcode longer than one byte. */
const OPCODE_LENGTHS = (() => {
  const lengths = [];
  const set = (length, ...opcodes) => opcodes.forEach((o) => { lengths[o] = length; });
  set(2, 0x10, 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39, 0x3a, 0xa9, 0xbc);
  set(3, 0x11, 0x14, 0x84, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xbb, 0xbd, 0xc0, 0xc1, 0xc6, 0xc7);
  for (let o = 0x99; o <= 0xa8; o++) set(3, o);
  set(4, 0xc5);
  set(5, 0xb9, 0xba, 0xc8, 0xc9);
  return lengths;
})();

// ---------------------------------------------------------------------------
// Live capture
// ---------------------------------------------------------------------------

/**
 * Boots a vanilla server of a release on a fresh flat world, logs an operator in offline and
 * returns the payload (after the packet id) of the first commands packet it receives.
 * @param {{commands: number, login: object, configuration: object|null}} ids packet ids of the
 *   release: see {@link packetIds}
 */
export async function captureCommandsPacket(release, jar, java, protocol, ids, port) {
  const workDir = mkdtempSync(join(tmpdir(), 'warp-command-capture-'));
  const server = new ManagedProcess(`vanilla ${release.id}`, java, ['-Xmx1G', '-jar', jar, 'nogui'], {
    cwd: workDir,
    logFile: join(workDir, 'server.log'),
  });
  try {
    writeFileSync(join(workDir, 'eula.txt'), 'eula=true\n');
    writeFileSync(join(workDir, 'server.properties'), serverProperties(protocol, port));
    const uuid = dashed(offlineUuid(PLAYER));
    writeFileSync(join(workDir, 'ops.json'), JSON.stringify([{ uuid, name: PLAYER, level: 4, bypassesPlayerLimit: false }]));
    server.start();
    await server.waitFor(/Done \(\d+[.,]\d+s\)!/, BOOT_TIMEOUT_MS);
    return await loginAndCapture(port, protocol, ids);
  } finally {
    await server.stop({ command: 'stop', graceMs: 30_000 });
    rmSync(workDir, { recursive: true, force: true });
  }
}

function serverProperties(protocol, port) {
  const flat = protocol >= 759 ? 'minecraft\\:flat' : 'flat';
  return [
    `server-port=${port}`,
    'server-ip=127.0.0.1',
    'online-mode=false',
    'enforce-secure-profile=false',
    'network-compression-threshold=-1',
    `level-type=${flat}`,
    'generate-structures=false',
    'spawn-monsters=false',
    'spawn-animals=false',
    'spawn-npcs=false',
    'allow-nether=false',
    'view-distance=2',
    'simulation-distance=2',
    'spawn-protection=0',
    'op-permission-level=4',
    'enable-command-block=true',
    '',
  ].join('\n');
}

const dashed = (hex) => `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;

/** Logs the operator in and resolves with the commands packet's payload. */
function loginAndCapture(port, protocol, ids) {
  return new Promise((resolve, reject) => {
    const socket = createConnection({ host: '127.0.0.1', port });
    let state = 'login';
    let pending = Buffer.alloc(0);
    const timer = setTimeout(() => fail(new Error(`no commands packet within ${CAPTURE_TIMEOUT_MS / 1000}s (state ${state})`)), CAPTURE_TIMEOUT_MS);
    const fail = (e) => {
      clearTimeout(timer);
      socket.destroy();
      reject(e);
    };
    const send = (id, ...fields) => {
      const body = Buffer.concat([varInt(id), ...fields]);
      socket.write(Buffer.concat([varInt(body.length), body]));
    };
    socket.on('error', fail);
    socket.on('close', () => fail(new Error(`the server closed the connection (state ${state})`)));
    socket.on('connect', () => {
      send(0x00, varInt(protocol), string('127.0.0.1'), u16(port), varInt(2));
      send(0x00, ...loginStart(protocol));
    });
    socket.on('data', (chunk) => {
      pending = Buffer.concat([pending, chunk]);
      for (;;) {
        const length = readVarInt(pending, 0);
        if (!length || pending.length < length.next + length.value) return;
        const packet = pending.subarray(length.next, length.next + length.value);
        pending = pending.subarray(length.next + length.value);
        const id = readVarInt(packet, 0);
        const body = packet.subarray(id.next);
        try {
          const payload = onPacket(id.value, body);
          if (payload) {
            clearTimeout(timer);
            socket.removeAllListeners('close');
            socket.destroy();
            resolve(Buffer.from(payload));
            return;
          }
        } catch (e) {
          fail(e);
          return;
        }
      }
    });

    function onPacket(id, body) {
      if (state === 'login') {
        if (id === ids.login.disconnect) throw new Error(`login refused: ${body.toString('utf8')}`);
        if (id === ids.login.compression) throw new Error('the server enabled compression');
        if (id === ids.login.encryption) throw new Error('the server asked for encryption: not offline');
        if (id === ids.login.pluginRequest) {
          send(ids.login.pluginResponse, varInt(readVarInt(body, 0).value), Buffer.from([0]));
        } else if (id === ids.login.success) {
          if (ids.configuration) {
            send(ids.login.acknowledged);
            state = 'configuration';
          } else {
            state = 'play';
          }
        }
        return null;
      }
      if (state === 'configuration') {
        const c = ids.configuration;
        if (id === c.disconnect) throw new Error(`configuration refused: ${body.toString('utf8')}`);
        if (id === c.knownPacks) send(c.knownPacksResponse, body);
        else if (id === c.keepAlive) send(c.keepAliveResponse, body);
        else if (id === c.ping) send(c.pong, body);
        else if (id === c.finish) {
          send(c.finishAck);
          state = 'play';
        }
        return null;
      }
      return id === ids.commands ? body : null;
    }
  });
}

/** The login start fields of a protocol, for an offline player. */
function loginStart(protocol) {
  const name = string(PLAYER);
  if (protocol < 759) return [name];
  if (protocol === 759) return [name, Buffer.from([0])]; // no signature data
  if (protocol === 760) return [name, Buffer.from([0]), Buffer.from([0])]; // no signature, no UUID
  if (protocol < 764) return [name, Buffer.from([0])]; // no UUID
  return [name, Buffer.from(offlineUuid(PLAYER), 'hex')];
}

/**
 * The ids of the packets the capture exchanges, at one protocol: from Mojang's packet report of
 * that protocol when there is one, else from minecraft-data.
 * @param {(state: string, direction: string, mojang: string, mcData: string) => number|undefined} id
 */
export function packetIds(protocol, id) {
  const need = (state, direction, mojang, mcData) => {
    const value = id(state, direction, mojang, mcData);
    if (value === undefined) throw new Error(`protocol ${protocol}: no id for ${state} ${direction} ${mojang}`);
    return value;
  };
  const login = {
    disconnect: need('login', 'clientbound', 'minecraft:login_disconnect', 'disconnect'),
    encryption: need('login', 'clientbound', 'minecraft:hello', 'encryption_begin'),
    success: id('login', 'clientbound', 'minecraft:login_finished', 'success') ?? need('login', 'clientbound', 'minecraft:game_profile', 'success'),
    compression: need('login', 'clientbound', 'minecraft:login_compression', 'compress'),
    pluginRequest: need('login', 'clientbound', 'minecraft:custom_query', 'login_plugin_request'),
    pluginResponse: need('login', 'serverbound', 'minecraft:custom_query_answer', 'login_plugin_response'),
    acknowledged: protocol >= 764 ? need('login', 'serverbound', 'minecraft:login_acknowledged', 'login_acknowledged') : null,
  };
  const configuration =
    protocol >= 764
      ? {
          disconnect: need('configuration', 'clientbound', 'minecraft:disconnect', 'disconnect'),
          finish: need('configuration', 'clientbound', 'minecraft:finish_configuration', 'finish_configuration'),
          finishAck: need('configuration', 'serverbound', 'minecraft:finish_configuration', 'finish_configuration'),
          keepAlive: need('configuration', 'clientbound', 'minecraft:keep_alive', 'keep_alive'),
          keepAliveResponse: need('configuration', 'serverbound', 'minecraft:keep_alive', 'keep_alive'),
          ping: need('configuration', 'clientbound', 'minecraft:ping', 'ping'),
          pong: need('configuration', 'serverbound', 'minecraft:pong', 'pong'),
          knownPacks: id('configuration', 'clientbound', 'minecraft:select_known_packs', 'select_known_packs') ?? -1,
          knownPacksResponse: id('configuration', 'serverbound', 'minecraft:select_known_packs', 'select_known_packs') ?? -1,
        }
      : null;
  return { commands: need('play', 'clientbound', 'minecraft:commands', 'declare_commands'), login, configuration };
}

// ---------------------------------------------------------------------------
// Wire helpers
// ---------------------------------------------------------------------------

function varInt(value) {
  const bytes = [];
  let v = value >>> 0;
  do {
    let b = v & 0x7f;
    v >>>= 7;
    if (v !== 0) b |= 0x80;
    bytes.push(b);
  } while (v !== 0);
  return Buffer.from(bytes);
}

function readVarInt(buf, at) {
  let value = 0;
  for (let i = 0; i < 5; i++) {
    if (at + i >= buf.length) return null;
    const b = buf[at + i];
    value |= (b & 0x7f) << (7 * i);
    if ((b & 0x80) === 0) return { value, next: at + i + 1 };
  }
  throw new Error('VarInt too long');
}

const string = (s) => {
  const bytes = Buffer.from(s, 'utf8');
  return Buffer.concat([varInt(bytes.length), bytes]);
};

const u16 = (v) => {
  const b = Buffer.alloc(2);
  b.writeUInt16BE(v);
  return b;
};

// ---------------------------------------------------------------------------
// Server jars
// ---------------------------------------------------------------------------

/** Downloads (or reuses) a release's vanilla server jar, checked against Mojang's sha1. */
export async function fetchServer(release, cacheDir) {
  const server = release.downloads?.server;
  if (!server) throw new Error(`Minecraft ${release.id} has no server jar`);
  const jar = await download(server.url, join(cacheDir, 'downloads', `minecraft-server-${release.id}.jar`), { sha1: server.sha1 });
  const listed = Number(release.javaVersion?.majorVersion ?? 8);
  if (!Number.isInteger(listed) || listed < 8) throw new Error(`Minecraft ${release.id} lists no Java version`);
  // 1.17 lists Java 16, of which Adoptium has no JRE: it runs on 17, as in the e2e matrix.
  const javaMajor = listed === 16 ? 17 : listed;
  return { jar, java: await findJava(javaMajor, cacheDir) };
}

