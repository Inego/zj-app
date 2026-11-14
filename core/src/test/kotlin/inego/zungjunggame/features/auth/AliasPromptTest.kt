package inego.zungjunggame.features.auth

import inego.zungjunggame.input.Intent
import inego.zungjunggame.net.MAX_ALIAS
import inego.zungjunggame.platform.MemoryOverlayTextPrompt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AliasPromptTest {

    @Test
    fun nativeShowIsTheOsDialogSelectedAndOkSubmits() {
        val os = MemoryOverlayTextPrompt(replacesKeyboard = true)
        val emitted = mutableListOf<Intent>()
        val prompt = NativeAliasPrompt(os, emit = { emitted += it })

        prompt.show(AliasPrompt.Kind.ChangeName, "user-k7m4p9q2")

        assertTrue(prompt.isOpen(AliasPrompt.Kind.ChangeName))
        assertFalse(prompt.isOpen(AliasPrompt.Kind.FirstLogin))
        val request = os.lastRequest
        assertEquals("user-k7m4p9q2", request?.text)
        assertEquals(true, request?.selectAll)
        assertEquals(MAX_ALIAS, request?.maxLength)

        os.complete("Alice")
        assertEquals("Alice", prompt.text(AliasPrompt.Kind.ChangeName))
        assertEquals(Intent.SaveDisplayName, emitted.single())
    }

    @Test
    fun nativeCancelAndHideDropTheResult() {
        val os = MemoryOverlayTextPrompt(replacesKeyboard = true)
        val emitted = mutableListOf<Intent>()
        val prompt = NativeAliasPrompt(os, emit = { emitted += it })

        prompt.show(AliasPrompt.Kind.ChangeName, "Alice")
        os.complete(null)
        assertEquals(Intent.CancelDisplayName, emitted.single())

        prompt.show(AliasPrompt.Kind.ChangeName, "Alice")
        prompt.hide(AliasPrompt.Kind.ChangeName)
        assertFalse(prompt.isOpen(AliasPrompt.Kind.ChangeName))
        os.complete("stolen")
        assertEquals(1, emitted.size)
    }

    @Test
    fun nativeFirstLoginMapsSubmitAndBack() {
        val os = MemoryOverlayTextPrompt(replacesKeyboard = true)
        val emitted = mutableListOf<Intent>()
        val prompt = NativeAliasPrompt(os, emit = { emitted += it })

        prompt.show(AliasPrompt.Kind.FirstLogin, "user-k7m4p9q2")
        os.complete("Alice")
        prompt.hide(AliasPrompt.Kind.FirstLogin)
        prompt.show(AliasPrompt.Kind.FirstLogin, "user-k7m4p9q2")
        os.complete(null)
        assertEquals(Intent.FirstLoginSubmit, emitted[0])
        assertEquals(Intent.FirstLoginBack, emitted[1])
        assertEquals(2, emitted.size)
    }

    @Test
    fun nativeErrorReopensTheDialogSelected() {
        val os = MemoryOverlayTextPrompt(replacesKeyboard = true)
        val prompt = NativeAliasPrompt(os, emit = {})

        prompt.show(AliasPrompt.Kind.ChangeName, "Alice")
        os.complete("   ")
        prompt.setError(AliasPrompt.Kind.ChangeName, "required")

        assertEquals(2, os.requestCount)
        assertTrue(prompt.isOpen(AliasPrompt.Kind.ChangeName))
        assertEquals(true, os.lastRequest?.selectAll)
        assertEquals("required", os.lastRequest?.hint)
        assertEquals("   ", os.lastRequest?.text)
    }
}
