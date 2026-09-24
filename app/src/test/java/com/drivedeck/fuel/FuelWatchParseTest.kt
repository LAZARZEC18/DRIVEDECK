package com.drivedeck.fuel

import com.drivedeck.data.DeckJson
import com.drivedeck.testing.DemoData
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FuelWatchParseTest {
    private val sample = """<?xml version="1.0"?><rss version="2.0"><channel><title>FuelWatch Prices For Morley</title>
        <item><title>234.9: Vibe Bayswater</title><brand>Vibe</brand><price>234.9</price><trading-name>Vibe Bayswater</trading-name>
        <location>BAYSWATER</location><address>120 Beechboro Rd South</address><latitude>-31.90468330</latitude><longitude>115.91632680</longitude></item>
        <item><title>231.5: Burk Morley</title><brand>Burk</brand><price>231.5</price><trading-name>Burk Morley</trading-name>
        <location>MORLEY</location><address>1 Walter Rd</address><latitude>-31.89</latitude><longitude>115.90</longitude></item>
        <item><title>broken</title><price>n/a</price></item>
        </channel></rss>"""

    @Test fun `parses stations and skips broken items`() {
        val list = FuelWatch.parse(sample.byteInputStream())
        assertEquals(2, list.size)
        assertEquals("Vibe Bayswater", list[0].name)
        assertEquals(234.9, list[0].centsPerLitre, 1e-9)
        assertEquals("Bayswater", list[0].suburb)
        assertEquals(-31.9046833, list[0].lat, 1e-6)
    }

    @Test fun `deck json round trips everything`() {
        val s = DemoData.state(ZonedDateTime.now())
        assertEquals(s, DeckJson.decode(DeckJson.encode(s)))
    }
}
