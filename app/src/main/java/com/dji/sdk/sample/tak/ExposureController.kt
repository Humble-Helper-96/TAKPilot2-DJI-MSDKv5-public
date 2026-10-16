package com.dji.sdk.sample.tak

import android.content.Context
import com.taklite.util.AppLog
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.camera.CameraExposureCompensation
import dji.sdk.keyvalue.value.camera.CameraExposureMode
import dji.sdk.keyvalue.value.camera.CameraMeteringMode
import dji.sdk.keyvalue.value.camera.CameraMode
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager

/**
 * Forces a consistent auto-exposure setup on the camera so FPV/recording adapts to changing
 * light instead of running on whatever the DJI app last left it in.
 *
 * Everything is a CameraKey set through KeyManager. The strategy — VIDEO mode first, CENTER
 * metering, PROGRAM (full auto) exposure, pilot EV slider with a hidden brightness bias — was
 * tuned on a Mini 2; re-verify the bias and the +3.0 EV cap on the Matrice 4T before trusting
 * them.
 *
 * Rule: do not trust onSuccess — read back what the camera actually
 * applied ([logReadback]).
 */
object ExposureController {
    private const val TAG = "TP2Exposure"
    private const val PREFS = "takpilot2_tak"
    private const val KEY_EV = "exposure_ev"

    /** Same convention as the flight activity's `MAIN_CAM` — every camera key this object
     *  writes/reads is explicitly targeted at the main camera, never left to whatever the
     *  SDK defaults an untargeted key to. */
    private val MAIN_CAM = ComponentIndexType.LEFT_OR_MAIN

    private val EV_ZERO = CameraExposureCompensation.NEG_0EV

    /** Every real EV value in declaration order (-5.0 .. +5.0, 1/3-stop steps),
     *  excluding FIXED/UNKNOWN. */
    private val EV_ALL: List<CameraExposureCompensation> =
        CameraExposureCompensation.values().filter {
            it != CameraExposureCompensation.FIXED && it != CameraExposureCompensation.UNKNOWN
        }

    /** The pilot slider's range: -2.0 .. +2.0 EV in 1/3 stops (13 steps). */
    val EV_SLIDER: List<CameraExposureCompensation> = EV_ALL.filter {
        val i = EV_ALL.indexOf(it)
        i in EV_ALL.indexOf(CameraExposureCompensation.NEG_2P0EV)..
            EV_ALL.indexOf(CameraExposureCompensation.POS_2P0EV)
    }

    val sliderMax: Int get() = EV_SLIDER.size - 1

    /** Hidden brightness bias, in 1/3-stop steps, added on top of whatever the pilot
     *  sees/sets. Tuned to +2/3 EV on a Mini 2 (paired with CENTER metering); re-tune on
     *  the M4T. */
    private const val HIDDEN_BIAS_STEPS = 2

    private fun biased(nominal: CameraExposureCompensation): CameraExposureCompensation {
        val i = EV_ALL.indexOf(nominal)
        return EV_ALL[(i + HIDDEN_BIAS_STEPS).coerceIn(0, EV_ALL.size - 1)]
    }

    /** Inverse of [biased] — used only to turn a READ-BACK (the camera's own value, which
     *  carries the hidden bias) back into the nominal figure the slider/label show the pilot. */
    private fun unbiased(actual: CameraExposureCompensation): CameraExposureCompensation {
        val i = EV_ALL.indexOf(actual)
        if (i < 0) return actual
        return EV_ALL[(i - HIDDEN_BIAS_STEPS).coerceIn(0, EV_ALL.size - 1)]
    }

    /** Stored EV, clamped into the slider range. */
    fun savedEv(context: Context): CameraExposureCompensation {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_EV, null)
        val stored = name?.let {
            runCatching { CameraExposureCompensation.valueOf(it) }.getOrNull()
        } ?: EV_ZERO
        val fi = EV_ALL.indexOf(stored)
        if (fi < 0) return EV_ZERO
        val lo = EV_ALL.indexOf(EV_SLIDER.first())
        val hi = EV_ALL.indexOf(EV_SLIDER.last())
        return EV_ALL[fi.coerceIn(lo, hi)]
    }

    private fun saveEv(context: Context, ev: CameraExposureCompensation) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_EV, ev.name).apply()
    }

    /** Slider position (0..[sliderMax]) matching the stored EV. */
    fun savedSliderIndex(context: Context): Int =
        EV_SLIDER.indexOf(savedEv(context)).coerceAtLeast(0)

    fun labelAt(index: Int): String = evLabel(EV_SLIDER[index.coerceIn(0, sliderMax)])

    /** NEG_2P0EV -> "-2.0", NEG_0EV -> "0.0", POS_1P3EV -> "+1.3". */
    private fun evLabel(ev: CameraExposureCompensation): String = when {
        ev == EV_ZERO -> "0.0"
        ev.name.startsWith("NEG_") ->
            "-" + ev.name.removePrefix("NEG_").removeSuffix("EV").replace('P', '.')
        ev.name.startsWith("POS_") ->
            "+" + ev.name.removePrefix("POS_").removeSuffix("EV").replace('P', '.')
        else -> "0.0"
    }

    /** Human shutter label from a CameraShutterSpeed enum NAME, tolerant of both the
     *  SHUTTER_SPEED_1_60 and SHUTTER_SPEED_1_12_DOT_5 shapes. Null in, null out. */
    fun shutterLabel(name: String?): String? {
        val raw = name?.removePrefix("SHUTTER_SPEED_")?.replace("_DOT_", ".") ?: return null
        return if (raw.startsWith("1_")) "1/" + raw.removePrefix("1_").replace('_', '.')
        else raw.replace('_', '.')
    }

    /** Numeric ISO from a CameraISO enum NAME ("ISO_100" -> 100); null for AUTO/FIXED/unknown. */
    fun isoValue(name: String?): Int? =
        name?.removePrefix("ISO_")?.toIntOrNull()

    /**
     * Push the exposure setup to the camera (called on connect). Switches the camera to
     * VIDEO mode FIRST — video-exposure settings don't drive the live FPV until the camera
     * is in video mode. v5 has one mode key (KeyCameraMode).
     *
     * @param onVideoMode reports whether the camera accepted VIDEO mode. Callers that
     *   switch modes around a still need this: the camera rejects a mode change while it is
     *   still writing a photo, and without the result the failure is invisible.
     */
    fun applyDefaults(
        context: Context,
        onVideoMode: (IDJIError?) -> Unit = {},
    ) {
        AppLog.i(TAG, "applyDefaults: VIDEO mode -> CENTER metering, PROGRAM (full auto), " +
            "ev=${savedEv(context)}")
        // Answer once: a second fire would run the whole three-write exposure chain
        // (metering, exposure mode, EV) again, interleaved with the first pass — and would
        // deliver onVideoMode twice, which at the photo-sequence caller means a duplicate
        // endPhotoSequence(), or a spurious retry ladder writing camera modes the camera has
        // already left. A success-then-failure pair would do both.
        val answered = java.util.concurrent.atomic.AtomicBoolean(false)
        KeyManager.getInstance().setValue(
            KeyTools.createKey(CameraKey.KeyCameraMode, MAIN_CAM),
            CameraMode.VIDEO_NORMAL,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    if (!answered.compareAndSet(false, true)) {
                        AppLog.w(TAG, "set VIDEO mode: duplicate completion (success) ignored"); return
                    }
                    AppLog.i(TAG, "set VIDEO mode: OK")
                    applyExposureSettings(context)
                    onVideoMode(null)
                }

                override fun onFailure(error: IDJIError) {
                    if (!answered.compareAndSet(false, true)) {
                        AppLog.w(TAG, "set VIDEO mode: duplicate completion (failure) ignored"); return
                    }
                    // Only chase exposure if the mode switch landed — otherwise every call
                    // fails too and buries the ONE line that mattered.
                    AppLog.i(TAG, "set VIDEO mode: ${error.description()}")
                    onVideoMode(error)
                }
            },
        )
    }

    /** Metering mode + exposure mode + biased EV — independent of camera mode, so calling
     *  this right after switching to VIDEO_NORMAL or PHOTO_NORMAL guarantees the same total
     *  exposure either way. CENTER metering + PROGRAM auto. */
    fun applyExposureSettings(context: Context, onDone: () -> Unit = {}) {
        KeyManager.getInstance().setValue(
            KeyTools.createKey(CameraKey.KeyCameraMeteringMode, MAIN_CAM),
            CameraMeteringMode.CENTER,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() = afterMetering(context, "OK", onDone)
                // ⚠ description() is a Java method, thus Kotlin sees a platform type and lets
                // it go to a non-null String parameter with no warning. The M4T returns NULL
                // here when it refuses the metering write, and the compiler's null check then
                // crashed the flight screen on open (measured). Keep the fallback.
                // The other description() call sites in this tree are string templates or `+`
                // concatenation, which take null safely; an argument position does not.
                override fun onFailure(error: IDJIError) =
                    afterMetering(context, error.description() ?: "refused (no description)", onDone)
            },
        )
    }

    private fun afterMetering(context: Context, meteringResult: String, onDone: () -> Unit) {
        AppLog.i(TAG, "setMeteringMode(CENTER): $meteringResult")
        KeyManager.getInstance().setValue(
            KeyTools.createKey(CameraKey.KeyExposureMode, MAIN_CAM),
            CameraExposureMode.PROGRAM,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    AppLog.i(TAG, "setExposureMode(PROGRAM): OK")
                    val ev = biased(savedEv(context))
                    KeyManager.getInstance().setValue(
                        KeyTools.createKey(CameraKey.KeyExposureCompensation, MAIN_CAM),
                        ev,
                        object : CommonCallbacks.CompletionCallback {
                            override fun onSuccess() {
                                AppLog.i(TAG, "setExposureCompensation($ev) [biased]: OK")
                                logReadback()
                                onDone()
                            }

                            override fun onFailure(error: IDJIError) {
                                AppLog.i(TAG, "setExposureCompensation($ev) [biased]: " +
                                    error.description())
                                logReadback()
                                onDone()
                            }
                        },
                    )
                }

                override fun onFailure(error: IDJIError) {
                    AppLog.i(TAG, "setExposureMode(PROGRAM): ${error.description()}")
                    onDone()
                }
            },
        )
    }

    /** Read back what the camera actually applied — the definitive check that the setup
     *  stuck (vs. reporting OK but silently reverting). */
    private fun logReadback() {
        readback(CameraKey.KeyExposureMode, "exposureMode")
        readback(CameraKey.KeyShutterSpeed, "shutter")
        readback(CameraKey.KeyISO, "iso")
    }

    private fun <T> readback(keyInfo: dji.sdk.keyvalue.key.DJIKeyInfo<T>, label: String) {
        KeyManager.getInstance().getValue(
            KeyTools.createKey(keyInfo, MAIN_CAM),
            object : CommonCallbacks.CompletionCallbackWithParam<T> {
                override fun onSuccess(v: T?) { AppLog.i(TAG, "readback $label=$v") }
                override fun onFailure(error: IDJIError) {
                    AppLog.i(TAG, "readback $label failed: ${error.description()}")
                }
            },
        )
    }

    /** Apply the EV at slider [index] (nominal, as displayed), persisting only on success.
     *  The camera is actually sent [biased] on top of it. */
    fun setEvAt(context: Context, index: Int, onDone: (String) -> Unit) {
        val nominal = EV_SLIDER[index.coerceIn(0, sliderMax)]
        val ev = biased(nominal)
        val key = KeyTools.createKey(CameraKey.KeyExposureCompensation, MAIN_CAM)
        KeyManager.getInstance().setValue(
            key,
            ev,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    AppLog.i(TAG, "setExposureCompensation($ev) [biased from $nominal]: OK")
                    // A success callback is not proof. Read back what the
                    // camera actually holds before persisting/labeling — the label the pilot
                    // sees, and the value the next screen entry adopts, must be the aircraft's
                    // answer, not the request we just sent.
                    KeyManager.getInstance().getValue(key,
                        object : CommonCallbacks.CompletionCallbackWithParam<CameraExposureCompensation> {
                            override fun onSuccess(v: CameraExposureCompensation?) {
                                val readNominal = v?.let { unbiased(it) } ?: nominal
                                AppLog.i(TAG, "exposure compensation read back: $v -> $readNominal")
                                saveEv(context, readNominal)
                                onDone(evLabel(readNominal))
                            }

                            override fun onFailure(error: IDJIError) {
                                AppLog.w(TAG, "exposure compensation read-back failed: " +
                                    error.description())
                                // The write's own onSuccess already fired, so trust the request
                                // as the least-bad answer rather than leaving the pilot's slider
                                // unresponsive because the read-back alone timed out.
                                saveEv(context, nominal)
                                onDone(evLabel(nominal))
                            }
                        })
                }

                override fun onFailure(error: IDJIError) {
                    AppLog.i(TAG, "setExposureCompensation($ev) [biased from $nominal]: " +
                        error.description())
                    onDone(evLabel(nominal))
                }
            },
        )
    }
}
