package app.alextran.immich.background

import io.flutter.plugin.common.BinaryMessenger
import app.alextran.immich.core.HttpClientManager
import app.alextran.immich.upload.RufhUploader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

import android.content.Context
import android.provider.MediaStore
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.flutter.embedding.engine.FlutterEngineCache
import java.util.concurrent.TimeUnit

private const val TAG = "BackgroundWorkerApiImpl"

class BackgroundWorkerApiImpl(
    context: Context,
    private val messenger: BinaryMessenger? = null,
) : BackgroundWorkerFgHostApi {
  private val ctx: Context = context.applicationContext
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)


  override fun enable() {
    enqueueMediaObserver(ctx)
    enqueuePeriodicWorker(ctx)
  }

  override fun saveNotificationMessage(title: String, body: String) {
    BackgroundWorkerPreferences(ctx).updateNotificationConfig(title, body)
  }

  override fun configure(settings: BackgroundWorkerSettings) {
    BackgroundWorkerPreferences(ctx).updateSettings(settings)
    enqueueMediaObserver(ctx)
    enqueuePeriodicWorker(ctx)
  }

  override fun disable() {
    WorkManager.getInstance(ctx).apply {
      cancelUniqueWork(OBSERVER_WORKER_NAME)
      cancelUniqueWork(BACKGROUND_WORKER_NAME)
      cancelUniqueWork(RETRY_WORKER_NAME)
      cancelUniqueWork(PERIODIC_WORKER_NAME)
    }
    Log.i(TAG, "Cancelled background upload tasks")
  }

  override fun wasLaunchedInBackground(): Boolean {
    // iOS only
    return false
  }

  override fun uploadRufh(request: RufhUploadRequest, callback: (Result<String>) -> Unit) {
      scope.launch {
          try {
              val baseClient = HttpClientManager.getClient()
                  .newBuilder()
                  .writeTimeout(0, TimeUnit.SECONDS)
                  .readTimeout(300, TimeUnit.SECONDS)
                  .callTimeout(0, TimeUnit.SECONDS)
                  .build()
  
              val uploader = RufhUploader(baseClient, request.serverUrl)
              val progressApi = messenger?.let { RufhProgressFlutterApi(it) }
              val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
  
              val assetId = uploader.upload(
                  assetId = request.assetId,
                  file = File(request.filePath),
                  deviceId = deviceId(),
                  onChunkComplete = { committed: Long, total: Long ->
                      mainHandler.post {
                          progressApi?.onRufhProgress(request.assetId, committed, total) {}
                      }
                  },
              )
  
              withContext(Dispatchers.Main) { callback(Result.success(assetId)) }
          } catch (e: Exception) {
              withContext(Dispatchers.Main) { callback(Result.failure(e)) }
          }
      }
  }

  override fun cancelRufhUpload(assetId: String) {
    // Optional: track in-flight uploads by assetId and cancel.
    // For the first cut, no-op is acceptable — WorkManager isn't involved.
  }

  private fun deviceId(): String =
    android.provider.Settings.Secure.getString(
      ctx.contentResolver,
      android.provider.Settings.Secure.ANDROID_ID,
  )

  companion object {
    private const val BACKGROUND_WORKER_NAME = "immich/BackgroundWorkerV1"
    private const val RETRY_WORKER_NAME = "immich/BackgroundWorkerRetryV1"
    private const val OBSERVER_WORKER_NAME = "immich/MediaObserverV1"
    private const val PERIODIC_WORKER_NAME = "immich/PeriodicBackgroundWorkerV1"
    const val ENGINE_CACHE_KEY = "immich::background_worker::engine"
    const val RETRY_TAG = "immich/retryWhenConnected"


    fun enqueueMediaObserver(ctx: Context) {
      val settings = BackgroundWorkerPreferences(ctx).getSettings()
      val constraints = Constraints.Builder().apply {
        addContentUriTrigger(MediaStore.Images.Media.INTERNAL_CONTENT_URI, true)
        addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
        addContentUriTrigger(MediaStore.Video.Media.INTERNAL_CONTENT_URI, true)
        addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
        setTriggerContentUpdateDelay(settings.minimumDelaySeconds, TimeUnit.SECONDS)
        setTriggerContentMaxDelay(settings.minimumDelaySeconds * 10, TimeUnit.SECONDS)
        setRequiresCharging(settings.requiresCharging)
      }.build()

      val work = OneTimeWorkRequestBuilder<MediaObserver>()
        .setConstraints(constraints)
        .build()
      WorkManager.getInstance(ctx)
        .enqueueUniqueWork(OBSERVER_WORKER_NAME, ExistingWorkPolicy.REPLACE, work)

      Log.i(
        TAG,
        "Enqueued media observer worker with name: $OBSERVER_WORKER_NAME and settings: $settings"
      )
    }

    fun enqueuePeriodicWorker(ctx: Context) {
      val settings = BackgroundWorkerPreferences(ctx).getSettings()
      val constraints = Constraints.Builder().apply {
        setRequiresCharging(settings.requiresCharging)
      }.build()

      val work =
        PeriodicWorkRequestBuilder<PeriodicWorker>(
          1,
          TimeUnit.HOURS,
          15,
          TimeUnit.MINUTES
        ).setConstraints(constraints)
          .build()

      WorkManager.getInstance(ctx)
        .enqueueUniquePeriodicWork(PERIODIC_WORKER_NAME, ExistingPeriodicWorkPolicy.UPDATE, work)

      Log.i(TAG, "Enqueued periodic background worker with name: $PERIODIC_WORKER_NAME")
    }

    fun enqueueBackgroundWorker(ctx: Context) {
      val constraints = Constraints.Builder().setRequiresBatteryNotLow(true).build()
      val work = OneTimeWorkRequestBuilder<BackgroundWorker>()
        .setConstraints(constraints)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
        .build()
      WorkManager.getInstance(ctx)
        .enqueueUniqueWork(BACKGROUND_WORKER_NAME, ExistingWorkPolicy.KEEP, work)

      Log.i(TAG, "Enqueued background worker with name: $BACKGROUND_WORKER_NAME")
    }

    fun enqueueBackgroundWorkerWhenConnected(ctx: Context) {
      val settings = BackgroundWorkerPreferences(ctx).getSettings()
      val constraints = Constraints.Builder().apply {
        setRequiredNetworkType(NetworkType.CONNECTED)
        setRequiresBatteryNotLow(true)
        setRequiresCharging(settings.requiresCharging)
      }.build()
      val work = OneTimeWorkRequestBuilder<BackgroundWorker>()
        .setConstraints(constraints)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
        .addTag(RETRY_TAG)
        .build()
      WorkManager.getInstance(ctx)
        .enqueueUniqueWork(RETRY_WORKER_NAME, ExistingWorkPolicy.KEEP, work)

      Log.i(TAG, "Enqueued background worker to run once connected")
    }

    fun isBackgroundWorkerRunning(): Boolean {
      // Easier to check if the engine is cached as we always cache the engine when starting the worker
      // and remove it when the worker is finished
      return FlutterEngineCache.getInstance().get(ENGINE_CACHE_KEY) != null
    }

    fun cancelBackgroundWorker(ctx: Context) {
      WorkManager.getInstance(ctx).cancelUniqueWork(BACKGROUND_WORKER_NAME)
      WorkManager.getInstance(ctx).cancelUniqueWork(RETRY_WORKER_NAME)
      FlutterEngineCache.getInstance().remove(ENGINE_CACHE_KEY)

      Log.i(TAG, "Cancelled background upload task")
    }
  }
}
