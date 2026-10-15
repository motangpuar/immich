package app.alextran.immich

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.ext.SdkExtensions
import android.util.Log
import androidx.lifecycle.lifecycleScope
import app.alextran.immich.background.BackgroundEngineLock
import app.alextran.immich.background.BackgroundWorkerApiImpl
import app.alextran.immich.background.BackgroundWorkerFgHostApi
import app.alextran.immich.background.BackgroundWorkerLockApi
import app.alextran.immich.connectivity.ConnectivityApi
import app.alextran.immich.connectivity.ConnectivityApiImpl
import app.alextran.immich.core.HttpClientManager
import app.alextran.immich.core.ImmichPlugin
import app.alextran.immich.core.NetworkApiPlugin
import app.alextran.immich.background.RufhUploadRequest
import app.alextran.immich.upload.RufhSession
import app.alextran.immich.upload.RufhSessionStore
import app.alextran.immich.upload.RufhUploadWorker
import app.alextran.immich.upload.RufhUploader
import me.albemala.native_video_player.NativeVideoPlayerPlugin
import app.alextran.immich.images.LocalImageApi
import app.alextran.immich.images.LocalImagesImpl
import app.alextran.immich.images.RemoteImageApi
import app.alextran.immich.images.RemoteImagesImpl
import app.alextran.immich.permission.PermissionApi
import app.alextran.immich.permission.PermissionApiImpl
import app.alextran.immich.sync.NativeSyncApi
import app.alextran.immich.sync.NativeSyncApiImpl26
import app.alextran.immich.sync.NativeSyncApiImpl30
import app.alextran.immich.viewintent.ViewIntentPlugin
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : FlutterFragmentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    HttpClientManager.initialize(applicationContext)

    // Reconcile RUFH sessions left over from previous runs. Runs in the
    // background so it doesn't block Flutter engine setup; the KEEP policy
    // on enqueueUniqueWork makes concurrent enqueues from Dart safe.
    lifecycleScope.launch(Dispatchers.IO) {
      reconcileRufhSessions(applicationContext)
    }
  }

  override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
    super.configureFlutterEngine(flutterEngine)
    registerPlugins(this, flutterEngine)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
  }

  companion object {
    private const val TAG = "RufhReconcile"

    fun registerPlugins(ctx: Context, flutterEngine: FlutterEngine) {
      HttpClientManager.initialize(ctx)
      NativeVideoPlayerPlugin.dataSourceFactory = HttpClientManager::createDataSourceFactory
      flutterEngine.plugins.add(NetworkApiPlugin())

      val messenger = flutterEngine.dartExecutor.binaryMessenger
      val backgroundEngineLockImpl = BackgroundEngineLock(ctx)
      BackgroundWorkerLockApi.setUp(messenger, backgroundEngineLockImpl)
      val nativeSyncApiImpl =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) < 1) {
          NativeSyncApiImpl26(ctx)
        } else {
          NativeSyncApiImpl30(ctx)
        }
      val permissionApiImpl = PermissionApiImpl(ctx)
      NativeSyncApi.setUp(messenger, nativeSyncApiImpl)
      PermissionApi.setUp(messenger, permissionApiImpl)
      LocalImageApi.setUp(messenger, LocalImagesImpl(ctx))
      RemoteImageApi.setUp(messenger, RemoteImagesImpl(ctx))

      BackgroundWorkerFgHostApi.setUp(messenger, BackgroundWorkerApiImpl(ctx, messenger))
      ConnectivityApi.setUp(messenger, ConnectivityApiImpl(ctx))

      flutterEngine.plugins.add(ViewIntentPlugin())
      flutterEngine.plugins.add(backgroundEngineLockImpl)
      flutterEngine.plugins.add(nativeSyncApiImpl)
      flutterEngine.plugins.add(permissionApiImpl)
    }

    fun cancelPlugins(flutterEngine: FlutterEngine) {
      val nativeApi =
        flutterEngine.plugins.get(NativeSyncApiImpl26::class.java) as ImmichPlugin?
          ?: flutterEngine.plugins.get(NativeSyncApiImpl30::class.java) as ImmichPlugin?
      nativeApi?.detachFromEngine()
      val permissionApi = flutterEngine.plugins.get(PermissionApiImpl::class.java) as ImmichPlugin?
      permissionApi?.detachFromEngine()
    }

    /**
     * Walk the persisted RUFH sessions, verify each against the server, and
     * either re-enqueue the ones still alive or drop the ones that are gone.
     * Called from onCreate before Flutter's upload pipeline has a chance to
     * re-enqueue the same files.
     */
    private suspend fun reconcileRufhSessions(ctx: Context) {
      try {
        val store = RufhSessionStore(ctx)
        val sessions = store.listAll()
        Log.i(TAG, "reconciling ${sessions.size} stored session(s)")
        if (sessions.isEmpty()) return

        for (session in sessions) {
          val file = File(session.filePath)
          if (!file.exists() ||
              file.length() != session.fileSize ||
              file.lastModified() != session.fileMtime
          ) {
            Log.i(TAG, "dropping ${session.uploadId}: source file changed or missing")
            store.remove(session.assetId)
            continue
          }

          val client = HttpClientManager.getClient()
            .newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
          val uploader = RufhUploader(client, session.serverUrl)
          val offset = runCatching { uploader.head(session.uploadId) }.getOrDefault(-1L)

          if (offset < 0) {
            Log.i(TAG, "dropping ${session.uploadId}: server returned 404")
            store.remove(session.assetId)
            continue
          }

          Log.i(TAG, "re-enqueueing ${session.uploadId} from offset $offset")
          RufhUploadWorker.enqueue(
            ctx,
            RufhUploadRequest(
              assetId = session.assetId,
              filePath = session.filePath,
              fileSize = session.fileSize,
              fileMtime = session.fileMtime,
              serverUrl = session.serverUrl,
            ),
          )
        }
      } catch (e: Throwable) {
        Log.e(TAG, "reconcile pass failed", e)
      }
    }
  }
}
