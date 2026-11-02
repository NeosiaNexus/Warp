/**
 * Native libraries (libdeflate, for packet compression) bound through the FFM API, with their
 * loading, self-test and per-thread contexts. Callers check {@link dev.warp.jni.Natives} and fall
 * back to the JDK's implementations.
 */
@NullMarked
package dev.warp.jni;

import org.jspecify.annotations.NullMarked;
