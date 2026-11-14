package inego.zungjunggame.net

import kotlin.test.Test
import kotlin.test.assertEquals

/** The slider step ↔ stored-hours mapping: one step per allowed digest cadence. */
class NotificationPrefsScaleTest {
    @Test
    fun everyAllowedIntervalRoundTripsThroughItsStep() {
        NotificationPrefsScale.HOURS.forEachIndexed { step, hours ->
            assertEquals(step, NotificationPrefsScale.stepFor(hours))
            assertEquals(hours, NotificationPrefsScale.hoursFor(step))
        }
    }

    @Test
    fun anIntervalBetweenCadencesSnapsToTheNearest() {
        assertEquals(NotificationPrefsScale.HOURS.indexOf(4), NotificationPrefsScale.stepFor(5))
        assertEquals(24, NotificationPrefsScale.coerce(23))
        assertEquals(1, NotificationPrefsScale.coerce(0))
    }

    @Test
    fun stepsClampToTheEnds() {
        assertEquals(1, NotificationPrefsScale.hoursFor(-3))
        assertEquals(24, NotificationPrefsScale.hoursFor(99))
    }
}
