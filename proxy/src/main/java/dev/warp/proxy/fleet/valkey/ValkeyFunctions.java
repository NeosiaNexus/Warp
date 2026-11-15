/*
 * Copyright (C) 2026 Warp Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package dev.warp.proxy.fleet.valkey;

import java.util.List;

/**
 * The atomic operations on the store's keys, as Valkey functions (library {@value #LIBRARY}) and as
 * plain scripts for Redis 6.2, which has no functions. Every key of a network shares its hash tag,
 * {@code {warp:<network>}}, so that a cluster keeps the network on one slot and the scripts may
 * touch any of its keys.
 *
 * <p>Keys: {@code node:<id>} (hash, expires), {@code node:<id>:players} (set), {@code nodes} (set),
 * {@code player:<uuid>} (hash), {@code name:<lowercase name>} (string), {@code inc:<id>} (counter).
 */
final class ValkeyFunctions {

  static final String LIBRARY = "warp_fleet";

  private ValkeyFunctions() {}

  private static final String HELPERS =
      """
      local function owner_alive(prefix, node, inc)
        local alive = redis.call('HGET', prefix .. 'node:' .. node, 'inc')
        return alive and alive == inc
      end
      local function forget_name(prefix, name, uuid)
        if name then
          local nk = prefix .. 'name:' .. string.lower(name)
          if redis.call('GET', nk) == uuid then redis.call('DEL', nk) end
        end
      end
      """;

  /** {@code claim prefix uuid name node inc server since}: CLAIMED, or OWNED node inc. */
  static final String CLAIM =
      """
      local function warp_claim(keys, args)
        local prefix, uuid, name, node, inc, server, since =
          args[1], args[2], args[3], args[4], args[5], args[6], args[7]
        local p = prefix .. 'player:' .. uuid
        local cur = redis.call('HMGET', p, 'node', 'inc', 'name')
        if cur[1] then
          if owner_alive(prefix, cur[1], cur[2]) then return {'OWNED', cur[1], cur[2]} end
          redis.call('SREM', prefix .. 'node:' .. cur[1] .. ':players', uuid)
          forget_name(prefix, cur[3], uuid)
        end
        redis.call('HSET', p, 'name', name, 'node', node, 'inc', inc, 'server', server, 'since', since)
        redis.call('SET', prefix .. 'name:' .. string.lower(name), uuid)
        redis.call('SADD', prefix .. 'node:' .. node .. ':players', uuid)
        return {'CLAIMED'}
      end
      """;

  /** {@code release prefix uuid node inc}: 1 when the record was the caller's and is gone. */
  static final String RELEASE =
      """
      local function warp_release(keys, args)
        local prefix, uuid, node, inc = args[1], args[2], args[3], args[4]
        local p = prefix .. 'player:' .. uuid
        local cur = redis.call('HMGET', p, 'node', 'inc', 'name')
        if cur[1] == node and cur[2] == inc then
          redis.call('DEL', p)
          forget_name(prefix, cur[3], uuid)
          redis.call('SREM', prefix .. 'node:' .. node .. ':players', uuid)
          return 1
        end
        return 0
      end
      """;

  /** {@code move prefix uuid node inc server}: 1 when the record was the caller's and changed. */
  static final String MOVE =
      """
      local function warp_move(keys, args)
        local prefix, uuid, node, inc, server = args[1], args[2], args[3], args[4], args[5]
        local p = prefix .. 'player:' .. uuid
        local cur = redis.call('HMGET', p, 'node', 'inc')
        if cur[1] == node and cur[2] == inc then
          redis.call('HSET', p, 'server', server)
          return 1
        end
        return 0
      end
      """;

  /** {@code reap prefix node inc}: the player records of that incarnation removed, counted. */
  static final String REAP =
      """
      local function warp_reap(keys, args)
        local prefix, node, inc = args[1], args[2], args[3]
        local set = prefix .. 'node:' .. node .. ':players'
        local count = 0
        for _, uuid in ipairs(redis.call('SMEMBERS', set)) do
          local p = prefix .. 'player:' .. uuid
          local cur = redis.call('HMGET', p, 'node', 'inc', 'name')
          if cur[1] == node and cur[2] == inc then
            redis.call('DEL', p)
            forget_name(prefix, cur[3], uuid)
            redis.call('SREM', set, uuid)
            count = count + 1
          elseif not cur[1] or cur[1] ~= node then
            redis.call('SREM', set, uuid)
          end
        end
        local hb = prefix .. 'node:' .. node
        if redis.call('HGET', hb, 'inc') == inc then redis.call('DEL', hb) end
        if redis.call('EXISTS', hb) == 0 then
          redis.call('SREM', prefix .. 'nodes', node)
          if redis.call('SCARD', set) == 0 then redis.call('DEL', set) end
        end
        return count
      end
      """;

  /** The function names, in the order of {@link #BODIES}. */
  static final List<String> NAMES = List.of("warp_claim", "warp_release", "warp_move", "warp_reap");

  static final List<String> BODIES = List.of(CLAIM, RELEASE, MOVE, REAP);

  /**
   * Returns the library for {@code FUNCTION LOAD REPLACE}.
   *
   * @return the Lua source
   */
  static String library() {
    StringBuilder lua =
        new StringBuilder("#!lua name=").append(LIBRARY).append('\n').append(HELPERS);
    for (String body : BODIES) {
      lua.append(body);
    }
    for (String name : NAMES) {
      lua.append("redis.register_function('").append(name).append("', ").append(name).append(")\n");
    }
    return lua.toString();
  }

  /**
   * Returns one function as a standalone script for {@code SCRIPT LOAD} and {@code EVALSHA}.
   *
   * @param index the function's index in {@link #NAMES}
   * @return the Lua source
   */
  static String script(int index) {
    return HELPERS + BODIES.get(index) + "return " + NAMES.get(index) + "(KEYS, ARGV)\n";
  }
}
