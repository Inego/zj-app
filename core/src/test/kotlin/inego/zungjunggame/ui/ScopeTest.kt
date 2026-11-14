package inego.zungjunggame.ui

import com.badlogic.gdx.utils.Disposable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The [Scope] lifetime rules: reverse construction order, children before the
 * parent's own resources, idempotent exactly-once disposal, and terminal closure.
 */
class ScopeTest {

    /** Records each disposal in [log] so ordering and exactly-once are visible. */
    private class Recording(
        private val log: MutableList<String>,
        private val name: String,
    ) : Disposable {
        var disposals = 0
            private set

        override fun dispose() {
            disposals++
            log += name
        }
    }

    @Test
    fun disposesOwnResourcesInReverseConstructionOrder() {
        val log = mutableListOf<String>()
        val scope = Scope.root("app")
        scope.own("first") { Recording(log, "first") }
        scope.own("second") { Recording(log, "second") }
        scope.own("third") { Recording(log, "third") }

        scope.dispose()

        assertEquals(listOf("third", "second", "first"), log)
    }

    @Test
    fun closesChildrenBeforeItsOwnResources() {
        val log = mutableListOf<String>()
        val scope = Scope.root("app")
        scope.own("appFirst") { Recording(log, "appFirst") }
        val child = scope.child("entry")
        child.own("entryFirst") { Recording(log, "entryFirst") }
        child.own("entrySecond") { Recording(log, "entrySecond") }
        scope.own("appSecond") { Recording(log, "appSecond") }

        scope.dispose()

        assertEquals(listOf("entrySecond", "entryFirst", "appSecond", "appFirst"), log)
    }

    @Test
    fun disposeIsIdempotentAndDisposesEachResourceOnce() {
        val log = mutableListOf<String>()
        val scope = Scope.root("app")
        val resource = scope.own("only") { Recording(log, "only") }

        scope.dispose()
        scope.dispose()

        assertEquals(1, resource.disposals)
        assertEquals(listOf("only"), log)
    }

    @Test
    fun ownAfterCloseFailsFast() {
        val scope = Scope.root("app")
        scope.dispose()

        assertFailsWith<IllegalStateException> {
            scope.own("late") { Recording(mutableListOf(), "late") }
        }
    }

    @Test
    fun childAfterCloseFailsFast() {
        val scope = Scope.root("app")
        scope.dispose()

        assertFailsWith<IllegalStateException> { scope.child("late") }
    }

    @Test
    fun earlyChildCloseIsNotRepeatedByTheParent() {
        val log = mutableListOf<String>()
        val scope = Scope.root("app")
        val child = scope.child("entry")
        val resource = child.own("entryResource") { Recording(log, "entryResource") }

        child.dispose()
        scope.dispose()

        assertEquals(1, resource.disposals)
        assertEquals(listOf("entryResource"), log)
    }
}
