package inego.zungjunggame.state

import kotlin.test.Test
import kotlin.test.assertNotEquals

/**
 * On desktop the preferences container is a *file* at `<prefs dir>/<name>` and
 * the file store is a directory at the same depth. Sharing the name makes the
 * directory impossible to create, so every file-backed key fails to persist.
 */
class StateContainerNamesTest {

    @Test
    fun thePreferencesContainerAndTheFileDirectoryDiffer() {
        assertNotEquals(PreferencesStateBackend.STATE_NAME, FileStateBackend.STATE_DIR)
    }
}
