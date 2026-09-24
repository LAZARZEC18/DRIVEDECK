package com.drivedeck.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.drivedeck.testing.DemoData
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the phone app with demo data. Also checks that every tab composes without crashing.
 * With -Proborazzi.record the PNGs land in docs/screenshots for the README.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-night-xxhdpi")
class PhoneScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private val out = System.getProperty("roborazzi.output.dir") ?: "build/screenshots"

    @Before fun seed() { DemoData.seed(ApplicationProvider.getApplicationContext()) }

    private fun shoot(tab: Int, name: String) {
        compose.setContent { DriveDeckTheme { DeckApp(initialTab = tab) } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$out/$name.png")
    }

    @Test fun drive() = shoot(0, "phone_drive")
    @Test fun music() = shoot(1, "phone_music")
    @Test fun stats() = shoot(2, "phone_stats")
    @Test fun fuel() = shoot(3, "phone_fuel")
    @Test fun setup() = shoot(4, "phone_setup")
}
