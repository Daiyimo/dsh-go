package io.github.ccch1mneyyy.dsh_app.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A call must not be charged for time the process was not running.
 *
 * The vendor freezer on this phone stops the whole app for 5–100 s at a time; before this, a healthy
 * call answered 「超时」 the moment it thawed (log: `frozen 15548ms`, `frozen 30629ms`).
 */
class SerialWorkerStallTest {
    @Test fun anOrdinaryTickIsNotAStall() {
        // Tick arrived on time, and 80 ms late: both are just scheduling noise.
        assertEquals(0L, SerialWorker.stallGap(1_000, 1_050))
        assertEquals(0L, SerialWorker.stallGap(1_000, 1_080))
    }

    @Test fun aLongGapIsTimeTheCallNeverGot() {
        assertEquals(30_000L, SerialWorker.stallGap(1_000, 31_050))
        assertTrue(SerialWorker.stallGap(1_000, 97_500) > 90_000L)
    }
}
