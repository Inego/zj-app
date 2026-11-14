package inego.zungjunggame.state

/**
 * Platform secret storage: seals a blob so its bytes are not readable at rest.
 *
 * A store *capability*, not a store. [DefaultStateStore] asks for it only for
 * keys marked [StateKey.secret], so the store owns one path for secret values
 * instead of per-platform hierarchies for the auth session alone.
 *
 * [seal] returning null means "no durable secret storage right now" (the OS
 * credential store is locked, missing, or errored). The store then drops the
 * write rather than falling back to plaintext, so the value stays memory-only.
 */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray?

    fun open(sealed: ByteArray): ByteArray?
}

/**
 * The launchers install this before Gdx bootstraps; the composition root asks for
 * it once per boot.
 *
 * `null` means the platform has no secret storage at all — the browser, where a
 * secret value is stored in the clear because there is nowhere to keep a key.
 * A platform whose secret storage exists but is unavailable must return a
 * [SecretBox] whose [SecretBox.seal] fails instead: that distinction is what
 * keeps "no encryption available" from meaning "write the token in plaintext".
 */
object PlatformSecretBox {
    var provider: () -> SecretBox? = { null }

    fun create(): SecretBox? = provider()
}
