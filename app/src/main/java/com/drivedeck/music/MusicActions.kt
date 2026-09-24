package com.drivedeck.music

import android.content.Context
import com.drivedeck.data.DeckRepository
import com.drivedeck.data.MusicFavorite
import com.drivedeck.data.MusicKind
import com.drivedeck.data.RecentSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Song search + "recently searched" list, shared by the car and phone screens. */
object MusicActions {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Plays whatever you searched for and remembers it in Recent (max 20). A few seconds later
     * it reads what YouTube Music actually started, so the Recent tile shows the real title and
     * artist and replays exactly that song.
     */
    suspend fun playSearch(context: Context, query: String, allowActivityFallback: Boolean = false): YtMusicController.PlayResult {
        val ctx = context.applicationContext
        val yt = YtMusicController(ctx)
        val q = query.trim()
        val result = yt.play(MusicFavorite(id = "search", name = q, kind = MusicKind.SONG, query = q), allowActivityFallback)
        if (result.ok) scope.launch {
            // Wait for YouTube Music to start, then remember the real song (or the search text).
            delay(5_000)
            val np = yt.nowPlaying()?.takeIf { !it.title.isNullOrBlank() }
            DeckRepository.get(ctx).addRecent(
                if (np != null) RecentSong(np.title!!, np.artist, listOfNotNull(np.title, np.artist).joinToString(" "), System.currentTimeMillis())
                else RecentSong(q, null, q, System.currentTimeMillis()),
            )
        }
        return result
    }

    suspend fun playRecent(context: Context, song: RecentSong): YtMusicController.PlayResult =
        playSearch(context, song.query)
}
