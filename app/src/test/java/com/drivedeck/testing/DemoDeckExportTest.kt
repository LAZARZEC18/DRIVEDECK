package com.drivedeck.testing

import com.drivedeck.data.DeckJson
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZonedDateTime

/** Writes demo data as deck.json, for developing the laptop dashboard against realistic data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DemoDeckExportTest {
    @Test fun export() {
        File("build/demo-deck.json").apply { parentFile?.mkdirs() }.writeText(DeckJson.encode(DemoData.state(ZonedDateTime.now())))
    }
}
