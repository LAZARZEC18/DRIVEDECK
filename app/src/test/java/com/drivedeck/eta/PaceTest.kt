package com.drivedeck.eta

import com.drivedeck.data.Drive
import com.drivedeck.live.LiveData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PaceTest {
    private fun d(i: Int, waze: Double?, actual: Double?) = Drive("d$i", i * 1000L, i * 1000L + 1, 1000.0, 1, 1.0, wazeMin = waze, wazeActualMin = actual)

    @Test fun `needs two comparable drives`() {
        assertNull(Pace.factor(listOf(d(1, 20.0, 22.0))))
        assertNull(Pace.factor(listOf(d(1, 3.0, 5.0), d(2, 20.0, 22.0)))) // too short to count
    }

    @Test fun `median ratio ignores cancelled routes and describes itself`() {
        val f = Pace.factor(listOf(d(1, 20.0, 22.0), d(2, 10.0, 11.2), d(3, 30.0, 33.0), d(4, 20.0, 5.0)))!!
        assertEquals(3, f.samples) // the 5-minute "arrival" on a 20-minute route was a cancel
        assertEquals(1.1, f.factor, 0.001)
        assertEquals("you usually take 10% longer than Waze", f.describe)
        assertEquals(22.0, Pace.yourMinutes(20, f)!!, 0.001)
        assertEquals("you're usually 20% quicker than Waze", Pace.Factor(0.8, 4).describe)
    }

    @Test fun `live snapshot survives the trip between the two apps`() {
        val live = LiveData(true, 62.4, 41.0, 88.0, 12.4, -31.9, 115.9, "Waze", "8:42 am", 23, 1.1, 5, "Red-light camera", 600.0)
        val back = LiveData.fromJson(live.toJson())
        assertEquals(live, back)
        assertEquals(25.3, back.yourMinutes!!, 0.01)
        assertEquals(LiveData.EMPTY, LiveData.fromJson(LiveData.EMPTY.toJson()))
    }
}
