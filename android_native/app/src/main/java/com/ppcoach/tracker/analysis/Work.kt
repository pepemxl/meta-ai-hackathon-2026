package com.ppcoach.tracker.analysis

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ppcoach.tracker.AppGraph
import com.ppcoach.tracker.BuildConfig
import com.ppcoach.tracker.data.ApiClient
import com.ppcoach.tracker.data.ApiException
import com.ppcoach.tracker.data.Consent
import java.io.IOException
import java.util.concurrent.TimeUnit

/** WorkManager chains: one unique chain per clip ("clip-<id>") and one for consent events. */
object Work {
    fun clipChain(clipId: Int) = "clip-$clipId"

    /** Prepare the chunks and, with a server and a session, upload them. */
    fun send(context: Context, clipId: Int, upload: Boolean) {
        val wm = WorkManager.getInstance(context)
        val chunk = OneTimeWorkRequestBuilder<ChunkWorker>()
            .setInputData(workDataOf(ChunkWorker.KEY_CLIP to clipId))
            .addTag(ChunkWorker.TAG)
            .addTag(clipChain(clipId))
            .build()
        var chain = wm.beginUniqueWork(clipChain(clipId), ExistingWorkPolicy.KEEP, chunk)
        if (upload) {
            val up = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(workDataOf(UploadWorker.KEY_CLIP to clipId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag(UploadWorker.TAG)
                .addTag(clipChain(clipId))
                .build()
            chain = chain.then(up)
        }
        chain.enqueue()
    }

    fun cancelClip(context: Context, clipId: Int) {
        WorkManager.getInstance(context).cancelUniqueWork(clipChain(clipId))
    }

    /** Report an acceptance or a withdrawal to POST /consent, in order. */
    fun reportConsent(context: Context, action: String, version: String, at: String) {
        val req = OneTimeWorkRequestBuilder<ConsentWorker>()
            .setInputData(workDataOf("action" to action, "version" to version, "at" to at))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(ConsentWorker.TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("consent", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    /** Withdrawing cancels every pending upload. */
    fun cancelUploads(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(UploadWorker.TAG)
    }
}

/** POST /consent for one event. A 401 is retried too: it waits for the coach to log in. */
class ConsentWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val TAG = "consent"
        const val MAX_ATTEMPTS = 20
    }

    override suspend fun doWork(): Result {
        AppGraph.init(applicationContext)
        val settings = AppGraph.settings.load()
        val token = AppGraph.settings.token()
        if (settings.serverUrl.isBlank() || token == null) return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        val event = Consent.event(
            inputData.getString("action") ?: return Result.failure(),
            inputData.getString("version") ?: return Result.failure(),
            inputData.getString("at") ?: return Result.failure(),
            BuildConfig.VERSION_NAME,
        )
        return try {
            ApiClient(settings.serverUrl, token).consentEvent(event)
            Result.success()
        } catch (e: ApiException) {
            if ((e.retryable || e.code == 401) && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        } catch (e: IOException) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }
}
