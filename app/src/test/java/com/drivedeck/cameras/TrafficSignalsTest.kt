package com.drivedeck.cameras

import com.drivedeck.data.DeckJson
import com.drivedeck.data.DeckState
import com.drivedeck.data.Drive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrafficSignalsTest {
    private val base = Signal(-31.9300, 115.8700)

    @Test fun `posts of one intersection merge into one`() {
        val posts = listOf(
            Signal(-31.93000, 115.87000), Signal(-31.93010, 115.87015), Signal(-31.92995, 115.87020), // same corner
            Signal(-31.93500, 115.87000), // 550 m away: separate
        )
        assertEquals(2, TrafficSignals.cluster(posts).size)
    }

    @Test fun `only lights in front of you and in range`() {
        val north = Signal(base.lat + 0.002, base.lng) // ~220 m north
        val south = Signal(base.lat - 0.002, base.lng)
        val list = listOf(north, south)
        assertEquals(north, TrafficSignals.ahead(list, base.lat, base.lng, 0.0, 300.0)!!.signal)
        assertEquals(south, TrafficSignals.ahead(list, base.lat, base.lng, 180.0, 300.0)!!.signal)
        assertNull(TrafficSignals.ahead(list, base.lat, base.lng, 90.0, 300.0)) // heading east
        assertNull(TrafficSignals.ahead(list, base.lat, base.lng, 0.0, 150.0)) // out of range
        assertNull(TrafficSignals.ahead(list, base.lat, base.lng, null, 300.0))
    }

    @Test fun `parses overpass skeleton output`() {
        val body = """{"elements":[{"type":"node","id":1,"lat":-31.9,"lon":115.8},{"type":"node","id":2,"lat":-31.91,"lon":115.81}]}"""
        assertEquals(2, TrafficSignals.parse(body).size)
    }

    @Test fun `drive route label and suburbs survive sync`() {
        val d = Drive("x", 0, 600_000, 4500.0, 500_000, 20.0, from = "Morley", to = "Bayswater")
        assertEquals("Morley → Bayswater", d.route)
        assertEquals("Around Morley", d.copy(to = "Morley").route)
        assertNull(Drive("y", 0, 1, 1.0, 1, 1.0).route)
        val back = DeckJson.decode(DeckJson.encode(DeckState(drives = listOf(d)))).drives.single()
        assertEquals("Morley", back.from); assertEquals("Bayswater", back.to)
    }
}
