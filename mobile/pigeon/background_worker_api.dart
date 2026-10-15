import 'package:pigeon/pigeon.dart';

// RUFH Upload Class
class RufhUploadRequest {
  final String assetId;
  final String filePath;
  final int fileSize;
  final int fileMtime;
  final String serverUrl;
}

@FlutterApi()
abstract class RufhProgressFlutterApi {
  @async
  void onRufhProgress(String assetId, int committedOffset, int totalSize);
}

@ConfigurePigeon(
  PigeonOptions(
    dartOut: 'lib/platform/background_worker_api.g.dart',
    swiftOut: 'ios/Runner/Background/BackgroundWorker.g.swift',
    swiftOptions: SwiftOptions(includeErrorClass: false),
    kotlinOut: 'android/app/src/main/kotlin/app/alextran/immich/background/BackgroundWorker.g.kt',
    kotlinOptions: KotlinOptions(package: 'app.alextran.immich.background'),
    dartOptions: DartOptions(),
    dartPackageName: 'immich_mobile',
  ),
)
class BackgroundWorkerSettings {
  final bool requiresCharging;
  final int minimumDelaySeconds;

  const BackgroundWorkerSettings({required this.requiresCharging, required this.minimumDelaySeconds});
}

@HostApi()
abstract class BackgroundWorkerFgHostApi {
  void enable();

  void saveNotificationMessage(String title, String body);

  void configure(BackgroundWorkerSettings settings);

  void disable();

  // iOS Only: true when the OS started the process in the background for one of its background tasks
  bool wasLaunchedInBackground();

  /// Enqueue a RUFH upload. Returns immediately; the transfer runs in a
  /// WorkManager foreground service independent of the Flutter engine.
  void enqueueRufhUpload(RufhUploadRequest request);

  void cancelRufhUpload(String assetId);
}

@HostApi()
abstract class BackgroundWorkerBgHostApi {
  // Called from the background flutter engine when it has bootstrapped and established the
  // required platform channels to notify the native side to start the background upload
  void onInitialized();

  // Called from the background flutter engine to request the native side to cleanup
  void close();
}

@FlutterApi()
abstract class BackgroundWorkerFlutterApi {
  // iOS Only: Called when the iOS background upload is triggered
  @async
  void onIosUpload(bool isRefresh, int? maxSeconds);

  // Android Only: Called when the Android background upload is triggered
  // Returns whether the native side needs to retry the run
  @async
  bool onAndroidUpload(int? maxMinutes);

  @async
  void cancel();
}
