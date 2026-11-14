package inego.zungjunggame.state

/**
 * Versioned binary codec for a persisted value type.
 *
 * [migrations] upgrades payload bytes one version at a time; a missing migration
 * makes the stored value unrecoverable and the key falls back to its default.
 */
interface Schema<S : Any> {
    val version: Int
    fun encode(value: S): ByteArray
    fun decode(bytes: ByteArray): S?
    /** Upgrades payload bytes from `fromVersion` to `fromVersion + 1`. */
    val migrations: List<Migration> get() = emptyList()
}

/** Outcome of a single [Migration] step. */
sealed interface MigrationResult {
    data class Migrated(val bytes: ByteArray) : MigrationResult
    data object Drop : MigrationResult
}

/** One version-to-version payload transform. */
fun interface Migration {
    fun migrate(fromVersion: Int, bytes: ByteArray): MigrationResult
}
