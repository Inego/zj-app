package inego.zungjunggame.ui.stack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StackTest {

    private val screen = fakeInstance(Route.Screen("home"))
    private val modal = fakeInstance(
        Route.Modal("confirm"),
        role = LayerRole.Modal,
        blocksAutomation = true,
    )
    private val overlay = fakeInstance(Route.Overlay("banner"), role = LayerRole.Overlay)

    @Test
    fun copiesEntriesImmutably() {
        val source = mutableListOf(screen, modal)
        val stack = Stack(source)
        source.clear()
        assertEquals(listOf(screen, modal), stack.entries)
    }

    @Test
    fun topWhereFindsTopmostMatch() {
        val secondModal = fakeInstance(Route.Modal("second"), role = LayerRole.Modal)
        val stack = Stack(listOf(screen, modal, overlay, secondModal))
        assertSame(secondModal, stack.topWhere { it.role == LayerRole.Modal })
        assertSame(overlay, stack.topWhere { it.role == LayerRole.Overlay })
        assertNull(stack.topWhere { it.role == LayerRole.Tooltip })
    }

    @Test
    fun aggregatesFlags() {
        val blocking = Stack(listOf(screen, modal))
        assertTrue(blocking.anyBlocksAutomation)
        assertTrue(blocking.onFelt, "a card floats on the felt")
        assertFalse(blocking.menuSurface)

        val board = fakeInstance(Route.Layer("local_board"), role = LayerRole.Context)
        assertFalse(
            Stack(listOf(screen, board, modal)).onFelt,
            "a board paints its own surface and supersedes the felt",
        )

        val menu = fakeInstance(Route.Screen("root_menu"), menuSurface = true)
        assertTrue(Stack(listOf(menu)).menuSurface)
        assertFalse(Stack(listOf(menu, board)).menuSurface)
    }

    @Test
    fun rejectsEmpty() {
        assertFailsWith<IllegalArgumentException> { Stack(emptyList()) }
    }

    @Test
    fun rejectsMissingScreen() {
        assertFailsWith<IllegalArgumentException> { Stack(listOf(modal)) }
    }

    @Test
    fun rejectsScreenNotAtFloor() {
        assertFailsWith<IllegalArgumentException> { Stack(listOf(modal, screen)) }
    }

    @Test
    fun rejectsDuplicateKeys() {
        val tooltip = Route.Tooltip("tip")
        assertFailsWith<IllegalArgumentException> {
            Stack(
                listOf(
                    screen,
                    fakeInstance(tooltip, LayerRole.Tooltip),
                    fakeInstance(tooltip, LayerRole.Tooltip),
                ),
            )
        }
    }
}
