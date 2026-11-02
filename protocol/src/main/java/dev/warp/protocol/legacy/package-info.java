/**
 * What clients of Minecraft 1.6 and older send before any framed packet: the legacy server list
 * ping ({@code 0xFE}) and the legacy login ({@code 0x02}), with the kick packet ({@code 0xFF}) that
 * answers both.
 */
@NullMarked
package dev.warp.protocol.legacy;

import org.jspecify.annotations.NullMarked;
