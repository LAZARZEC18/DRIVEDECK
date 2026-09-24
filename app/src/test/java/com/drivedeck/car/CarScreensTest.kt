package com.drivedeck.car

import android.os.Looper
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.testing.TestCarContext
import androidx.test.core.app.ApplicationProvider
import com.drivedeck.data.DeckRepository
import com.drivedeck.testing.DemoData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Builds the real car templates. The Car App Library validates every template against Android
 * Auto's rules while building it (action limits, loading states and so on), so these tests
 * catch anything the car would reject.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CarScreensTest {

    private lateinit var carContext: TestCarContext

    @Before fun setUp() {
        carContext = TestCarContext.createCarContext(ApplicationProvider.getApplicationContext())
    }

    @Test fun `home puts the learned suggestion first`() {
        DemoData.seed(carContext)
        val t = HomeScreen(carContext).onGetTemplate() as GridTemplate
        val items = t.singleList!!.items.map { it as GridItem }
        assertEquals("Greenhse", items.first().title.toString())
        assertTrue(items.first().text.toString(), items.first().text.toString().contains("~"))
        assertTrue("grid respects car limit", items.size <= 6)
        assertEquals(2, t.actionStrip!!.actions.size)
    }

    @Test fun `home shows a helpful message with no places`() {
        val repo = DemoData.seed(carContext)
        repo.places.value.forEach { repo.deletePlace(it.id) }
        assertTrue(HomeScreen(carContext).onGetTemplate() is MessageTemplate)
    }

    @Test fun `music hub lists shortcuts with controls`() {
        DemoData.seed(carContext)
        val t = MusicScreen(carContext).onGetTemplate() as GridTemplate
        val titles = t.singleList!!.items.map { (it as GridItem).title.toString() }
        assertEquals("Party Mix", titles.first())
        assertEquals(5, titles.size)
        assertEquals(2, t.actionStrip!!.actions.size)
    }

    @Test fun `tapping a place logs the trip and asks the car to navigate`() {
        val repo: DeckRepository = DemoData.seed(carContext)
        val before = repo.trips.value.size
        val t = HomeScreen(carContext).onGetTemplate() as GridTemplate
        val first = t.singleList!!.items.first() as GridItem
        first.onClickDelegate!!.sendClick(NoopCallback)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before + 1, repo.trips.value.size)
        val started = carContext.getStartCarAppIntents()
        assertEquals(1, started.size)
        assertEquals("androidx.car.app.action.NAVIGATE", started.first().action)
        assertTrue(started.first().data.toString().startsWith("geo:-31.776"))
    }

    private object NoopCallback : androidx.car.app.OnDoneCallback
}
