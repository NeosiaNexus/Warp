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
package dev.warp.api.fleet.spi;

import dev.warp.api.fleet.NodeState;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * A node's heartbeat, as written to the store.
 *
 * @param id the node id
 * @param incarnation the incarnation of this start of the node
 * @param publicAddress where players are transferred to, or {@code null} when not configured
 * @param state ready or draining; never {@link NodeState#DOWN}, a down node writes no heartbeat
 * @param players how many players the node carries
 * @param version the Warp version the node runs
 * @param started when the node started, on its clock
 */
public record NodeRecord(
    String id,
    long incarnation,
    @Nullable String publicAddress,
    NodeState state,
    int players,
    String version,
    Instant started) {}
