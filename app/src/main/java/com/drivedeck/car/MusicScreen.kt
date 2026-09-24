@file:Suppress("DEPRECATION") // see HomeScreen: deprecated builders keep us compatible with every car.

package com.drivedeck.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.drivedeck.R
import com.drivedeck.data.DeckRepository
import com.drivedeck.data.MusicFavorite
import com.drivedeck.music.YtMusicController
import com.drivedeck.ui.iconRes
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Music hub on the car display: your favourite playlists, artists and mixes as big tiles.
 * One tap starts it in YouTube Music. The action strip has play/pause and skip.
 */
class MusicScreen(carContext: CarContext) : Screen(carContext) {

    private val repo = DeckRepository.get(carContext)
    private val music = YtMusicController(carContext)
    private var watch: AutoCloseable? = null
    private var pendingId: String? = null

    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { repo.music.drop(1).collect { invalidate() } }
        }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { watch = music.observe { invalidate() } }
            override fun onStop(owner: LifecycleOwner) { watch?.close(); watch = null }
        })
    }

    override fun onGetTemplate(): Template {
        val favs = repo.music.value
        val items = ItemList.Builder()
        items.addItem(
            GridItem.Builder().setTitle("Search").setText("Any song")
                .setImage(CarUi.icon(carContext, R.drawable.ic_search, CarColor.DEFAULT), GridItem.IMAGE_TYPE_LARGE)
                .setOnClickListener { screenManager.push(MusicSearchScreen(carContext)) }.build(),
        )
        items.addItem(
            GridItem.Builder().setTitle("Recent").setText("${repo.recents.value.size} songs")
                .setImage(CarUi.icon(carContext, R.drawable.ic_history, CarColor.DEFAULT), GridItem.IMAGE_TYPE_LARGE)
                .setOnClickListener { screenManager.push(RecentSongsScreen(carContext)) }.build(),
        )
        favs.take(CarUi.gridLimit(carContext) - 2).forEach { fav ->
            items.addItem(
                GridItem.Builder()
                    .setTitle(fav.name)
                    .setText(fav.kind.label)
                    .apply {
                        if (fav.id == pendingId) {
                            setLoading(true) // loading tiles may not have an image or click listener
                        } else {
                            setImage(CarUi.icon(carContext, fav.kind.iconRes(), CarUi.ACCENT), GridItem.IMAGE_TYPE_LARGE)
                            setOnClickListener { play(fav) }
                        }
                    }
                    .build(),
            )
        }

        // Title stays fixed on purpose: Android Auto limits how many times an app may swap
        // templates, and a changing title can count as a new template instead of a refresh.
        // The track itself is already shown in Android Auto's own media bar.
        val now = music.nowPlaying()

        val strip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(CarUi.icon(carContext, if (now?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play, CarColor.DEFAULT))
                    .setOnClickListener { if (!music.togglePlayPause()) toast("Pick something below to start") }
                    .build(),
            )
            .addAction(
                Action.Builder()
                    .setIcon(CarUi.icon(carContext, R.drawable.ic_next, CarColor.DEFAULT))
                    .setOnClickListener { if (!music.skipNext()) toast("Nothing playing yet") }
                    .build(),
            )
            .build()

        return GridTemplate.Builder()
            .setTitle("Music")
            .setHeaderAction(Action.BACK)
            .setSingleList(items.build())
            .setActionStrip(strip)
            .build()
    }

    private fun play(fav: MusicFavorite) {
        if (pendingId != null) return
        pendingId = fav.id
        invalidate()
        lifecycleScope.launch {
            val result = music.play(fav)
            pendingId = null
            toast(result.message)
            invalidate()
        }
    }

    private fun toast(msg: String) = CarToast.makeText(carContext, msg, CarToast.LENGTH_SHORT).show()
}
