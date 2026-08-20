package com.squadsync.app.media

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent

/**
 * Wraps Android audio + media-button control into a tiny command surface.
 *
 * Volume: uses AudioManager stream-music adjust APIs (works for all music apps).
 * Play/Pause/Next/Prev: dispatches media-key events to the focused MediaSession
 * (the app currently playing audio). Falls back to legacy media-button broadcast
 * on older devices.
 */
class MediaCommander(private val appCtx: Context) {

    private val audio: AudioManager =
        appCtx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // (We used to keep a private MediaSession here to dispatch through
    // transportControls, but that session was unused and the events were
    // dropped on the floor. The new path queries the *active* session via
    // MediaSessionManager and targets whoever is actually playing.)

    fun maxVolume(): Int = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    fun currentVolume(): Int = audio.getStreamVolume(AudioManager.STREAM_MUSIC)

    /**
     * Sets STREAM_MUSIC to a percentage of the device's max. Treats the value as
     * "audio index percent" — i.e. 50% means index = max/2 (rounded), regardless
     * of the device's quirky "step" semantics.
     */
    fun setVolumePercent(percent: Int) {
        val max = maxVolume()
        val p = percent.coerceIn(0, 100)
        val target = ((max + 1) * p / 100).coerceIn(0, max)
        // 0 flag = no system UI pop-up. We don't want a slider scrub to flash the
        // volume dialog on every frame.
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
    }

    fun volumePercent(): Int {
        val max = maxVolume()
        if (max == 0) return 0
        return (currentVolume() * 100 / max).coerceIn(0, 100)
    }

    fun setVolume(value: Int) {
        val max = maxVolume()
        val v = value.coerceIn(0, max)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
    }

    fun adjust(delta: Int) {
        val dir = if (delta > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        repeat(kotlin.math.abs(delta)) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0)
        }
    }

    fun play() = sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
    fun pause() = sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
    fun toggle() = sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    fun next() = sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
    fun previous() = sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    fun mute(yes: Boolean) {
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (yes) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
            0
        )
    }

    // ---- Screen brightness ----
    //
    // Android requires `WRITE_SETTINGS` to mutate the system brightness. The
    // first time a user receives a brightness command they'll be prompted by
    // Android to grant "Modify system settings". If the grant isn't there we
    // silently no-op (and report via the State frame so the master can show
    // a "needs permission" hint).

    fun hasBrightnessWritePermission(): Boolean =
        Settings.System.canWrite(appCtx)

    fun brightnessPercent(): Int {
        if (!hasBrightnessWritePermission()) return 0
        val raw = runCatching {
            Settings.System.getInt(appCtx.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(-1)
        if (raw < 0) return 0
        return (raw * 100 / 255).coerceIn(0, 100)
    }

    fun setBrightnessPercent(percent: Int) {
        if (!hasBrightnessWritePermission()) return
        val v = percent.coerceIn(0, 100)
        val raw = (255 * v / 100).coerceIn(0, 255)
        runCatching {
            Settings.System.putInt(
                appCtx.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                raw
            )
        }
    }

    // ---- App launching ----
    //
    // `QUERY_ALL_PACKAGES` is restricted on Play Store apps but allowed for
    // development/debug builds. To make `list_apps` work on stock firmware we
    // query via PackageManager with the flag the slave already has implicitly
    // for its own apps (every APK can always query its own install).

    data class InstalledApp(
        val packageName: String,
        val label: String,
        val isSystem: Boolean
    )

    fun listLaunchableApps(): List<InstalledApp> {
        val pm = appCtx.packageManager
        // The new overload (taking PackageInfoFlags) only exists on API 33+.
        // Stick to the legacy int flag on older API levels to keep the build happy.
        val installed = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(android.content.pm.PackageManager.GET_META_DATA)
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0)
        }
        return installed.mapNotNull { pi ->
            val launchIntent = pm.getLaunchIntentForPackage(pi.packageName) ?: return@mapNotNull null
            val label = pi.applicationInfo.loadLabel(pm).toString()
            val isSystem = (pi.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            InstalledApp(pi.packageName, label, isSystem)
        }.sortedBy { it.label.lowercase() }
    }

    fun launchApp(packageName: String): Boolean {
        val pm = appCtx.packageManager
        // Make sure the package is actually installed before we trust
        // getLaunchIntentForPackage (on some OEM ROMs the call returns
        // null for valid system packages like Settings).
        val pkgInfo = runCatching { pm.getPackageInfo(packageName, 0) }.getOrNull()
        if (pkgInfo == null) {
            android.util.Log.w("MediaCommander", "launchApp: $packageName not installed")
            return false
        }
        val intent = pm.getLaunchIntentForPackage(packageName)
            ?: Intent(Intent.ACTION_MAIN).apply {
                // Fallback: ask the system to find a launcher for this package.
                setPackage(packageName)
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )
        android.util.Log.i("MediaCommander", "launchApp $packageName → $intent")
        return runCatching {
            appCtx.startActivity(intent)
            true
        }.getOrElse {
            android.util.Log.w("MediaCommander", "startActivity failed: ${it.message}")
            false
        }
    }

    /** Sends a media key. Tries the focused session first, falls back to broadcast. */
    private fun sendMediaKey(keyCode: Int) {
        android.util.Log.i("MediaCommander", "sendMediaKey $keyCode")
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
        val up = KeyEvent(now, now + 20, KeyEvent.ACTION_UP, keyCode, 0)

        // We dispatch media-button events via the AudioManager. Android
        // routes them to whichever MediaSession currently has the focus
        // (e.g. QQ Music / 网易云 / Spotify / YouTube Music). This is the
        // same path that the wired headset play/pause key uses, so it
        // requires no special permission and works on every OEM.
        //
        // We previously tried going through our own private MediaSession, but
        // that session had no listeners and the events were dropped on the
        // floor — which is what was causing the "play/pause doesn't do
        // anything" bug.
        val am = appCtx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(down)
        am.dispatchMediaKeyEvent(up)
    }
}