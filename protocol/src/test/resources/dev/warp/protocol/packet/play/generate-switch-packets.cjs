// Generates reference wire bytes for the play packets Warp encodes or decodes to switch servers
// before 1.20.2, with node-minecraft-protocol (minecraft-data 3.117.0): an implementation
// independent of Warp and Velocity. Output: one line per packet, "<protocol> <name> <hex>".
'use strict'
const path = require('path')
// The e2e harness's dependencies (npm ci in e2e/), nine levels up from this resource directory.
const NM = path.join(__dirname, '..', '..', '..', '..', '..', '..', '..', '..', '..', 'e2e', 'node_modules')
const mc = require(path.join(NM, 'minecraft-protocol'))
const mcData = require(path.join(NM, 'minecraft-data'))

const VERSIONS = ['1.7', '1.8.8', '1.9', '1.9.4', '1.10.2', '1.11.2', '1.12.2', '1.13.2', '1.14.4', '1.15.2',
  '1.16.1', '1.16.5', '1.17.1', '1.18.2', '1.19', '1.19.2', '1.19.3', '1.19.4', '1.20.1', '1.20.2']

const ENTITY_ID = 42
const SEED = [0x01020304, 0x05060708] // protodef i64 as [high, low]
const OVERWORLD = 0

function registry () {
  // Every NBT tag type, nested lists and compounds: enough to exercise the skipper.
  return {
    type: 'compound',
    name: '',
    value: {
      'minecraft:dimension_type': {
        type: 'compound',
        value: {
          type: { type: 'string', value: 'minecraft:dimension_type' },
          value: {
            type: 'list',
            value: {
              type: 'compound',
              value: [{
                name: { type: 'string', value: 'minecraft:overworld' },
                id: { type: 'int', value: 0 },
                element: { type: 'compound', value: dimensionType().value }
              }]
            }
          }
        }
      },
      bytes: { type: 'byteArray', value: [1, 2, 3] },
      ints: { type: 'intArray', value: [7, 8] },
      longs: { type: 'longArray', value: [[0, 9]] },
      shorts: { type: 'list', value: { type: 'short', value: [4, 5, 6] } },
      empty: { type: 'list', value: { type: 'end', value: [] } },
      nested: { type: 'list', value: { type: 'list', value: [{ type: 'double', value: [0.5] }] } },
      flag: { type: 'short', value: 1 },
      ratio: { type: 'double', value: 1.5 },
      big: { type: 'long', value: [1, 2] }
    }
  }
}

function dimensionType () {
  return {
    type: 'compound',
    name: '',
    value: {
      piglin_safe: { type: 'byte', value: 0 },
      natural: { type: 'byte', value: 1 },
      ambient_light: { type: 'float', value: 0 },
      infiniburn: { type: 'string', value: 'minecraft:infiniburn_overworld' },
      height: { type: 'int', value: 384 }
    }
  }
}

function at (data, version) {
  return data.version['>='](version)
}

function joinGame (v, data) {
  if (!at(data, '1.16')) {
    const p = {
      entityId: ENTITY_ID,
      gameMode: 2,
      dimension: OVERWORLD,
      difficulty: 2,
      maxPlayers: 20,
      levelType: 'default',
      reducedDebugInfo: false,
      viewDistance: 10,
      hashedSeed: SEED,
      enableRespawnScreen: true
    }
    return p
  }
  const p = {
    entityId: ENTITY_ID,
    isHardcore: false,
    gameMode: 2,
    previousGameMode: at(data, '1.17') ? -1 : 255,
    worldNames: ['minecraft:overworld', 'minecraft:the_nether'],
    dimensionCodec: registry(),
    worldName: 'minecraft:overworld',
    hashedSeed: SEED,
    maxPlayers: 20,
    viewDistance: 10,
    simulationDistance: 8,
    reducedDebugInfo: false,
    enableRespawnScreen: true,
    isDebug: false,
    isFlat: true,
    death: { dimensionName: 'minecraft:overworld', location: { x: 1, y: 64, z: -1 } },
    portalCooldown: 3
  }
  if (at(data, '1.16.2') && !at(data, '1.19')) {
    p.dimension = dimensionType()
  } else {
    p.dimension = 'minecraft:overworld'
    p.worldType = 'minecraft:overworld'
  }
  return p
}

function respawn (v, data) {
  if (!at(data, '1.16')) {
    return { dimension: OVERWORLD, difficulty: 2, hashedSeed: SEED, gamemode: 2, levelType: 'default' }
  }
  const p = {
    worldName: 'minecraft:overworld',
    hashedSeed: SEED,
    gamemode: 2,
    previousGamemode: 255,
    isDebug: false,
    isFlat: true,
    copyMetadata: false,
    death: { dimensionName: 'minecraft:overworld', location: { x: 1, y: 64, z: -1 } },
    portalCooldown: 3
  }
  p.dimension = at(data, '1.16.2') && !at(data, '1.19') ? dimensionType() : 'minecraft:overworld'
  return p
}

const UUID_A = '5c39a8cb-1a3a-4c86-9a47-0f0aaf0e3a01'
const UUID_B = '0f3e1b7c-7b5d-4b55-8e0a-2c3d4e5f6a7b'

function out (protocol, name, buffer) {
  process.stdout.write(`${protocol} ${name} ${buffer.toString('hex')}\n`)
}

for (const v of VERSIONS) {
  const data = mcData(v)
  const protocol = data.version.version
  const ser = mc.createSerializer({ state: 'play', isServer: true, version: v })
  const cser = mc.createSerializer({ state: 'play', isServer: false, version: v })
  const body = (s, name, params) => {
    const buf = s.createPacketBuffer({ name, params })
    // Strip the packet id: Warp's codecs read and write the body only.
    let i = 0
    while (buf[i] & 0x80) i++
    return buf.subarray(i + 1)
  }
  const packets = data.protocol.play.toClient.types.packet[1][0].type[1].mappings
  const has = (name) => Object.values(packets).includes(name)

  if (at(data, '1.20.2')) {
    // Opaque from 1.20.2: any body round-trips; take the server's real layout.
    out(protocol, 'join_game', body(ser, 'login', {
      entityId: ENTITY_ID,
      isHardcore: true,
      worldNames: ['minecraft:overworld'],
      maxPlayers: 20,
      viewDistance: 10,
      simulationDistance: 8,
      reducedDebugInfo: false,
      enableRespawnScreen: true,
      doLimitedCrafting: false,
      worldState: { dimension: 'minecraft:overworld', name: 'minecraft:overworld', hashedSeed: SEED, gamemode: 'adventure', previousGamemode: -1, isDebug: false, isFlat: false, death: undefined, portalCooldown: 0 },
      worldType: 'minecraft:overworld',
      worldName: 'minecraft:overworld',
      hashedSeed: SEED,
      gameMode: 2,
      previousGameMode: -1,
      isDebug: false,
      isFlat: false,
      portalCooldown: 0
    }))
    continue
  }

  out(protocol, 'join_game', body(ser, 'login', joinGame(v, data)))
  out(protocol, 'respawn', body(ser, 'respawn', respawn(v, data)))

  if (at(data, '1.8')) {
    out(protocol, 'header_footer_empty', body(ser, 'playerlist_header', { header: '{"text":""}', footer: '{"text":""}' }))
  }
  if (has('title')) {
    const reset = at(data, '1.11') ? 5 : 4
    out(protocol, 'clear_titles_reset', body(ser, 'title', { action: reset }))
    out(protocol, 'clear_titles_hide', body(ser, 'title', { action: reset - 1 }))
  }
  if (has('clear_titles')) {
    out(protocol, 'clear_titles_reset', body(ser, 'clear_titles', { reset: true }))
    out(protocol, 'clear_titles_hide', body(ser, 'clear_titles', { reset: false }))
  }
  if (at(data, '1.9')) {
    out(protocol, 'boss_bar_add', body(ser, 'boss_bar', { entityUUID: UUID_A, action: 0, title: '{"text":"Boss"}', health: 1, color: 1, dividers: 0, flags: 0 }))
    out(protocol, 'boss_bar_health', body(ser, 'boss_bar', { entityUUID: UUID_A, action: 2, health: 0.5 }))
    out(protocol, 'boss_bar_remove', body(ser, 'boss_bar', { entityUUID: UUID_A, action: 1 }))
  }
  if (at(data, '1.8') && !at(data, '1.19.3')) {
    const crypto = at(data, '1.19') ? { timestamp: [0, 1000], publicKey: Buffer.from([1, 2, 3]), signature: Buffer.from([4, 5]) } : undefined
    out(protocol, 'player_info_add', body(ser, 'player_info', {
      action: 'add_player',
      data: [
        { UUID: UUID_A, uuid: UUID_A, name: 'Alice', properties: [{ name: 'textures', value: 'abc', signature: 'sig' }], gamemode: 0, ping: 12, displayName: '{"text":"Alice"}', crypto },
        { UUID: UUID_B, uuid: UUID_B, name: 'Bob', properties: [], gamemode: 1, ping: 3, displayName: undefined, crypto: undefined }
      ]
    }))
    out(protocol, 'player_info_latency', body(ser, 'player_info', { action: 'update_latency', data: [{ UUID: UUID_A, uuid: UUID_A, ping: 40 }] }))
    out(protocol, 'player_info_display_name', body(ser, 'player_info', { action: 'update_display_name', data: [{ UUID: UUID_B, uuid: UUID_B, displayName: '{"text":"B"}' }] }))
    out(protocol, 'player_info_remove', body(ser, 'player_info', { action: 'remove_player', data: [{ UUID: UUID_A, uuid: UUID_A }, { UUID: UUID_B, uuid: UUID_B }] }))
  }
  if (at(data, '1.19.3')) {
    const all = { add_player: true, initialize_chat: true, update_game_mode: true, update_listed: true, update_latency: true, update_display_name: true }
    out(protocol, 'player_info_update_all', body(ser, 'player_info', {
      action: all,
      data: [
        { uuid: UUID_A, player: { name: 'Alice', properties: [{ key: 'textures', name: 'textures', value: 'abc', signature: 'sig' }] }, chatSession: { uuid: UUID_B, publicKey: { expireTime: [0, 1000], keyBytes: Buffer.from([1, 2, 3]), keySignature: Buffer.from([4, 5]) } }, gamemode: 2, listed: 1, latency: 12, displayName: '{"text":"Alice"}' },
        { uuid: UUID_B, player: { name: 'Bob', properties: [] }, chatSession: undefined, gamemode: 0, listed: 0, latency: 0, displayName: undefined }
      ]
    }))
    out(protocol, 'player_info_update_latency', body(ser, 'player_info', { action: { update_latency: true }, data: [{ uuid: UUID_A, latency: 99 }] }))
    out(protocol, 'player_info_remove', body(ser, 'player_remove', { players: [UUID_A, UUID_B] }))
  }
  if (!at(data, '1.19')) {
    out(protocol, 'chat_server', body(cser, 'chat', { message: '/server survival' }))
  }
}
