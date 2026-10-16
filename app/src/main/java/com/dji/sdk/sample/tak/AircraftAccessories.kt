package com.dji.sdk.sample.tak

import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.payload.WidgetType
import dji.sdk.keyvalue.value.payload.WidgetValue
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.megaphone.MegaphoneIndex
import dji.v5.manager.aircraft.megaphone.MegaphoneManager
import dji.v5.manager.aircraft.megaphone.MegaphoneStatus
import dji.v5.manager.aircraft.megaphone.PlayMode
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.payload.PayloadCenter
import dji.v5.manager.aircraft.payload.PayloadIndexType
import dji.v5.manager.aircraft.payload.widget.PayloadWidget
import java.util.concurrent.atomic.AtomicBoolean
import com.taklite.util.AppLog

/**
 * THE TWO PAYLOAD ACCESSORIES on the Matrice 4TD: the AL1 LIGHT and the AS1 SPEAKER.
 *
 * The aircraft's own exterior lights are a different thing and live in [AircraftLights]. This
 * object is only for what is bolted on.
 *
 * ⚠ THE TWO ACCESSORIES REACH THE SDK BY TWO COMPLETELY DIFFERENT ROUTES. This was MEASURED on
 * a Matrice 4TD, after two wrong guesses; the aircraft's own enumeration reads:
 *
 * ```
 * payload port UP:       connected=true name='Speaker'     type=DJI_SELF_RESEARCH_PSDK
 * payload port EXTERNAL: connected=true name='Searchlight' type=DJI_SELF_RESEARCH_PSDK
 * ```
 *
 * | Accessory | Route | Why |
 * |---|---|---|
 * | AS1 speaker | `MegaphoneManager` at index `UPSIDE` | A real manager, and the M4D capability file declares it |
 * | AL1 light | PSDK **WIDGETS** on port `EXTERNAL` | Every light KEY is refused on this airframe |
 *
 * ⚠ THE LIGHT IS NOT DRIVEN BY THE LIGHT KEYS. `SpotLightKey` and `PayloadKey.KeyLightCtrl` both
 * looked like the answer and both are wrong here. `KeyLightCtrl` returned `refused: null` on
 * every write. What the aircraft actually offers is three PSDK widgets, and they are the whole
 * control surface of the AL1:
 *
 * ```
 * widget @EXTERNAL: type=SWITCH name='on/off'      idx=0
 * widget @EXTERNAL: type=RANGE  name='brightiness' idx=1     (DJI's spelling, not ours)
 * widget @EXTERNAL: type=SWITCH name='blink'       idx=2
 * ```
 *
 * ⚠ AND "IT ANSWERED" IS NOT "IT IS THERE". Before the enumeration existed, this file walked the
 * light keys across ports and the SPEAKER's port answered `KeyLightState` with a complete,
 * plausible reply — `mode=CLOSE type=FLOOD brightness=10`. It latched that port, drew a light
 * control for a port with no light in it, and every write then failed. A payload is now found by
 * WHAT THE AIRCRAFT NAMES IT, never by which key happens to reply.
 *
 * ⚠ EVERY FIELD HERE IS THE AIRCRAFT'S ANSWER, NEVER OUR REQUEST. The widget
 * values arrive by a PUSH listener, so the light's state is whatever the payload last reported.
 *
 * ⚠ LISTENERS ARE ATTACHED ONCE, in [start] (a listener slot holds one client and
 * a second registration replaces the first with no warning). [reset] is the only way to detach.
 *
 * ⚠ These are WRITES TO A PAYLOAD, thus on an explicit pilot action only, never on a timer.
 */
object AircraftAccessories {

    private const val TAG = "Accessories"

    /**
     * ⚠ THERE IS NO SETTLE TIME, AND THERE MUST NOT BE ONE. This used to wait a fixed 800ms and
     * then sample the widget cache. Measured: the payload's answer arrived 19ms after that
     * sample:
     *
     * ```
     * 21:34:08.419  light on/off: read-back = 0 (asked 1)      <- sampled here
     * 21:34:08.438  light widgets changed: on=1 bright=53      <- the truth, 19ms later
     * ```
     *
     * The light was on. The panel said it had refused, painted the pill off and left it there.
     * A timer cannot know when a payload has answered; the PUSH LISTENER knows, because the
     * answer IS the push. So a write now registers what it is waiting for ([pendingWrite]) and
     * [adoptLightWidgets] completes it the moment the value appears. The same race the other way
     * is why the OFF write passed and the ON write failed on the same afternoon — a fixed delay
     * turns a working control into an intermittent one.
     */
    private class PendingWrite(
        val what: String,
        val target: Int,
        val onResult: (Boolean) -> Unit,
        val done: AtomicBoolean = AtomicBoolean(false),
    )

    /**
     * Distinguishes one write from the next.
     *
     * ⚠ WITHOUT THIS A WATCHDOG FIRES AGAINST THE WRONG WRITE. Measured: the brightness
     * watchdog for a write of 100 fired 30ms AFTER a new write of 0 had started, matched it by
     * name alone, and reported the new write as refused before the payload had had any chance to
     * answer it. Identity, not name.
     */
    private val pendingSeq = java.util.concurrent.atomic.AtomicLong(0)

    @Volatile
    private var pendingWrite: PendingWrite? = null

    /**
     * How long a widget write may go unanswered before the panel takes itself back.
     *
     * Generous on purpose: a slow answer is still an answer, and cutting a working write short
     * would report a refusal that did not happen.
     */
    private const val WIDGET_TIMEOUT_MS = 2500L

    /**
     * The brightness scale for a PSDK RANGE widget.
     *
     * ⚠ A CONVENTION, NOT A READ, and the only thing in this file that is. `PayloadWidget`
     * exposes no minimum or maximum — there is nothing to ask. 0..100 is what DJI's own Pilot
     * interface uses for a RANGE, and the AL1's reported default of 10 fits it. If the light
     * behaves oddly at the top of the slider, this is the first suspect.
     */
    private val WIDGET_RANGE = 0..100

    // ------------------------------------------------------------------------------ the light

    /** The port the aircraft names as a light. Null until the enumeration finds one. */
    @Volatile
    var lightPort: PayloadIndexType? = null
        private set

    /** True when the light is lit, false when it is dark, NULL when nothing has answered. */
    @Volatile
    var lightOn: Boolean? = null
        private set

    /** Brightness as the payload reports it, on [WIDGET_RANGE]. Null is unknown. */
    @Volatile
    var lightBrightness: Int? = null
        private set

    /** The brightness scale, or null when the light has no brightness control. */
    @Volatile
    var lightBrightnessRange: IntRange? = null
        private set

    /**
     * True when the light is blinking. NULL when the light has no blink control at all — the
     * panel hides the row rather than showing a control that cannot work.
     */
    @Volatile
    var lightBlink: Boolean? = null
        private set

    private val widgetOnOff = java.util.concurrent.atomic.AtomicReference<PayloadWidget?>(null)
    private val widgetBrightness = java.util.concurrent.atomic.AtomicReference<PayloadWidget?>(null)
    private val widgetBlink = java.util.concurrent.atomic.AtomicReference<PayloadWidget?>(null)

    // ---------------------------------------------------------------------------- the speaker

    @Volatile
    var speakerVolume: Int? = null
        private set

    @Volatile
    var speakerPlayMode: PlayMode? = null
        private set

    @Volatile
    var speakerStatus: MegaphoneStatus? = null
        private set

    /** True once any speaker read has succeeded — the panel needs it to tell absent from unread. */
    @Volatile
    var speakerAnswered: Boolean = false
        private set

    /** The megaphone port that answered. Null until one does. */
    @Volatile
    var speakerIndex: MegaphoneIndex? = null
        private set

    /**
     * The megaphone ports to try if the aircraft will not say which one it holds. The M4TD
     * answered `UPSIDE` directly, which matches payload port `UP`, so the walk is a fallback
     * that should never run on this airframe.
     */
    private val candidateSpeakerIndexes = listOf(
        MegaphoneIndex.UPSIDE,
        MegaphoneIndex.PORTSIDE,
        MegaphoneIndex.STARBOARD,
        MegaphoneIndex.OSDK,
        MegaphoneIndex.PORT_1,
        MegaphoneIndex.PORT_2,
        MegaphoneIndex.PORT_3,
    )

    /**
     * The megaphone's fixed volume scale.
     *
     * ⚠ A CONSTANT, NOT A READ. `IMegaphoneManager` has no volume-range call — `setVolume(int)`
     * is all there is — so there is nothing to ask.
     */
    val speakerVolumeRange = 0..100

    @Volatile
    private var started = false

    /**
     * Attaches the payload listeners, ONCE.
     *
     * A listener slot holds one client, so attaching on every panel open would
     * quietly replace the previous registration. The listeners are a PUSH feed — once attached,
     * the light's state keeps itself current with no polling and no writes.
     */
    fun start() {
        if (started) return
        started = true
        try {
            val managers = PayloadCenter.getInstance().payloadManager
            if (managers.isEmpty()) {
                AppLog.i(TAG, "payload ports: the aircraft reports NONE")
                return
            }
            for ((index, mgr) in managers) {
                mgr.addPayloadBasicInfoListener { info ->
                    if (info == null) return@addPayloadBasicInfoListener
                    if (!info.isConnected) return@addPayloadBasicInfoListener
                    // isFeatureOpened is logged because a PSDK payload can enumerate, advertise
                    // its widgets and still refuse every write when its feature set is not
                    // opened to this application. If the writes go nowhere, look here first.
                    AppLog.i(TAG, "payload port $index: name='${info.payloadProductName}' " +
                        "type=${info.payloadType} featureOpened=${info.isFeatureOpened} " +
                        "sn=${info.serialNumber}")
                    // The names this airframe uses are 'Searchlight' and 'Speaker' — not "AL1"
                    // and not "AS1". Match on what the aircraft says, not on the model number.
                    val name = info.payloadProductName?.lowercase().orEmpty()
                    if (name.contains("speak") || name.contains("megaphone")) {
                        speakerPort = runCatching {
                            ComponentIndexType.find(index.value())
                        }.getOrNull()
                        AppLog.i(TAG, "the speaker is on port $index -> component $speakerPort")
                        probeAudioFiles()
                    }
                    if (name.contains("light") || name.contains("lamp")) {
                        if (lightPort != index) {
                            lightPort = index
                            AppLog.i(TAG, "the light is on port $index " +
                                "('${info.payloadProductName}')")
                        }
                    }
                    // ⚠ ASK EVERY CONNECTED PORT, NOT JUST THE LIGHT. The widget LISTENER was
                    // attached to all of them, but only the light was ever PULLED — so the
                    // speaker's widget list was never requested and the log showed nothing for
                    // it. That absence read as "the speaker advertises no widgets", which was
                    // never measured. A listener with nothing to listen to is not evidence.
                    pullWidgets(index)
                }
                mgr.addPayloadWidgetInfoListener { wi ->
                    if (index == lightPort) {
                        adoptLightWidgets(wi?.mainInterfaceWidgetList ?: emptyList())
                    } else {
                        // ⚠ EVERY OTHER PORT IS LOGGED, NOT IGNORED. The speaker's widgets were
                        // never looked at because this returned early on anything that was not
                        // the light — and a LIST widget carrying the loaded file names is the
                        // most likely place a file picker can come from, since MegaphoneManager
                        // has no file API at all. Logged once per port, on change only.
                        logOtherPortWidgets(index, wi)
                    }
                }
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "payload listener attach failed: ${t.message}")
        }
    }

    private fun pullWidgets(index: PayloadIndexType) {
        val mgr = try { PayloadCenter.getInstance().payloadManager[index] } catch (t: Throwable) { null }
            ?: return
        mgr.pullWidgetInfoFromPayload(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {}
            override fun onFailure(error: IDJIError) {
                // "Fetch icon file fail" is normal and harmless: the widget VALUES still arrive,
                // and this application draws its own controls rather than the payload's icons.
                AppLog.v(TAG, "pullWidgetInfo $index: ${error.description()}")
            }
        })
    }

    /**
     * Takes the payload's advertised controls and turns them into this object's state.
     *
     * The three widgets are matched BY NAME, with the type as a check. Matching by position
     * would be shorter and would break silently the day a firmware update reorders them.
     */
    private fun adoptLightWidgets(list: List<PayloadWidget>) {
        if (list.isEmpty()) return
        var on: PayloadWidget? = null
        var bright: PayloadWidget? = null
        var blink: PayloadWidget? = null
        for (w in list) {
            val n = w.widgetName?.lowercase().orEmpty()
            when {
                // 'brightiness' is DJI's spelling on this payload. Matching on "bright" catches
                // it and the correct spelling both.
                w.widgetType == WidgetType.RANGE && n.contains("bright") -> bright = w
                w.widgetType == WidgetType.SWITCH &&
                    (n.contains("blink") || n.contains("strobe") || n.contains("flash")) -> blink = w
                w.widgetType == WidgetType.SWITCH && n.contains("on") && n.contains("off") -> on = w
                else -> AppLog.v(TAG, "light widget not used: type=${w.widgetType} name='$n'")
            }
        }
        widgetOnOff.set(on)
        widgetBrightness.set(bright)
        widgetBlink.set(blink)
        lightOn = on?.let { it.widgetValue != 0 }
        lightBrightness = bright?.widgetValue
        lightBrightnessRange = if (bright != null) WIDGET_RANGE else null
        lightBlink = blink?.let { it.widgetValue != 0 }
        // ONE line, and only when something moved. The push fires several times a second and an
        // unconditional log buried every other accessory line in the file.
        val stamp = "on=${on?.widgetValue} bright=${bright?.widgetValue} blink=${blink?.widgetValue}"
        if (stamp != lastWidgetStamp) {
            lastWidgetStamp = stamp
            AppLog.i(TAG, "light widgets changed: $stamp  [frame: ${list.size} widgets — " +
                list.joinToString { "${it.widgetName}=${it.widgetValue}" } + "]")
            // ⚠ THE PANEL MUST HEAR THIS. The widgets are a PUSH feed, so a light changed from
            // DJI Pilot 2, from the payload itself, or by a write that lands after its own
            // callback would otherwise never reach the screen. The pill has stayed dark while
            // the light was lit for exactly this reason.
            notifyChanged()
        }
        // A write is finished by the value ARRIVING, never by a timer.
        settlePending()
    }

    /**
     * Told whenever the aircraft's accessory state moves, from any cause. The flight screen sets
     * this to repaint the panel; it is cleared in [reset] so a dead screen is never called.
     */
    @Volatile
    var onChanged: (() -> Unit)? = null

    private fun notifyChanged() {
        val cb = onChanged ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post { cb() }
    }

    @Volatile
    private var lastWidgetStamp: String? = null

    private val otherPortStamps = java.util.Collections.synchronizedMap(HashMap<String, String>())

    /** Diagnostic: what a NON-light payload advertises. See the widget listener in [start]. */
    private fun logOtherPortWidgets(
        index: PayloadIndexType,
        wi: dji.v5.manager.aircraft.payload.data.PayloadWidgetInfo?,
    ) {
        val main = wi?.mainInterfaceWidgetList ?: emptyList()
        val config = wi?.configInterfaceWidgetList ?: emptyList()
        val sp = wi?.speakerWidget
        val stamp = main.joinToString { "${it.widgetName}=${it.widgetValue}" } + "|" +
            config.joinToString { "${it.widgetName}=${it.widgetValue}" } +
            "|tts=${sp?.isTTSEnabled}/voice=${sp?.isVoiceEnabled}"
        if (otherPortStamps.put(index.name, stamp) == stamp) return
        // ⚠ THIS IS THE PAYLOAD DECLARING WHAT IT CAN DO. Measured: the AS1 on port UP
        // answered tts=true voice=true — TEXT-TO-SPEECH AND VOICE-FILE UPLOAD ARE SUPPORTED by
        // this speaker. It only appeared once EVERY port was pulled instead of the light alone.
        if (sp != null) {
            AppLog.i(TAG, "port $index speakerWidget: tts=${sp.isTTSEnabled} " +
                "voice=${sp.isVoiceEnabled}")
        }
        for (w in main) {
            AppLog.i(TAG, "port $index MAIN widget: type=${w.widgetType} " +
                "name='${w.widgetName}' idx=${w.widgetIndex} value=${w.widgetValue} " +
                "subs=${w.subItemsList?.map { it.subItemsName }}")
        }
        for (w in config) {
            AppLog.i(TAG, "port $index CONFIG widget: type=${w.widgetType} " +
                "name='${w.widgetName}' idx=${w.widgetIndex} value=${w.widgetValue} " +
                "subs=${w.subItemsList?.map { it.subItemsName }}")
        }
    }

    /**
     * Diagnostic: can the aircraft name the audio files the speaker holds?
     *
     * ⚠ NEITHER SOURCE IS KNOWN TO WORK ON THIS AIRFRAME. `MegaphoneManager` has no file API, so
     * a file picker has to come from `SpeakerKey.KeyAudioFileList` on the ACCESSORY bus or from
     * `PayloadKey.KeyMegaphoneFileName` on the payload bus. Every light KEY was refused on this
     * aircraft, so neither is assumed — this asks and logs, and the picker gets built on
     * whichever answers.
     */
    /**
     * True once the file-list walk has run in this process.
     *
     * ⚠ THE WALK IS TEN KEY READS AND IT IS SETTLED — see [tryAudioFileListAt]. It stays in the
     * code because it is the only thing that would notice a DIFFERENT speaker on a different
     * airframe, but it must not run on every panel open: that is ten reads to the payload bus
     * every time a pilot presses L2, to re-learn an answer that has not changed.
     */
    @Volatile
    private var audioFileWalkDone = false

    private fun probeAudioFiles() {
        // The payload bus's single current-file name. It answered 'megaphone_file' on the M4TD,
        // which is a slot and not a list — kept because a CHANGE in it would be the first sign
        // that the speaker holds more than one sound after all.
        speakerPort?.let { port ->
            KeyManager.getInstance().getValue(
                KeyTools.createKey(dji.sdk.keyvalue.key.PayloadKey.KeyMegaphoneFileName, port),
                object : CommonCallbacks.CompletionCallbackWithParam<String> {
                    override fun onSuccess(value: String?) {
                        AppLog.i(TAG, "CURRENT AUDIO FILE (PayloadKey @$port) = '$value'")
                    }

                    override fun onFailure(error: IDJIError) {
                        AppLog.i(TAG, "current audio file @$port refused: ${error.description()}")
                    }
                })
        }
        if (!audioFileWalkDone) {
            audioFileWalkDone = true
            tryAudioFileListAt(candidateIndexes, 0)
        }
    }

    /**
     * Walks the component indexes asking each for the speaker's audio file list.
     *
     * ⚠ THE FIRST ATTEMPT USED NO INDEX AT ALL and came back `refused: null`, which was read as
     * "this aircraft has no file list". That was the same mistake the light walk had already
     * cost a build over: a key that is refused at the DEFAULT index says nothing about the key,
     * only about that index. The light lives on `EXTERNAL` and was invisible until the walk
     * reached it, so the file list gets the same treatment before it is called impossible.
     *
     * Sequential, like the light walk, and for the same reason — these are reads to a payload
     * bus and firing ten at once to find which index exists is the burst shape the write rule
     * exists to stop.
     *
     * ⚠ MEASURED: REFUSED AT ALL TEN INDEXES on a Matrice 4TD with the AS1 fitted
     * and working. `PayloadKey.KeyMegaphoneFileName` answers with one slot named
     * 'megaphone_file'. So THIS AIRCRAFT HOLDS ONE SOUND and there is nothing to pick between —
     * that is now evidence, and not the assumption it was when it came from a single un-indexed
     * call. Runs once per process; see [audioFileWalkDone].
     */
    private fun tryAudioFileListAt(all: List<ComponentIndexType>, i: Int) {
        if (i >= all.size) {
            AppLog.i(TAG, "AUDIO FILE LIST: no index answered out of $all")
            return
        }
        val index = all[i]
        KeyManager.getInstance().getValue(
            KeyTools.createKey(dji.sdk.keyvalue.key.SpeakerKey.KeyAudioFileList, index),
            object : CommonCallbacks.CompletionCallbackWithParam<
                List<dji.sdk.keyvalue.value.accessory.SpeakerAudioFileInfo>> {
                override fun onSuccess(
                    value: List<dji.sdk.keyvalue.value.accessory.SpeakerAudioFileInfo>?,
                ) {
                    if (value.isNullOrEmpty()) {
                        AppLog.i(TAG, "audio file list @$index: answered with an EMPTY list")
                        tryAudioFileListAt(all, i + 1)
                        return
                    }
                    audioFiles = value
                    AppLog.i(TAG, "AUDIO FILE LIST FOUND @$index (${value.size}): " +
                        value.joinToString { "${it.fileIndex}:'${it.fileName}' " +
                            "${it.fileSize}B ${it.storageLocation}" })
                    notifyChanged()
                }

                override fun onFailure(error: IDJIError) {
                    AppLog.v(TAG, "audio file list @$index refused: ${error.description()}")
                    tryAudioFileListAt(all, i + 1)
                }
            })
    }

    /**
     * The component indexes a payload key is asked at, in order.
     *
     * The three named mounts a Matrice 4 series aircraft has, then the type-C variants, then the
     * numbered PSDK ports. The AL1 turned out to be on `EXTERNAL`, which no earlier guess
     * included — that is the whole reason anything is walked here rather than assumed.
     */
    private val candidateIndexes = listOf(
        ComponentIndexType.LEFT_OR_MAIN,
        ComponentIndexType.RIGHT,
        ComponentIndexType.UP,
        ComponentIndexType.UP_TYPE_C,
        ComponentIndexType.UP_TYPE_C_EXT_ONE,
        ComponentIndexType.INDEX_3,
        ComponentIndexType.AGGREGATION,
        ComponentIndexType.PORT_1,
        ComponentIndexType.PORT_2,
        ComponentIndexType.PORT_3,
    )

    /**
     * The sounds the speaker holds, if any index ever answers. Empty means either that the
     * speaker holds one unnamed slot or that no index answered — the log says which.
     */
    @Volatile
    var audioFiles: List<dji.sdk.keyvalue.value.accessory.SpeakerAudioFileInfo> = emptyList()
        private set

    /** The payload port the aircraft names as a speaker, for the key-based probes. */
    @Volatile
    private var speakerPort: ComponentIndexType? = null

    /** True when the aircraft has answered about at least one accessory. */
    fun anythingDetected(): Boolean = lightOn != null || speakerAnswered

    /**
     * Asks the aircraft about both accessories.
     *
     * The LIGHT needs no read here — its widgets arrive on the push listener [start] attached,
     * so its state is already current. Only the speaker is polled.
     *
     * Reading is free of the write rule — it forbids timed WRITES, not polling.
     * [onDone] is delivered on the main thread so every caller can render from it.
     */
    fun refresh(onDone: (() -> Unit)? = null) {
        start()
        lightPort?.let { pullWidgets(it) }
        refreshSpeaker {
            AppLog.v(TAG, "read-back: light port=$lightPort on=$lightOn bright=$lightBrightness " +
                "blink=$lightBlink | speaker answered=$speakerAnswered vol=$speakerVolume " +
                "mode=$speakerPlayMode status=$speakerStatus")
            android.os.Handler(android.os.Looper.getMainLooper()).post { onDone?.invoke() }
        }
    }

    /**
     * Writes one widget and reports whether the payload actually took it.
     *
     * ⚠ THE READ-BACK IS DELAYED ON PURPOSE. `setWidgetValue`'s success means "sent"; the new
     * value comes back later on the push listener. Comparing immediately would read the OLD
     * value and call every successful write a refusal — see [PendingWrite].
     */
    private fun writeWidget(
        widget: PayloadWidget?,
        value: Int,
        what: String,
        onResult: (Boolean) -> Unit,
    ) {
        val port = lightPort
        val mgr = try { port?.let { PayloadCenter.getInstance().payloadManager[it] } }
            catch (t: Throwable) { null }
        if (widget == null || mgr == null) {
            AppLog.w(TAG, "light $what: no widget or no port — refused")
            onResult(false); return
        }
        // ⚠ ALREADY THERE IS ALREADY DONE. A payload pushes a frame when a value CHANGES, so a
        // write of the value it already holds produces no frame, and a read-back that waits for
        // one waits for ever. Measured: that hung every press of the brightness step the light
        // was already at, for the whole four-second watchdog, and the panel guard
        // was global so it took the other controls down with it.
        if (readBackOf(what) == value) {
            AppLog.i(TAG, "light $what: already at $value — nothing to send")
            onResult(true); return
        }
        AppLog.i(TAG, "light $what: asking for $value on widget " +
            "'${widget.widgetName}' idx=${widget.widgetIndex}")
        val wv = WidgetValue(widget.widgetType, widget.widgetIndex, value)
        // What this write is waiting for. adoptLightWidgets completes it the moment the payload
        // reports the value — see [PendingWrite]. Any earlier write still outstanding is given
        // up here rather than left to fire against a control the pilot has since moved on from.
        finishPending(false)
        val mine = PendingWrite(what, value, onResult)
        val seq = pendingSeq.incrementAndGet()
        pendingWrite = mine
        val fired = AtomicBoolean(false)          // answer once: this SDK can complete twice
        // ⚠ A COMPLETION THAT NEVER ARRIVES IS A REAL OUTCOME HERE. Measured: three widget
        // writes were issued and NEITHER callback ever fired — no success, no failure. The
        // panel had already dimmed itself for the write, so it stayed dimmed and every later
        // control looked broken. The watchdog gives the pilot the panel back and says so.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            fired.set(true)
            // Only if THIS write is still the outstanding one — see [pendingSeq].
            if (pendingSeq.get() == seq && pendingWrite === mine && !mine.done.get()) {
                AppLog.w(TAG, "light $what: the payload never reported $value within " +
                    "${WIDGET_TIMEOUT_MS}ms")
                finishPending(false)
            }
        }, WIDGET_TIMEOUT_MS)
        mgr.setWidgetValue(wv, object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                if (!fired.compareAndSet(false, true)) return
                AppLog.i(TAG, "light $what: setWidgetValue ACCEPTED (sent, not yet confirmed)")
                // ⚠ NO PULL HERE. The widget feed is a PUSH and reports the change by itself.
                // The pull that used to sit here is the prime suspect for the half-built frames
                // seen on the M4TD — values arriving one widget at a time, with the ones not
                // yet filled reading zero, which made the light pill blink off and back on while
                // the light was steady. The value may also already be here, so settle first.
                settlePending()
            }

            override fun onFailure(error: IDJIError) {
                if (!fired.compareAndSet(false, true)) return
                AppLog.w(TAG, "light $what refused: ${error.description()}")
                finishPending(false)
            }
        })
    }

    /** The current value of whichever control [writeWidget] wrote, for its read-back. */
    private fun readBackOf(what: String): Int? = when (what) {
        "on/off" -> lightOn?.let { if (it) 1 else 0 }
        "brightness" -> lightBrightness
        "blink" -> lightBlink?.let { if (it) 1 else 0 }
        else -> null
    }

    /**
     * Completes the outstanding write if the payload has now reported the value it asked for.
     *
     * @return true when the write is finished, false when it is still outstanding.
     */
    private fun settlePending(): Boolean {
        val p = pendingWrite ?: return true
        val back = readBackOf(p.what) ?: return false
        if (back != p.target) return false
        if (!p.done.compareAndSet(false, true)) return true
        pendingWrite = null
        AppLog.i(TAG, "light ${p.what}: CONFIRMED at ${p.target}")
        p.onResult(true)
        return true
    }

    private fun finishPending(ok: Boolean) {
        val p = pendingWrite ?: return
        if (!p.done.compareAndSet(false, true)) return
        pendingWrite = null
        p.onResult(ok)
    }

    /**
     * Turns the AL1 on or off.
     *
     * @param onResult true only when the payload CONFIRMED the new state on read-back. False
     * means the pilot must be told the light did not change.
     */
    fun setLightOn(on: Boolean, onResult: (Boolean) -> Unit) =
        writeWidget(widgetOnOff.get(), if (on) 1 else 0, "on/off", onResult)

    /** Sets the AL1's brightness. Same read-back contract as [setLightOn]. */
    fun setLightBrightness(value: Int, onResult: (Boolean) -> Unit) =
        writeWidget(widgetBrightness.get(),
            value.coerceIn(WIDGET_RANGE.first, WIDGET_RANGE.last), "brightness", onResult)

    /**
     * Puts the light into one whole state — lit or dark, at a brightness, steady or blinking —
     * as one pilot action.
     *
     * ⚠ THE ORDER IS DELIBERATE AND IT IS NOT COSMETIC. Brightness and blink are set BEFORE the
     * light is lit, so a light asked for "on, low, steady" cannot flash at the previous full
     * brightness on its way there. Turning OFF writes only the switch: the other two would be
     * changes the pilot never asked for, and they would be visible the next time the light came
     * on.
     *
     * Writes that are already at their target cost nothing — [writeWidget] returns at once
     * without sending — so the common press sends one write, not three.
     *
     * @param onResult true only when EVERY step the state needed was confirmed.
     */
    fun applyLightState(
        on: Boolean,
        brightness: Int?,
        blink: Boolean,
        onResult: (Boolean) -> Unit,
    ) {
        AppLog.i(TAG, "AL1 light: asking for on=$on brightness=$brightness blink=$blink")
        if (!on) { setLightOn(false, onResult); return }
        setLightBlink(blink) { blinkOk ->
            val next = { brightOk: Boolean ->
                setLightOn(true) { onOk -> onResult(blinkOk && brightOk && onOk) }
            }
            if (brightness == null) next(true) else setLightBrightness(brightness, next)
        }
    }

    /** Turns blinking on or off. Same read-back contract. */
    fun setLightBlink(blink: Boolean, onResult: (Boolean) -> Unit) =
        writeWidget(widgetBlink.get(), if (blink) 1 else 0, "blink", onResult)

    // ---------------------------------------------------------------------------- the speaker

    /**
     * The speaker, and the index hunt that has to come first.
     *
     * ⚠ "Megaphone is not connect" IS THE MANAGER TALKING ABOUT THE WRONG PORT. The manager holds
     * ONE megaphone index at a time. The M4TD answered `UPSIDE` when asked directly, which is
     * why asking is the opening move and the walk is only the fallback.
     */
    private fun refreshSpeaker(onDone: () -> Unit) {
        if (speakerIndex != null) { readSpeakerState(onDone); return }
        MegaphoneManager.getInstance().getMegaphoneIndex(
            object : CommonCallbacks.CompletionCallbackWithParam<MegaphoneIndex> {
                override fun onSuccess(value: MegaphoneIndex?) {
                    AppLog.i(TAG, "speaker: the aircraft holds index $value")
                    if (value != null && value != MegaphoneIndex.UNKNOWN) {
                        speakerIndex = value
                        readSpeakerState(onDone)
                    } else {
                        trySpeakerIndex(candidateSpeakerIndexes, 0, onDone)
                    }
                }

                override fun onFailure(error: IDJIError) {
                    AppLog.v(TAG, "speaker index read refused (${error.description()}) — walking")
                    trySpeakerIndex(candidateSpeakerIndexes, 0, onDone)
                }
            })
    }

    /** Sets each index in turn and keeps the first whose volume read answers. */
    private fun trySpeakerIndex(all: List<MegaphoneIndex>, i: Int, onDone: () -> Unit) {
        if (i >= all.size) {
            AppLog.i(TAG, "no speaker answered on any of $all")
            onDone()
            return
        }
        val index = all[i]
        MegaphoneManager.getInstance().setMegaphoneIndex(index,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    MegaphoneManager.getInstance().getVolume(
                        object : CommonCallbacks.CompletionCallbackWithParam<Int> {
                            override fun onSuccess(value: Int?) {
                                AppLog.i(TAG, "speaker FOUND @$index (volume $value)")
                                speakerIndex = index
                                readSpeakerState(onDone)
                            }

                            override fun onFailure(error: IDJIError) {
                                AppLog.v(TAG, "speaker @$index: ${error.description()}")
                                trySpeakerIndex(all, i + 1, onDone)
                            }
                        })
                }

                override fun onFailure(error: IDJIError) {
                    AppLog.v(TAG, "speaker index $index refused: ${error.description()}")
                    trySpeakerIndex(all, i + 1, onDone)
                }
            })
    }

    private fun readSpeakerState(onDone: () -> Unit) {
        val volDone = AtomicBoolean(false)
        val modeDone = AtomicBoolean(false)
        val statusDone = AtomicBoolean(false)
        val fired = AtomicBoolean(false)
        fun finish() {
            if (!volDone.get() || !modeDone.get() || !statusDone.get()) return
            if (!fired.compareAndSet(false, true)) return
            speakerAnswered = speakerVolume != null || speakerPlayMode != null ||
                speakerStatus != null
            onDone()
        }

        val mgr = MegaphoneManager.getInstance()
        mgr.getVolume(object : CommonCallbacks.CompletionCallbackWithParam<Int> {
            override fun onSuccess(value: Int?) { speakerVolume = value; volDone.set(true); finish() }
            override fun onFailure(error: IDJIError) {
                AppLog.v(TAG, "speaker volume read failed: ${error.description()}")
                volDone.set(true); finish()
            }
        })
        mgr.getPlayMode(object : CommonCallbacks.CompletionCallbackWithParam<PlayMode> {
            override fun onSuccess(value: PlayMode?) {
                speakerPlayMode = if (value == PlayMode.UNKNOWN) null else value
                modeDone.set(true); finish()
            }

            override fun onFailure(error: IDJIError) { modeDone.set(true); finish() }
        })
        mgr.getStatus(object : CommonCallbacks.CompletionCallbackWithParam<MegaphoneStatus> {
            override fun onSuccess(value: MegaphoneStatus?) {
                speakerStatus = if (value == MegaphoneStatus.UNKNOWN) null else value
                statusDone.set(true); finish()
            }

            override fun onFailure(error: IDJIError) { statusDone.set(true); finish() }
        })
    }

    /**
     * ⚠ THE MEGAPHONE HAS NO PUSH FEED, so its state must be ASKED FOR AGAIN, repeatedly, until
     * it catches up. This is the opposite of the light, and getting it wrong made every speaker
     * control look broken when first measured:
     *
     * ```
     * 22:07:29.855  asking for volume 14
     * 22:07:29.934  read-back: vol=52     <- the value from BEFORE the write
     * 22:07:32.177  asking for volume 24
     * 22:07:32.211  read-back: vol=14     <- the PREVIOUS write, one behind for ever
     * ```
     *
     * `MegaphoneManager`'s getters answer from a cache that the write has not reached yet, so a
     * single read after a write is always stale. Every write is confirmed by re-reading until
     * the aircraft agrees or the time runs out.
     *
     * ⚠ THESE ARE READS. The timer rule forbids timed WRITES to the aircraft, not polling a
     * state, and nothing in this loop writes.
     */
    private const val SPEAKER_RECHECK_MS = 300L
    private const val SPEAKER_CONFIRM_MS = 3000L

    /** Re-reads the speaker until [isDone] agrees, or the budget runs out. */
    private fun confirmSpeaker(what: String, isDone: () -> Boolean, onResult: (Boolean) -> Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val deadline = android.os.SystemClock.elapsedRealtime() + SPEAKER_CONFIRM_MS
        fun attempt() {
            readSpeakerState {
                notifyChanged()
                when {
                    isDone() -> {
                        AppLog.i(TAG, "speaker $what: CONFIRMED")
                        onResult(true)
                    }
                    android.os.SystemClock.elapsedRealtime() >= deadline -> {
                        AppLog.w(TAG, "speaker $what: the aircraft never agreed within " +
                            "${SPEAKER_CONFIRM_MS}ms")
                        onResult(false)
                    }
                    else -> main.postDelayed({ attempt() }, SPEAKER_RECHECK_MS)
                }
            }
        }
        main.postDelayed({ attempt() }, SPEAKER_RECHECK_MS)
    }

    private var speakerPollRunning = false
    private val speakerPoll = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Keeps the speaker's state current while the panel is open.
     *
     * The light gets this for free from its push feed. The speaker has none, so without a poll a
     * sound that ends by itself leaves SOUND green for ever, and a change made from DJI Pilot 2
     * never shows at all.
     */
    fun startSpeakerPolling() {
        if (speakerPollRunning) return
        speakerPollRunning = true
        val tick = object : Runnable {
            override fun run() {
                if (!speakerPollRunning) return
                readSpeakerState { notifyChanged() }
                speakerPoll.postDelayed(this, 1500L)
            }
        }
        speakerPoll.postDelayed(tick, 1500L)
    }

    fun stopSpeakerPolling() {
        speakerPollRunning = false
        speakerPoll.removeCallbacksAndMessages(null)
    }

    /** Sets the AS1's volume. Same read-back contract as the light. */
    fun setSpeakerVolume(value: Int, onResult: (Boolean) -> Unit) {
        val clamped = value.coerceIn(speakerVolumeRange.first, speakerVolumeRange.last)
        AppLog.i(TAG, "AS1 speaker: asking for volume $clamped")
        MegaphoneManager.getInstance().setVolume(clamped,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() =
                    confirmSpeaker("volume $clamped", { speakerVolume == clamped }, onResult)

                override fun onFailure(error: IDJIError) {
                    AppLog.w(TAG, "speaker volume refused: ${error.description()}")
                    onResult(false)
                }
            })
    }

    /** SINGLE or LOOP. Same read-back contract. */
    fun setSpeakerPlayMode(mode: PlayMode, onResult: (Boolean) -> Unit) {
        AppLog.i(TAG, "AS1 speaker: asking for play mode $mode")
        MegaphoneManager.getInstance().setPlayMode(mode,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() =
                    confirmSpeaker("play mode $mode", { speakerPlayMode == mode }, onResult)

                override fun onFailure(error: IDJIError) {
                    AppLog.w(TAG, "speaker play mode refused: ${error.description()}")
                    onResult(false)
                }
            })
    }

    /**
     * Plays what is already loaded on the speaker, or stops it.
     *
     * ⚠ THIS PLAYS THE FILE THE SPEAKER ALREADY HOLDS. Nothing here uploads audio — that is the
     * `startPushingFileToMegaphone` path, it needs an OPUS file, and it is deliberately out of
     * scope for a flight-screen panel. A speaker with nothing loaded plays nothing, and the
     * status read-back is what tells the pilot so.
     */
    fun setSpeakerPlaying(play: Boolean, onResult: (Boolean) -> Unit) {
        AppLog.i(TAG, "AS1 speaker: asking to ${if (play) "PLAY" else "STOP"}")
        val fired = AtomicBoolean(false)
        val cb = object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                if (!fired.compareAndSet(false, true)) return
                // ⚠ THE TEST IS THE STATE, NOT "DID ANYTHING ANSWER". This used to confirm on
                // `speakerStatus != null`, which is true whatever the speaker is doing — so
                // play and stop both reported success without ever checking either.
                confirmSpeaker(if (play) "play" else "stop",
                    { (speakerStatus == MegaphoneStatus.PLAYING) == play }, onResult)
            }

            override fun onFailure(error: IDJIError) {
                if (!fired.compareAndSet(false, true)) return
                AppLog.w(TAG, "speaker play/stop refused: ${error.description()}")
                onResult(false)
            }
        }
        if (play) MegaphoneManager.getInstance().startPlay(cb)
        else MegaphoneManager.getInstance().stopPlay(cb)
    }

    /**
     * Drops every read state and detaches the payload listeners.
     *
     * Called when the flight screen goes away, so the next flight cannot open the panel on the
     * last aircraft's answers — including which port each accessory was on, which is a property
     * of the airframe that is plugged in and not of this application.
     */
    fun reset() {
        stopSpeakerPolling()
        onChanged = null
        finishPending(false)
        try {
            for ((_, mgr) in PayloadCenter.getInstance().payloadManager) {
                mgr.clearAllPayloadBasicInfoListener()
                mgr.clearAllPayloadWidgetInfoListener()
            }
        } catch (t: Throwable) {
            AppLog.v(TAG, "payload listener detach: ${t.message}")
        }
        started = false
        lightPort = null
        lightOn = null
        lightBrightness = null
        lightBrightnessRange = null
        lightBlink = null
        widgetOnOff.set(null)
        widgetBrightness.set(null)
        widgetBlink.set(null)
        speakerIndex = null
        speakerVolume = null
        speakerPlayMode = null
        speakerStatus = null
        speakerAnswered = false
        AppLog.i(TAG, "accessory state reset")
    }
}
