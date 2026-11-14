package inego.zungjunggame.state

import java.nio.ByteBuffer

/**
 * [StateStore] over a [StateBackend], framing each value with a 4-byte
 * big-endian schema version.
 *
 * Reads never throw: missing, short, corrupt, forward-versioned, or
 * unmigratable data yields [StateKey.default].
 *
 * A key marked [StateKey.secret] is sealed by [secretBox] before it reaches the
 * backend, so the stored bytes are not the value:
 *
 * - no box (a platform with no secret storage, e.g. the browser) stores the
 *   value in the clear — that platform's documented behaviour;
 * - a box whose `seal` fails drops the write entirely, and clears whatever the
 *   key held before, so a session that cannot be protected stays memory-only
 *   instead of becoming plaintext — and never leaves the superseded credential
 *   behind, which the next boot would present already burned;
 * - a stored blob the box cannot open reads as missing, so an undecryptable
 *   value degrades to the key's default instead of throwing.
 *
 * A secret key is also **committed**: its write publishes to the container
 * before it returns, even when the runtime's batched flush is far away. The
 * value is a bearer credential whose loss costs the player a sign-in, so it is
 * never allowed to sit in [StateBackend] memory across a kill.
 */
class DefaultStateStore(
    private val backend: StateBackend,
    private val secretBox: SecretBox? = null,
) : StateStore {

    override fun <S : Any> read(key: StateKey<S>): S? =
        try {
            decodeStored(key)
        } catch (_: Exception) {
            key.default()
        }

    override fun <S : Any> write(key: StateKey<S>, value: S) {
        // A credential is never batched; see [commit].
        if (key.secret) {
            commit(key, value)
            return
        }
        backend.put(key.fullName, frame(key, value))
    }

    override fun remove(key: StateKey<*>) {
        // The removal of a credential is as durable as its write.
        if (key.secret) {
            commit(key, null)
            return
        }
        backend.remove(key.fullName)
    }

    override fun contains(key: StateKey<*>): Boolean = backend.get(key.fullName) != null

    override fun clearNamespace(namespace: String) {
        val prefix = "$namespace."
        backend.names().filter { it.startsWith(prefix) }.forEach { backend.remove(it) }
    }

    /**
     * Write [value] or remove the key, publish it, and read it back.
     *
     * The read-back is the stored bytes compared with the bytes we just wrote —
     * not a decoded value, which would need equality the store does not have,
     * and not a read that could be served from the backend's pending map. It
     * runs after the publish, so what it answers is what the container kept.
     *
     * True means exactly "the container holds [value]": a refused seal is false
     * even though the key ends up absent, because the caller asked about *its*
     * value. A `null` value asks the other question — is the key durably gone —
     * and answers that.
     */
    override fun <S : Any> commit(key: StateKey<S>, value: S?): Boolean {
        if (value == null) {
            backend.remove(key.fullName)
            return publish() && backend.get(key.fullName) == null
        }
        val stored = protect(key, frame(key, value))
        if (stored == null) {
            // No durable secret storage right now (locked or missing credential
            // store, failed seal). The value must not become plaintext, and the
            // credential it supersedes must not survive: a burned token on disk
            // is worse than none at all.
            backend.remove(key.fullName)
            publish()
            return false
        }
        backend.put(key.fullName, stored)
        return publish() && backend.get(key.fullName)?.contentEquals(stored) == true
    }

    override fun flush() {
        backend.flush()
    }

    /** Publish staged writes, reporting whether the container accepted them. */
    private fun publish(): Boolean =
        try {
            backend.flush()
            true
        } catch (_: Exception) {
            false
        }

    /** The framed bytes the backend stores for [value]. */
    private fun <S : Any> frame(key: StateKey<S>, value: S): ByteArray {
        val encoded = key.schema.encode(value)
        return ByteBuffer.allocate(4 + encoded.size)
            .putInt(key.schema.version)
            .put(encoded)
            .array()
    }

    /** The bytes to store, or null when a secret value cannot be sealed. */
    private fun protect(key: StateKey<*>, frame: ByteArray): ByteArray? {
        if (!key.secret) return frame
        val box = secretBox ?: return frame
        return box.seal(frame)
    }

    private fun <S : Any> decodeStored(key: StateKey<S>): S? {
        val stored = backend.get(key.fullName) ?: return key.default()
        val raw = if (key.secret && secretBox != null) {
            secretBox.open(stored) ?: return key.default()
        } else {
            stored
        }
        if (raw.size < 4) return key.default()
        var version = ByteBuffer.wrap(raw, 0, 4).int
        var payload = raw.copyOfRange(4, raw.size)
        if (version > key.schema.version) return key.default()
        val migrations = key.schema.migrations
        val baseVersion = key.schema.version - migrations.size
        while (version < key.schema.version) {
            val migration = migrations.getOrNull(version - baseVersion)
                ?: return key.default()
            when (val result = migration.migrate(version, payload)) {
                MigrationResult.Drop -> return key.default()
                is MigrationResult.Migrated -> {
                    payload = result.bytes
                    version++
                }
            }
        }
        return key.schema.decode(payload) ?: key.default()
    }
}
