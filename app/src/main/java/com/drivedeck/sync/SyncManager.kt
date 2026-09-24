package com.drivedeck.sync

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.drivedeck.data.DeckMerge
import com.drivedeck.data.DeckRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Keeps this phone and the private GitHub copy of the deck in step.
 *
 * When: app start, car screen opening, 3 s after any edit on the phone, "Sync now", and every
 * 15 minutes in the background.
 * How: pull → merge (newest edit wins per item) → save locally → push only if GitHub is behind.
 * If GitHub changed in between, it re-pulls and merges again, so nothing is overwritten.
 */
class SyncManager private constructor(context: Context) {

    private val ctx = context.applicationContext
    private val repo = DeckRepository.get(ctx)
    private val prefs = ctx.getSharedPreferences("drivedeck_sync", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    data class Config(val owner: String, val repo: String, val token: String)

    sealed interface Status {
        data object NotSetUp : Status
        data object Syncing : Status
        data class Synced(val at: Long) : Status
        data class Error(val message: String, val at: Long) : Status
    }

    private val _status = MutableStateFlow<Status>(if (config() == null) Status.NotSetUp else initialStatus())
    val status: StateFlow<Status> = _status.asStateFlow()

    fun config(): Config? {
        val token = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        return Config(prefs.getString(KEY_OWNER, DEFAULT_OWNER)!!, prefs.getString(KEY_REPO, DEFAULT_REPO)!!, token)
    }

    fun saveConfig(owner: String, repoName: String, token: String) {
        prefs.edit {
            putString(KEY_OWNER, owner.trim()); putString(KEY_REPO, repoName.trim()); putString(KEY_TOKEN, token.trim())
        }
        _status.value = Status.Syncing
        requestSync()
    }

    fun disconnect() {
        prefs.edit { remove(KEY_TOKEN) }
        _status.value = Status.NotSetUp
    }

    /** Called once from Application.onCreate. */
    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch { repo.localEdits.debounce(3_000).collect { syncNow() } }
        runCatching {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                "drivedeck-sync",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }
        requestSync()
    }

    fun requestSync() { scope.launch { syncNow() } }

    suspend fun syncNow(): Status = mutex.withLock {
        val cfg = config() ?: return Status.NotSetUp.also { _status.value = it }
        _status.value = Status.Syncing
        val store = GitHubStore(cfg.owner, cfg.repo, cfg.token)
        val result = try {
            repeat(3) {
                val remote = store.fetch()
                val merged = DeckMerge.merge(repo.state.value, remote.state ?: repo.state.value)
                repo.applySynced(merged)
                if (merged == remote.state) return@withLock markSynced()
                try {
                    store.put(merged, remote.sha, "DRIVEDECK phone sync")
                    return@withLock markSynced()
                } catch (_: GitHubStore.ConflictException) {
                    // Someone (laptop/chat) saved in between: loop pulls their change and merges again.
                }
            }
            Status.Error("GitHub kept changing. Will retry", System.currentTimeMillis())
        } catch (e: GitHubStore.AuthException) {
            Status.Error("Token rejected (${e.message}). Check repo name and token", System.currentTimeMillis())
        } catch (e: IOException) {
            Status.Error("Offline or GitHub unreachable. Will retry", System.currentTimeMillis())
        } catch (e: Exception) {
            Status.Error(e.message ?: "Sync failed", System.currentTimeMillis())
        }
        _status.value = result
        result
    }

    private fun markSynced(): Status {
        val now = System.currentTimeMillis()
        prefs.edit { putLong(KEY_LAST_OK, now) }
        return Status.Synced(now).also { _status.value = it }
    }

    private fun initialStatus(): Status =
        prefs.getLong(KEY_LAST_OK, 0).takeIf { it > 0 }?.let { Status.Synced(it) } ?: Status.Syncing

    companion object {
        const val DEFAULT_OWNER = "LAZARZEC18"
        const val DEFAULT_REPO = "drivedeck-data"
        private const val KEY_OWNER = "owner"
        private const val KEY_REPO = "repo"
        private const val KEY_TOKEN = "token"
        private const val KEY_LAST_OK = "last_ok"

        @SuppressLint("StaticFieldLeak") // holds the application context only
        @Volatile private var instance: SyncManager? = null

        fun get(context: Context): SyncManager =
            instance ?: synchronized(this) { instance ?: SyncManager(context).also { instance = it } }
    }
}

/** Background sync every ~15 minutes (Android batches these to save battery). */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        when (SyncManager.get(applicationContext).syncNow()) {
            is SyncManager.Status.Error -> Result.retry()
            else -> Result.success()
        }
}
