@file:Suppress("DEPRECATION")

package com.drivedeck.car

import android.content.Intent
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.drivedeck.R
import com.drivedeck.data.DeckRepository
import com.drivedeck.data.RecentSong
import com.drivedeck.music.MusicActions
import com.drivedeck.nav.Geo
import com.drivedeck.nav.NavLinks
import com.drivedeck.nav.PlaceSearch
import com.drivedeck.nav.SearchResult
import com.drivedeck.trip.Journey
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URLEncoder

/**
 * Search any destination. Results come from OpenStreetMap and Android's geocoder. The last row
 * hands your exact words to your navigation app (Waze or Google Maps), so its own search and
 * live traffic take over.
 */
class PlaceSearchScreen(carContext: CarContext) : Screen(carContext) {
    private var query = ""
    private var results: List<SearchResult> = emptyList()
    private var loading = false
    private var job: Job? = null

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        val limit = CarUi.listLimit(carContext)
        results.take(limit - 1).forEach { r ->
            list.addItem(
                Row.Builder().setTitle(r.name)
                    .addText(listOfNotNull(r.distanceKm?.let { Geo.formatDistance(it * 1000) }, r.detail.ifBlank { null }).joinToString(" · "))
                    .setImage(CarUi.icon(carContext, R.drawable.ic_pin, CarColor.DEFAULT))
                    .setOnClickListener { go(r) }
                    .build(),
            )
        }
        if (query.isNotBlank()) {
            list.addItem(
                Row.Builder().setTitle("Search \"$query\" in your navigation app")
                    .addText("Waze / Google Maps search with live traffic")
                    .setImage(CarUi.icon(carContext, R.drawable.ic_search, CarUi.ACCENT))
                    .setOnClickListener { navigate("geo:0,0?q=${URLEncoder.encode(query, "UTF-8")}") }
                    .build(),
            )
        }
        return SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) { schedule(searchText, 600) }
            override fun onSearchSubmitted(searchText: String) { schedule(searchText, 0) }
        })
            .setHeaderAction(Action.BACK)
            .setSearchHint("Search places or addresses")
            .setShowKeyboardByDefault(false)
            .setInitialSearchText(query)
            .apply { if (loading && results.isEmpty()) setLoading(true) else setItemList(list.build()) }
            .build()
    }

    private fun schedule(text: String, delayMs: Long) {
        query = text
        job?.cancel()
        if (text.isBlank()) { results = emptyList(); invalidate(); return }
        job = lifecycleScope.launch {
            delay(delayMs)
            loading = true; invalidate()
            results = PlaceSearch.search(carContext, text)
            loading = false; invalidate()
        }
    }

    private fun go(r: SearchResult) {
        Journey.begin(carContext, r.toPlace())
        navigate(NavLinks.geo(r.toPlace()))
    }

    private fun navigate(uri: String) {
        try {
            carContext.startCarApp(Intent(CarContext.ACTION_NAVIGATE, uri.toUri()))
        } catch (_: Exception) {
            CarToast.makeText(carContext, "Couldn't open navigation", CarToast.LENGTH_LONG).show()
        }
    }
}

/** Song search with your last 20 songs underneath for one-tap replay. */
class MusicSearchScreen(carContext: CarContext) : Screen(carContext) {
    private val repo = DeckRepository.get(carContext)
    private var query = ""

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        if (query.isNotBlank()) {
            list.addItem(
                Row.Builder().setTitle("Play \"$query\"").addText("YouTube Music")
                    .setImage(CarUi.icon(carContext, R.drawable.ic_play, CarUi.ACCENT))
                    .setOnClickListener { play(query) }.build(),
            )
        }
        val recents = repo.recents.value.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
        recents.take(CarUi.listLimit(carContext) - if (query.isBlank()) 0 else 1).forEach { s -> list.addItem(recentRow(s)) }
        if (query.isBlank() && recents.isEmpty()) list.setNoItemsMessage("Songs you search for will show up here")
        return SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) { query = searchText; invalidate() }
            override fun onSearchSubmitted(searchText: String) { query = searchText; if (searchText.isNotBlank()) play(searchText) }
        })
            .setHeaderAction(Action.BACK)
            .setSearchHint("Song, artist or album")
            .setShowKeyboardByDefault(false)
            .setInitialSearchText(query)
            .setItemList(list.build())
            .build()
    }

    private fun recentRow(s: RecentSong) = Row.Builder().setTitle(s.title)
        .addText(s.artist ?: "Recent")
        .setImage(CarUi.icon(carContext, R.drawable.ic_history, CarColor.DEFAULT))
        .setOnClickListener { play(s.query) }.build()

    private fun play(q: String) {
        lifecycleScope.launch {
            val r = MusicActions.playSearch(carContext, q)
            CarToast.makeText(carContext, r.message, CarToast.LENGTH_SHORT).show()
        }
    }
}

/** Your last 20 songs, one tap to play again. */
class RecentSongsScreen(carContext: CarContext) : Screen(carContext) {
    private val repo = DeckRepository.get(carContext)

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder().setNoItemsMessage("Songs you search for will show up here")
        repo.recents.value.take(CarUi.listLimit(carContext, 20)).forEach { s ->
            list.addItem(
                Row.Builder().setTitle(s.title).addText(s.artist ?: "Search")
                    .setImage(CarUi.icon(carContext, R.drawable.ic_song, CarUi.ACCENT))
                    .setOnClickListener {
                        lifecycleScope.launch {
                            CarToast.makeText(carContext, MusicActions.playRecent(carContext, s).message, CarToast.LENGTH_SHORT).show()
                        }
                    }.build(),
            )
        }
        return ListTemplate.Builder().setTitle("Recent songs").setHeaderAction(Action.BACK).setSingleList(list.build()).build()
    }
}
