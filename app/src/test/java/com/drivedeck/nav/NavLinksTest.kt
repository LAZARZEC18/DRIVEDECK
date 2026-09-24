package com.drivedeck.nav

import com.drivedeck.data.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavLinksTest {
    private val withCoords = Place(name = "Uni", address = "Kent St", lat = -32.00567, lng = 115.894)
    private val addressOnly = Place(name = "Uni", address = "Curtin University, Kent Street, Bentley WA")

    @Test fun `geo uses coordinates when known`() {
        assertEquals("geo:-32.005670,115.894000", NavLinks.geo(withCoords))
    }

    @Test fun `geo falls back to encoded address`() {
        assertEquals("geo:0,0?q=Curtin+University%2C+Kent+Street%2C+Bentley+WA", NavLinks.geo(addressOnly))
    }

    @Test fun `waze link starts navigation`() {
        val l = NavLinks.waze(withCoords)
        assertEquals("https://waze.com/ul?ll=-32.005670,115.894000&navigate=yes", l)
        assertTrue(NavLinks.waze(addressOnly).endsWith("&navigate=yes"))
    }

    @Test fun `distance and formatting`() {
        // Perth CBD to Curtin Bentley is roughly 6-7 km.
        val d = Geo.distanceMeters(-31.9523, 115.8613, -32.0057, 115.8940)
        assertTrue("was $d", d in 6000.0..7500.0)
        assertEquals("450 m", Geo.formatDistance(470.0))
        assertEquals("6.6 km", Geo.formatDistance(6612.0))
        assertEquals("24 km", Geo.formatDistance(24_400.0))
    }
}
