package app.alextran.immich.upload

import app.alextran.immich.background.RufhUploadRequest
import kotlinx.coroutines.CancellationException
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import app.alextran.immich.R
import app.alextran.immich.core.HttpClientManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class RufhUploadWorker(
    ctx: Context,
    params: WorkerParameters,
) : CoroutineWorker(ctx, params) {

    private val store by lazy { RufhSessionStore(applicationContext) }
    private val notifier by lazy { RufhUploadNotification(applicationContext) }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = notifier.buildProgress(
            fileName = "Preparing…",
            committed = 0,
            total = 1,
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val assetId = inputData.getString(KEY_ASSET_ID)
        val filePath = inputData.getString(KEY_FILE_PATH)
        val serverUrl = inputData.getString(KEY_SERVER_URL)

        if (assetId == null || filePath == null || serverUrl == null) {
            Log.w(TAG, "missing inputs, aborting")
            return@withContext Result.failure()
        }

        setForeground(getForegroundInfo())

        val file = File(filePath)
        if (!file.exists() ||
            file.length() != inputData.getLong(KEY_FILE_SIZE, file.length()) ||
            file.lastModified() != inputData.getLong(KEY_FILE_MTIME, file.lastModified())
        ) {
            Log.w(TAG, "source changed or missing, dropping session for $assetId")
            store.remove(assetId)
            notifier.dismiss()
            return@withContext Result.failure()
        }

        val client = HttpClientManager.getClient()
            .newBuilder()
            .writeTimeout(0, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()

        val uploader = RufhUploader(client, serverUrl)
        var currentSession: RufhSession? = store.load(assetId)

        try {
            uploader.upload(
                assetId = assetId,
                file = file,
                deviceId = deviceId(),
                onCreated = { uploadId, chunkSize ->
                    currentSession = RufhSession(
                        assetId = assetId,
                        uploadId = uploadId,
                        filePath = filePath,
                        fileSize = file.length(),
                        fileMtime = file.lastModified(),
                        serverUrl = serverUrl,
                        committedOffset = 0L,
                        chunkSize = chunkSize,
                        reprDigestSha1 = "",
                        createdAt = System.currentTimeMillis(),
                        status = RufhSession.Status.ACTIVE,
                    )
                    currentSession?.let { store.save(it) }
                    Log.i(TAG, "session persisted: $uploadId for $assetId")
                },
                onChunkComplete = { committed, total, chunkSize ->
                    currentSession = currentSession?.copy(
                        committedOffset = committed,
                        chunkSize = chunkSize,
                    )
                    currentSession?.let { store.save(it) }
                    notifier.updateProgress(file.name, committed, total)
                },
            )

            // Success — clean up
            store.remove(assetId)
            notifier.dismiss()
            Log.i(TAG, "upload complete for $assetId")
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Platform stopped us — swipe from Recents, foreground service
            // teardown, WorkManager cancel. Not a failure. Leave the session
            // record as-is so the next run can HEAD and resume it.
            Log.i(TAG, "worker cancelled for $assetId, session left intact")
            throw e

        } catch (e: IOException) {
            // Network — retryable. Session stays ACTIVE so HEAD resumes from offset.
            Log.w(TAG, "transient network failure for $assetId: $e")
            notifier.updateProgress(file.name, currentSession?.committedOffset ?: 0, file.length())
            Result.retry()

        } catch (e: OrphanedSessionException) {
            // Server has a session it won't tell us about — mark and reconcile later.
            Log.w(TAG, "orphaned session for $assetId: ${e.message}")
            store.markOrphaned(assetId)
            notifier.dismiss()
            Result.failure()

        } catch (e: Exception) {
            Log.e(TAG, "permanent failure for $assetId: $e", e)
            store.markOrphaned(assetId)
            notifier.dismiss()
            Result.failure()
        }
    }

    private fun deviceId(): String =
        Settings.Secure.getString(
            applicationContext.contentResolver,
            Settings.Secure.ANDROID_ID,
        )

    companion object {
        private const val TAG = "RufhUploadWorker"
        private const val NOTIFICATION_ID = 0x1F0F  // arbitrary, unique within app

        const val KEY_ASSET_ID = "assetId"
        const val KEY_FILE_PATH = "filePath"
        const val KEY_FILE_SIZE = "fileSize"
        const val KEY_FILE_MTIME = "fileMtime"
        const val KEY_SERVER_URL = "serverUrl"

        /**
         * Enqueue a RUFH upload. Idempotent per assetId via enqueueUniqueWork KEEP.
         * Returns immediately; the transfer runs on WorkManager's foreground service.
         */
        fun enqueue(ctx: Context, request: RufhUploadRequest) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val work = OneTimeWorkRequestBuilder<RufhUploadWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        KEY_ASSET_ID to request.assetId,
                        KEY_FILE_PATH to request.filePath,
                        KEY_FILE_SIZE to request.fileSize,
                        KEY_FILE_MTIME to request.fileMtime,
                        KEY_SERVER_URL to request.serverUrl,
                    )
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30L,
                    TimeUnit.SECONDS,
                )
                .addTag("rufh/${request.assetId}")
                .build()

            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "rufh/${request.assetId}",
                ExistingWorkPolicy.KEEP,
                work,
            )
        }
    }
}
