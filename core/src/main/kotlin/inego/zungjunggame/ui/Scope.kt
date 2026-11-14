package inego.zungjunggame.ui

import com.badlogic.gdx.utils.Disposable

/**
 * A lifetime: every [Disposable] constructed through [own] is released when this
 * scope or any ancestor closes — children first, then this scope's own resources
 * in reverse construction order.
 *
 * Ownership follows construction (`docs/architecture/widgets-and-state.md`): a
 * disposable's owner is the scope that built it, so "who disposes X" is answered
 * by the construction site and "when does X end" by the scope. No component,
 * presenter, or widget declares a disposal of its own; the composition root holds
 * the root scope ([root]), and the [inego.zungjunggame.ui.stack.Router] holds one
 * child scope per route entry.
 *
 * Closing is idempotent and terminal: [own] and [child] fail fast after the
 * scope has closed, because acquiring a resource after its lifetime ended is a
 * bug to surface, not a leak to tolerate.
 */
class Scope private constructor(
    val name: String,
    private val parent: Scope?,
) : Disposable {
    private class Owned(val resource: Disposable)

    private val resources = ArrayList<Owned>()
    private val children = ArrayList<Scope>()

    var isClosed: Boolean = false
        private set

    init {
        parent?.children?.add(this)
    }

    /** Constructs [create] and makes this scope responsible for disposing it. */
    fun <T : Disposable> own(what: String, create: () -> T): T {
        check(!isClosed) { "scope $name is closed; cannot own $what" }
        val resource = create()
        resources += Owned(resource)
        return resource
    }

    /** A nested lifetime that closes with this scope, or earlier. */
    fun child(what: String): Scope {
        check(!isClosed) { "scope $name is closed; cannot create child $what" }
        return Scope(what, this)
    }

    override fun dispose() {
        if (isClosed) return
        isClosed = true
        // Copy before closing: a child removes itself from [children] while
        // closing, and the parent must not iterate a list it is mutating.
        val closing = children.toList()
        children.clear()
        for (i in closing.indices.reversed()) closing[i].dispose()
        for (i in resources.indices.reversed()) resources[i].resource.dispose()
        resources.clear()
        parent?.children?.remove(this)
    }

    companion object {
        /** A scope with no parent: the composition root's lifetime. */
        fun root(name: String): Scope = Scope(name, null)
    }
}
