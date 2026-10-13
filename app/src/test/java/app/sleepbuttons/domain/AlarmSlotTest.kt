package app.sleepbuttons.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmSlotTest {
    private val all = AlarmSlot.entries

    @Test fun requestCodesAreDistinctAcrossFireAndShowIntents() {
        val codes = all.map { it.requestCode } + all.map { it.showRequestCode }
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test fun uriIdsAndNotificationIdsAreDistinct() {
        assertEquals(all.size, all.map { it.uri }.toSet().size)
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertEquals(all.size, all.map { it.notificationId }.toSet().size)
    }

    @Test fun identitiesAreStable() {
        // Changing these would orphan alarms already registered on a device.
        assertEquals(1001, AlarmSlot.NAP_GET_UP.requestCode)
        assertEquals(1002, AlarmSlot.NAP_BACKUP.requestCode)
        assertEquals(2001, AlarmSlot.MELATONIN.requestCode)
        assertEquals("Nap: get up", AlarmSlot.NAP_GET_UP.label)
        assertEquals("Nap: backup", AlarmSlot.NAP_BACKUP.label)
        assertEquals(AlarmSlot.NAP_BACKUP, AlarmSlot.fromId("nap_2"))
    }
}
