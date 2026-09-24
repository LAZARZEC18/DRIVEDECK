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
        assertEquals("More", items.last().title.toString())
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
        assertEquals(listOf("Search", "Recent", "Party Mix"), titles.take(3))
        assertTrue(titles.size <= 6)
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

    @Test fun `hub, trip, week, fuel, messages and search screens all build`() {
        DemoData.seed(carContext)
        val hub = HubScreen(carContext).onGetTemplate() as GridTemplate
        assertEquals(listOf("Trip", "Fuel", "WhatsApp", "This week", "Recent songs", "Cameras"), hub.singleList!!.items.map { (it as GridItem).title.toString() })
        assertTrue(CamerasScreen(carContext).onGetTemplate() is androidx.car.app.model.PlaceListMapTemplate)

        val trip = TripScreen(carContext).onGetTemplate() as androidx.car.app.model.PaneTemplate
        assertEquals("Trip computer", trip.pane.rows.first().title.toString())

        val week = WeekScreen(carContext).onGetTemplate() as androidx.car.app.model.PaneTemplate
        assertEquals(4, week.pane.rows.size)
        assertTrue(week.pane.rows.first().texts.first().toString(), week.pane.rows.first().texts.first().toString().contains("drives"))

        assertTrue(FuelScreen(carContext).onGetTemplate() is androidx.car.app.model.PlaceListMapTemplate)
        assertTrue(MessagesScreen(carContext).onGetTemplate() is MessageTemplate) // no notification access in tests
        assertTrue(PlaceSearchScreen(carContext).onGetTemplate() is androidx.car.app.model.SearchTemplate)
        val songs = MusicSearchScreen(carContext).onGetTemplate() as androidx.car.app.model.SearchTemplate
        assertEquals("Rumble", (songs.itemList!!.items.first() as androidx.car.app.model.Row).title.toString())
        val recents = RecentSongsScreen(carContext).onGetTemplate() as androidx.car.app.model.ListTemplate
        assertEquals(6, recents.singleList!!.items.size)
    }

    @Test fun `whatsapp chat screens build with a conversation`() {
        com.drivedeck.messages.MessageHub.setForTests(
            listOf(
                com.drivedeck.messages.Conversation(
                    key = "k1", app = "com.whatsapp", title = "Maxwell",
                    messages = listOf(com.drivedeck.messages.Conversation.Message(null, "Coming to the game tonight?", 1L)),
                    postedAt = System.currentTimeMillis(), isGroup = false, reply = null, markRead = null,
                ),
            ),
        )
        val convo = ConversationScreen(carContext, "k1").onGetTemplate() as androidx.car.app.model.PaneTemplate
        assertEquals("Maxwell", convo.title.toString())
        assertEquals(2, convo.pane.actions.size)
        val full = FullTextScreen(carContext, "k1").onGetTemplate() as androidx.car.app.model.LongMessageTemplate
        assertTrue(full.message.toString().contains("Coming to the game"))
        assertTrue(QuickReplyScreen(carContext, "k1").onGetTemplate() is androidx.car.app.model.ListTemplate)
        com.drivedeck.messages.MessageHub.setForTests(emptyList())
    }
}
