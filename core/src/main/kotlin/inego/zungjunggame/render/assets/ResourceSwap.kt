package inego.zungjunggame.render.assets

/**
 * All-or-nothing replacement of a set of resources.
 *
 * [build] creates the whole replacement set, reporting every resource it creates
 * through `track` as it goes. Only after [build] completes does [publish] run
 * with the replacement, and then every resource in [previous] is disposed —
 * unless [disposePrevious] is false. If [build] throws part-way, every tracked
 * resource is disposed and the exception is rethrown, so [previous] is left
 * untouched and published.
 *
 * This is the rollback [TextureRecovery] cannot supply: an owner that disposed
 * its old set before rebuilding would strand every consumer on a dead GL handle.
 * An unmanaged texture cannot be re-uploaded in place, so a successful swap
 * **replaces** the instances and the caller must re-derive anything built from
 * them.
 *
 * ### `disposePrevious = false` after a GPU context change
 *
 * The previous set is disposable only while the GL context that created it is
 * alive. After a context loss its names are dead, but the **new** context issues
 * names from the bottom of its own sequence, so a name in [previous] may already
 * have been reissued — to this replacement set, to another owner's texture, or
 * to the glyph atlas. Calling `glDeleteTextures` on it deletes that live
 * resource, and every handle still holding the name silently draws the wrong
 * image (the close icon rendering as a mahjong tile was one such case).
 *
 * The old context already destroyed its objects, so dropping the references is
 * the only retire that cannot delete a live texture. Recovery owners therefore
 * pass false; the live-swap default stays true.
 *
 * GL-free by construction: the resource type is opaque and only [dispose] —
 * supplied by the owner that knows how to release it — touches it.
 */
internal fun <R> buildThenSwap(
    previous: List<R>,
    dispose: (R) -> Unit,
    disposePrevious: Boolean = true,
    build: (track: (R) -> R) -> List<R>,
    publish: (List<R>) -> Unit,
) {
    val partial = ArrayList<R>()
    val replacement = try {
        build { value ->
            partial += value
            value
        }
    } catch (t: Throwable) {
        partial.forEach(dispose)
        throw t
    }
    publish(replacement)
    if (disposePrevious) previous.forEach(dispose)
}
