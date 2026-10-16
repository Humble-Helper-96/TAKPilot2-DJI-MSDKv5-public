package com.dji.sdk.sample.tak

import java.util.Locale
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.camera.CameraSDCardState
import dji.sdk.keyvalue.value.camera.CameraStorageLocation
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import com.taklite.util.AppLog

/**
 * Where the camera will actually write, for the home screen's pre-flight readout.
 *
 * ⚠ THIS EXISTS BECAUSE OF A REAL SILENT FAILURE. A camera was once pointed
 * at internal memory, recording refused to start, and NOTHING on the flight screen said why —
 * an installed SD card is not enough if the camera is not writing to it. A pilot who cannot
 * see the storage target before take-off finds out afterwards, when the recording they needed
 * does not exist.
 *
 * So the readout is deliberately blunt about the one case that loses footage: RED means "you
 * will get no recording" — the target is internal memory, or the card is full, missing or
 * unusable. Green means a verified card. Amber means the camera has not answered yet, which
 * is its own state and must never be painted as "fine".
 *
 * ⚠ READS USE THE TWO-ARGUMENT getValue. The one-argument form reads MSDK's
 * local cache and answers null for ever on a cold start.
 */
object AircraftStorage {

    private const val TAG = "AircraftStorage"
    private val MAIN_CAM = ComponentIndexType.LEFT_OR_MAIN

    /** What the camera says it will write to, or null before it answers. */
    @Volatile
    var location: CameraStorageLocation? = null
        private set

    /** The card's own health, or null before the camera answers. */
    @Volatile
    var sdState: CameraSDCardState? = null
        private set

    /** Free space on the card in MB, or null if unreported. */
    @Volatile
    var sdFreeMb: Int? = null
        private set

    /** The card's capacity, MB, from KeyCameraStorageInfos — this SDK reports it, so
     *  Pre-Flight shows free OF total. Null until read. */
    @Volatile
    var sdTotalMb: Int? = null
        private set

    /** True only when the camera will write to a card that is genuinely usable. */
    val willRecord: Boolean
        get() = location == CameraStorageLocation.SDCARD && sdState == CameraSDCardState.NORMAL

    /** True when the target is internal memory — the case that silently loses a recording. */
    val recordingToInternal: Boolean
        get() = location == CameraStorageLocation.INTERNAL ||
            location == CameraStorageLocation.INTERNAL_SSD

    fun refresh(onDone: (() -> Unit)? = null) {
        // Answer once: this SDK fires some completion callbacks twice, and the three
        // getters below answer on SDK threads. The old counter was a plain `var` tested with
        // `== 0`, which failed in BOTH directions: a lost update across threads, or a spurious
        // early decrement, stepped straight past 0 and `onDone` was then never called at all —
        // leaving the Pre-Flight storage row stuck on amber "STORAGE: —", which is precisely
        // the "you will get no recording" blind spot this object exists to prevent. Atomic
        // count, `<= 0`, and a one-shot gate so an extra fire can neither skip it nor repeat it.
        val outstanding = java.util.concurrent.atomic.AtomicInteger(4)
        val fired = java.util.concurrent.atomic.AtomicBoolean(false)
        fun step() {
            if (outstanding.decrementAndGet() > 0) return
            if (!fired.compareAndSet(false, true)) return
            AppLog.v(TAG, "storage read-back: location=$location sd=$sdState free=$sdFreeMb")
            onDone?.invoke()
        }

        KeyManager.getInstance().getValue(
            KeyTools.createKey(CameraKey.KeyCameraStorageLocation, MAIN_CAM),
            object : CommonCallbacks.CompletionCallbackWithParam<CameraStorageLocation> {
                override fun onSuccess(value: CameraStorageLocation?) { location = value; step() }
                override fun onFailure(error: IDJIError) { step() }
            })

        KeyManager.getInstance().getValue(
            KeyTools.createKey(CameraKey.KeyCameraSDCardState, MAIN_CAM),
            object : CommonCallbacks.CompletionCallbackWithParam<CameraSDCardState> {
                override fun onSuccess(value: CameraSDCardState?) { sdState = value; step() }
                override fun onFailure(error: IDJIError) { step() }
            })

        KeyManager.getInstance().getValue(
            KeyTools.createKey(CameraKey.KeySDCardRemainSpace, MAIN_CAM),
            object : CommonCallbacks.CompletionCallbackWithParam<Int> {
                override fun onSuccess(value: Int?) { sdFreeMb = value; step() }
                override fun onFailure(error: IDJIError) { step() }
            })

        KeyManager.getInstance().getValue(
            KeyTools.createKey(CameraKey.KeyCameraStorageInfos, MAIN_CAM),
            object : CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.camera.CameraStorageInfos> {
                override fun onSuccess(value: dji.sdk.keyvalue.value.camera.CameraStorageInfos?) {
                    val sd = value?.cameraStorageInfoList?.firstOrNull { it.storageType == CameraStorageLocation.SDCARD }
                    sdTotalMb = sd?.storageCapacity?.takeIf { it > 0 }
                    step()
                }
                override fun onFailure(error: IDJIError) { step() }
            })
    }

    /**
     * Formats the SD card. The callback carries the CAMERA's answer; the card state,
     * re-read by the caller's poll, is what says it finished. Run for real on the M4TD:
     * a card with ~90 GB free came back 118.9 of 118.9 GB. The refusal
     * reasons are the caller's, before this is reached.
     */
    fun formatSdCard(onResult: (IDJIError?) -> Unit) {
        AppLog.i(TAG, "format SD card requested")
        runCatching {
            KeyManager.getInstance().performAction(
                KeyTools.createKey(CameraKey.KeyFormatStorage, MAIN_CAM),
                CameraStorageLocation.SDCARD,
                object : CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                    override fun onSuccess(t: dji.sdk.keyvalue.value.common.EmptyMsg?) {
                        AppLog.i(TAG, "format SD card accepted by the camera"); onResult(null)
                    }
                    override fun onFailure(error: IDJIError) {
                        AppLog.w(TAG, "format SD card refused: ${error.description()}"); onResult(error)
                    }
                })
        }.onFailure { AppLog.w(TAG, "format SD card threw: ${it.message}"); onResult(null) }
    }

    /**
     * Clears every value read back from the aircraft. Call on disconnect — without this, a
     * swapped-in aircraft with no card at all still shows the PREVIOUS aircraft's green "SD
     * CARD" verdict, exactly the silent-recording-loss case this object exists to prevent.
     */
    fun resetOnDisconnect() {
        location = null
        sdState = null
        sdFreeMb = null
        sdTotalMb = null
    }

    /** The pilot-facing line. Short, because it sits on a card row. */
    fun label(): String = when {
        recordingToInternal ->
            "RECORDING TO INTERNAL MEMORY" + (freeLabel()?.let { " · $it FREE" } ?: "")
        location == CameraStorageLocation.SDCARD && sdState == CameraSDCardState.NORMAL ->
            "SD CARD" + (freeLabel()?.let { " · $it FREE" } ?: "")
        location == CameraStorageLocation.SDCARD ->
            "SD CARD: " + (sdState?.name?.replace('_', ' ') ?: "—")
        else -> "STORAGE: —"
    }

    private fun freeLabel(): String? {
        val mb = sdFreeMb ?: return null
        return if (mb >= 1024) "%.1f GB".format(Locale.US, mb / 1024.0) else "$mb MB"
    }
}
