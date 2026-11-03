/**
 * Warp's version numbers. `version.txt` holds the last release, written by release-please; only
 * the release workflow builds that number. Every other build is a snapshot of the next number, so
 * that no build but the release carries a released number, and a snapshot sorts after the release
 * it follows and before the next one, in Maven's and Gradle's version ordering alike.
 */
object WarpVersions {

    private const val SNAPSHOT = "-SNAPSHOT"
    private val LAST_NUMBER = Regex("(\\d+)(?!.*\\d)")

    /**
     * Returns the snapshot version that follows a release: its last number incremented, plus
     * `-SNAPSHOT`, such as `0.1.0-beta.6-SNAPSHOT` after `0.1.0-beta.5` and `1.0.1-SNAPSHOT` after
     * `1.0.0`. The smallest step keeps the snapshot below whatever release comes next (`0.1.0-beta.6`,
     * `0.1.0` or `0.2.0-beta.1` after `0.1.0-beta.5`).
     */
    fun nextSnapshot(release: String): String {
        require(!release.endsWith(SNAPSHOT)) { "version.txt must hold a release, not $release" }
        val last = LAST_NUMBER.find(release) ?: error("version.txt holds no number: $release")
        val next = last.value.toBigInteger().inc().toString()
        return release.replaceRange(last.range, next) + SNAPSHOT
    }
}
