/**
 * Permissions: what a player may do, decided by permission plugins.
 *
 * <p>The proxy decides nothing itself: without a provider, a player has no permission. A permission
 * plugin (LuckPerms-style) registers a {@link dev.warp.api.permission.PermissionProvider}; when
 * several are registered, the highest priority that has an opinion wins.
 */
@NullMarked
package dev.warp.api.permission;

import org.jspecify.annotations.NullMarked;
