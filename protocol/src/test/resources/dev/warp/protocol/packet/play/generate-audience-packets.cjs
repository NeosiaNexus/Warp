// Generates reference wire bytes for the packets Warp writes or reads for Adventure audiences
// (sounds, boss bars, resource packs, chat deletion, dialogs), with node-minecraft-protocol
// (minecraft-data 3.117.0): an implementation independent of Warp and Velocity. Output: one line
// per packet, "<protocol> <name> <hex>", the packet body without its id; configuration packets are
// named config_*.
//
//   node generate-audience-packets.cjs > audience-packets.txt
//
// with the e2e/ dependencies installed (npm ci in e2e/), or WARP_E2E_NODE_MODULES pointing at them.
'use strict'
const path = require('path')
// The e2e harness's dependencies, nine levels up from this resource directory.
const NM = process.env.WARP_E2E_NODE_MODULES ||
  path.join(__dirname, '..', '..', '..', '..', '..', '..', '..', '..', '..', 'e2e', 'node_modules')
const mc = require(path.join(NM, 'minecraft-protocol'))
const mcData = require(path.join(NM, 'minecraft-data'))

const VERSIONS = ['1.7', '1.8.8', '1.9', '1.9.4', '1.10.2', '1.12.2', '1.13.2', '1.14.4', '1.16.1',
  '1.16.5', '1.17.1', '1.18.2', '1.19', '1.19.2', '1.19.3', '1.19.4', '1.20.1', '1.20.2', '1.20.4',
  '1.20.6', '1.21.1', '1.21.4', '1.21.5', '1.21.6', '1.21.8', '1.21.9', '1.21.11', '26.1']

const PACK = '5c39a8cb-1a3a-4c86-9a47-0f0aaf0e3a01'
const URL = 'https://warp.dev/pack.zip'
const HASH = '0123456789abcdef0123456789abcdef01234567'
const SOUND = 'minecraft:entity.experience_orb.pickup'
const SEED = [0, 42] // protodef i64 as [high, low]
const SOUND_ID = 300
const SIGNATURE = Buffer.alloc(256, 7)

function at (data, version) {
  return data.version['>='](version)
}

function out (protocol, name, buffer) {
  process.stdout.write(`${protocol} ${name} ${buffer.toString('hex')}\n`)
}

function body (serializer, name, params) {
  const buf = serializer.createPacketBuffer({ name, params })
  // Strip the packet id: Warp's codecs read and write the body only.
  let i = 0
  while (buf[i] & 0x80) i++
  return buf.subarray(i + 1)
}

/**
 * A sound written as a holder with its event inlined: node-minecraft-protocol 1.68.0 skips the
 * holder's leading 0 without writing it, into a buffer it does not clear, so it is written here.
 */
function inlined (buffer) {
  buffer[0] = 0
  return buffer
}

/** A component as each version carries it: JSON text, NBT from 1.20.3. */
function text (data, value) {
  return at(data, '1.20.3') ? { type: 'string', value } : JSON.stringify({ text: value })
}

for (const v of VERSIONS) {
  const data = mcData(v)
  const protocol = data.version.version
  const ser = mc.createSerializer({ state: 'play', isServer: true, version: v })
  const cser = mc.createSerializer({ state: 'play', isServer: false, version: v })
  const packets = data.protocol.play.toClient.types.packet[1][0].type[1].mappings
  const has = (name) => Object.values(packets).includes(name)

  // Sounds: positioned and named, at x 1, y 65, z -2 (eighths of a block: 8, 520, -16), source
  // player (7), volume 0.5, pitch 1.5 (94 as the byte before 1.10).
  if (has('named_sound_effect')) {
    out(protocol, 'sound_named', body(ser, 'named_sound_effect', {
      soundName: SOUND, soundCategory: 7, x: 8, y: 520, z: -16, volume: 0.5, pitch: at(data, '1.10') ? 1.5 : 94, seed: SEED
    }))
    // Up to 1.19.2 a sound following an entity is its registry id: 300, two VarInt bytes.
    if (has('entity_sound_effect')) {
      out(protocol, 'sound_entity_id', body(ser, 'entity_sound_effect', {
        soundId: SOUND_ID, soundCategory: 7, entityId: 42, volume: 0.5, pitch: 1.5, seed: SEED
      }))
    }
  } else {
    out(protocol, 'sound_named', inlined(body(ser, 'sound_effect', {
      sound: { data: { soundName: SOUND, fixedRange: undefined } },
      soundCategory: 'player', x: 8, y: 520, z: -16, volume: 0.5, pitch: 1.5, seed: SEED
    })))
    out(protocol, 'sound_entity', inlined(body(ser, 'entity_sound_effect', {
      soundId: 0,
      soundEvent: { resource: SOUND, range: undefined },
      sound: { data: { soundName: SOUND, fixedRange: undefined } },
      soundCategory: 'player', entityId: 42, volume: 0.5, pitch: 1.5, seed: SEED
    })))
  }
  if (has('stop_sound')) {
    out(protocol, 'stop_sound_all', body(ser, 'stop_sound', { flags: 0 }))
    out(protocol, 'stop_sound_named_on_source', body(ser, 'stop_sound', { flags: 3, source: 7, sound: SOUND }))
  }

  if (at(data, '1.9')) {
    const uuid = PACK
    out(protocol, 'boss_bar_add_styled', body(ser, 'boss_bar', {
      entityUUID: uuid, action: 0, title: text(data, 'Boss'), health: 0.25, color: 2, dividers: 3, flags: 5
    }))
    out(protocol, 'boss_bar_progress', body(ser, 'boss_bar', { entityUUID: uuid, action: 2, health: 0.75 }))
    out(protocol, 'boss_bar_name', body(ser, 'boss_bar', { entityUUID: uuid, action: 3, title: text(data, 'Boss') }))
    out(protocol, 'boss_bar_style', body(ser, 'boss_bar', { entityUUID: uuid, action: 4, color: 6, dividers: 4 }))
    out(protocol, 'boss_bar_flags', body(ser, 'boss_bar', { entityUUID: uuid, action: 5, flags: 2 }))
    out(protocol, 'boss_bar_remove_any', body(ser, 'boss_bar', { entityUUID: uuid, action: 1 }))
  }

  // Resource packs: the required pack with a prompt, as each version carries it.
  if (has('resource_pack_send')) {
    out(protocol, 'resource_pack_push', body(ser, 'resource_pack_send', {
      url: URL, hash: HASH, forced: true, promptMessage: text(data, 'Please')
    }))
  }
  if (has('add_resource_pack')) {
    out(protocol, 'resource_pack_push', body(ser, 'add_resource_pack', {
      uuid: PACK, url: URL, hash: HASH, forced: true, promptMessage: text(data, 'Please')
    }))
    out(protocol, 'resource_pack_pop', body(ser, 'remove_resource_pack', { uuid: PACK }))
    out(protocol, 'resource_pack_pop_all', body(ser, 'remove_resource_pack', { uuid: undefined }))
  }
  if (at(data, '1.8')) {
    out(protocol, 'resource_pack_response', body(cser, 'resource_pack_receive', { uuid: PACK, hash: HASH, result: 3 }))
  }

  if (has('hide_message')) {
    out(protocol, 'delete_chat', body(ser, 'hide_message', { id: 0, signature: SIGNATURE }))
  }
  if (has('clear_dialog')) {
    out(protocol, 'clear_dialog', body(ser, 'clear_dialog', {}))
  }

  if (at(data, '1.20.2')) {
    const config = mc.createSerializer({ state: 'configuration', isServer: true, version: v })
    const cconfig = mc.createSerializer({ state: 'configuration', isServer: false, version: v })
    const configPackets = data.protocol.configuration.toClient.types.packet[1][0].type[1].mappings
    const configHas = (name) => Object.values(configPackets).includes(name)
    if (configHas('resource_pack_send')) {
      out(protocol, 'config_resource_pack_push', body(config, 'resource_pack_send', {
        url: URL, hash: HASH, forced: true, promptMessage: text(data, 'Please')
      }))
    }
    if (configHas('add_resource_pack')) {
      out(protocol, 'config_resource_pack_push', body(config, 'add_resource_pack', {
        uuid: PACK, url: URL, hash: HASH, forced: true, promptMessage: text(data, 'Please')
      }))
      out(protocol, 'config_resource_pack_pop', body(config, 'remove_resource_pack', { uuid: PACK }))
    }
    out(protocol, 'config_resource_pack_response', body(cconfig, 'resource_pack_receive', { uuid: PACK, result: 3 }))
    if (configHas('clear_dialog')) {
      out(protocol, 'config_clear_dialog', body(config, 'clear_dialog', {}))
    }
  }
}
