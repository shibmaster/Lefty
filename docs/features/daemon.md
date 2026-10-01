# Daemon Mode

**Last verified:** 2026-10-01

Kai's daemon mode keeps the app running in the background on Android so that scheduled tasks, heartbeat checks, and email polling continue to execute even when the app is not in the foreground. On other platforms (desktop, iOS, web), daemon mode is a no-op.

## Concepts

### Daemon Controller

A platform-abstracted interface with `start()` and `stop()` methods. On Android, it manages an Android foreground service. On all other platforms, it does nothing.

### Foreground Service

An Android service that runs with a persistent notification, preventing the system from killing the process. Kai's service uses the `dataSync` foreground service type and `IMPORTANCE_LOW` notification priority to minimize user disruption.

## Service Lifecycle

1. When daemon mode is enabled in settings, the foreground service is started
2. The service creates a notification channel and displays a persistent "Daemon is running" notification
3. The service starts the task scheduler, which owns its own process-lifetime scope
4. The service returns `START_STICKY`, so Android restarts it if the system kills it
5. The service type is `specialUse`. Unlike `dataSync`, which Android 15+ limits to about 6 hours per day for apps targeting recent SDKs, it has no daily runtime cap, so heartbeats keep running around the clock. The manifest describes the use under `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`
6. When daemon mode is disabled, the service stops itself and removes the foreground notification
7. On Android 12+, starting the service can silently fail with a `ForegroundServiceStartNotAllowedException` when the app isn't in the foreground; this is caught and ignored so the daemon can be retried later (e.g. on next app launch)

## Auto-Start

Every time the main activity is brought to the foreground, if daemon mode is enabled in settings, the service is (re)started. This runs on app launch as well as on every subsequent return to the foreground, so the daemon recovers from situations where OEM battery managers or aggressive task killers have terminated the service while the app was in the background.

The daemon also restarts on its own **after a reboot** (`BOOT_COMPLETED`) and **after the app is updated** (`MY_PACKAGE_REPLACED` — installing a new APK kills the running service), when daemon mode is enabled.

## Background Work

The daemon's task scheduler polls every 60 seconds and handles four types of background work:

- **Scheduled tasks** — executes due tasks by sending their prompts through the AI pipeline
- **Heartbeat checks** — periodic self-checks during active hours (see heartbeat doc)
- **Email polling** — fetches new emails from configured accounts on a configurable interval
- **SMS polling** — checks for new incoming SMS messages on a configurable interval



## Notification

- **Channel**: "Lefty Background Service" with low importance
- **Content**: "Daemon is running" with a sync icon
- **Tap action**: Opens the app's main screen
- The notification is required by Android for foreground services and cannot be hidden

## Permissions

The app declares these permissions in the Android manifest:

- `FOREGROUND_SERVICE` — required for all foreground services
- `FOREGROUND_SERVICE_SPECIAL_USE` — the daemon's `specialUse` service type (`FOREGROUND_SERVICE_DATA_SYNC` remains for model downloads)
- `RECEIVE_BOOT_COMPLETED` — restart after reboot
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — foss flavor only (Play restricts it): lets the app ask to be exempted from battery optimization

No wake locks are held. When daemon mode is turned on, the app asks the system to exempt it from battery optimization (so Doze and OEM battery managers don't pause the 60-second poll); the service otherwise relies on `START_STICKY` for restart behavior.

## Settings UI

A toggle labeled "Daemon Mode" appears in the General tab of settings, only on Android. The description reads: "Keep Lefty running in the background so scheduled tasks execute even when the app is not in the foreground." Toggling it starts or stops the foreground service and persists the preference. On Android 13+, turning the toggle on also requests the notification permission, since the foreground service's persistent notification cannot be displayed without it, and then the battery-optimization exemption. While daemon mode is on and the app is not exempt, the card shows "Battery optimization may pause background checks" with an **Allow** button; the state is re-checked whenever the settings screen resumes.

## Key Files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/.../DaemonController.kt` | Platform-independent interface, plus the `NoOpDaemonController` every non-Android target returns |
| `composeApp/src/androidMain/.../DaemonController.android.kt` | Android implementation, start/stop/auto-start logic |
| `composeApp/src/androidMain/.../DaemonService.kt` | Android foreground service, notification, task scheduler startup |
| `composeApp/src/androidMain/.../DaemonBootReceiver.kt` | Restarts the daemon after reboot and app update |
| `androidApp/src/main/.../MainActivity.kt` | Auto-start (and recovery) on every foreground transition via `onStart` |
| `androidApp/src/main/AndroidManifest.xml` | Service declaration and permissions |
| `composeApp/src/commonMain/.../data/TaskScheduler.kt` | Background poll loop started by the service |
| `composeApp/src/commonMain/.../data/AppSettings.kt` | Daemon enabled state persistence |
| `composeApp/src/commonMain/.../ui/settings/SettingsScreen.kt` | Daemon mode toggle UI |
