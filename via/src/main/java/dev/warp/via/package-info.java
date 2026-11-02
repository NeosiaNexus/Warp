/**
 * ViaVersion and ViaBackwards as a Warp plugin: players of any version join backends of any other.
 *
 * <p>Translation happens on the backend leg, as ViaVersion's Velocity platform does it, but through
 * Warp's network pipeline API instead of reflection, and only where the player's and the backend's
 * versions differ. Every other connection keeps Warp's blind forwarding and compression
 * passthrough.
 */
@NullMarked
package dev.warp.via;

import org.jspecify.annotations.NullMarked;
