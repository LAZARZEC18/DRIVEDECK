package com.drivedeck.cameras

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpeedCamerasTest {
    // Heading north along a street at -31.90, 115.90. 0.001° lat ≈ 111 m.
    private val ahead = Camera(1, -31.8960, 115.9000, redLight = false, maxSpeed = 60)   // ~445 m north
    private val behind = Camera(2, -31.9040, 115.9000, redLight = false, maxSpeed = 70)  // south
    private val side = Camera(3, -31.9000, 115.9040, redLight = true, maxSpeed = null)   // east
    private val far = Camera(4, -31.8800, 115.9000, redLight = false, maxSpeed = 80)     // 2.2 km north

    @Test fun `finds the camera in front, not behind or to the side`() {
        val a = SpeedCameras.ahead(listOf(behind, side, far, ahead), -31.9000, 115.9000, headingDeg = 0.0)!!
        assertEquals(1L, a.camera.id)
        assertEquals(445.0, a.distanceM, 10.0)
    }

    @Test fun `turning around finds the other one`() {
        assertEquals(2L, SpeedCameras.ahead(listOf(behind, ahead), -31.9000, 115.9000, headingDeg = 180.0)!!.camera.id)
    }

    @Test fun `no heading or out of range means no warning`() {
        assertNull(SpeedCameras.ahead(listOf(ahead), -31.9000, 115.9000, headingDeg = null))
        assertNull(SpeedCameras.ahead(listOf(far), -31.9000, 115.9000, headingDeg = 0.0))
    }

    @Test fun `bearing and angle maths`() {
        assertEquals(0.0, SpeedCameras.bearing(-31.9, 115.9, -31.8, 115.9), 0.5)
        assertEquals(90.0, SpeedCameras.bearing(-31.9, 115.9, -31.9, 116.0), 0.5)
        assertEquals(20.0, SpeedCameras.angleDiff(350.0, 10.0), 1e-9)
    }

    @Test fun `parses overpass`() {
        val body = """{"elements":[
          {"type":"node","id":11,"lat":-32.0071,"lon":115.9235,"tags":{"highway":"speed_camera","maxspeed":"60"}},
          {"type":"node","id":12,"lat":-31.8878,"lon":115.9054,"tags":{"enforcement":"traffic_signals"}},
          {"type":"way","id":13}]}"""
        val cams = SpeedCameras.parse(body)
        assertEquals(2, cams.size)
        assertEquals("Speed camera · 60 zone", cams[0].label)
        assertEquals("Red-light camera", cams[1].label)
    }
}
