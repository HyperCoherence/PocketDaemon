package com.pocketdaemon.pocket_daemon

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import android.util.Log

class PocketDaemonInCallService : InCallService() {

    companion object {
        private const val TAG = "PocketDaemonICS"
        private const val NOTIF_ID = 1
        const val ACTION_TAKE_OVER = "takeOverCall"
        const val ACTION_HANG_UP = "hangUpCall"
        const val ACTION_HAND_TO_AGENT = "handCallToAgent"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val contactResolver by lazy { ContactResolver(applicationContext) }
    private val sessions = HashMap<Call, ActiveCallSession>()
    private var activeCallerLabel: String? = null
    private var pendingCleanup = 0
    private var savedCallVolume = -1
    private var outboundBridgedCall: Call? = null
    private var pendingAnswer: Runnable? = null
    private val takenOverCalls = HashSet<Call>()
    /** Calls the owner is on themselves: outgoing calls they dialed, and incoming calls answered while the agent is off. */
    private val manualCalls = HashSet<Call>()
    /** Calls seen ringing, i.e. incoming. Direction fallback for devices below API 29. */
    private val ringingCalls = HashSet<Call>()
    /** Every call Telecom handed us that has not disconnected yet, so the UI gets a callEnded for each one. */
    private val knownCalls = HashSet<Call>()
    /** Take-overs whose agent audio is still being released; hand-offs wait for this to reach zero. */
    private var releasingTakeovers = 0

    private var phoneRecorder: AudioRecorder? = null
    private var phoneAudioRecord: AudioRecord? = null
    @Volatile private var phoneRecordingRunning = false
    private var phoneRecordThread: Thread? = null

    private val actionListener: (String) -> Unit = { action ->
        handler.post {
            when (action) {
                ACTION_TAKE_OVER -> takeOverActiveCall()
                ACTION_HANG_UP -> hangUpActiveCall()
                ACTION_HAND_TO_AGENT -> handCallToAgent()
            }
        }
    }

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            Log.i(TAG, "onStateChanged: state=$state")
            handleStateChange(call)
        }
    }

    override fun onCreate() {
        super.onCreate()
        app.addActionListener(actionListener)
    }

    override fun onDestroy() {
        app.removeActionListener(actionListener)
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val number = call.details?.handle?.schemeSpecificPart ?: "unknown"
        Log.i(TAG, "onCallAdded: $number")
        app.emitEvent("callAdded", mapOf("number" to number))

        knownCalls.add(call)
        call.registerCallback(callCallback)
        handleStateChange(call)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.i(TAG, "onCallRemoved")
        call.unregisterCallback(callCallback)
        handleStateChange(call)
    }

    private fun stateOf(call: Call): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            call.details.state
        } else {
            @Suppress("DEPRECATION")
            call.state
        }
    }

    /** True when the owner dialed this call. Incoming calls always pass through RINGING first. */
    private fun isOutgoingCall(call: Call): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (call.details?.callDirection) {
                Call.Details.DIRECTION_OUTGOING -> return true
                Call.Details.DIRECTION_INCOMING -> return false
            }
        }
        return !ringingCalls.contains(call)
    }

    private fun isTracked(call: Call): Boolean {
        return outboundBridgedCall == call
            || sessions.containsKey(call)
            || takenOverCalls.contains(call)
            || manualCalls.contains(call)
    }

    private fun handleStateChange(call: Call) {
        val state = stateOf(call)
        val number = call.details?.handle?.schemeSpecificPart ?: "unknown"

        if (state != Call.STATE_RINGING) {
            pendingAnswer?.let { handler.removeCallbacks(it) }
            pendingAnswer = null
        }

        when (state) {
            Call.STATE_RINGING -> {
                ringingCalls.add(call)
                app.emitEvent("callRinging", mapOf("number" to number))
                if (app.agentEnabled) {
                    Log.i(TAG, "Agent enabled — auto-answer in ${app.answerDelayMs}ms")
                    val runnable = Runnable {
                        pendingAnswer = null
                        try {
                            call.answer(VideoProfile.STATE_AUDIO_ONLY)
                            Log.i(TAG, "Call answered")
                        } catch (e: Exception) {
                            Log.e(TAG, "Answer failed", e)
                            app.emitEvent("error", mapOf("message" to "Answer failed: ${e.message}"))
                        }
                    }
                    pendingAnswer = runnable
                    handler.postDelayed(runnable, app.answerDelayMs)
                }
            }

            Call.STATE_ACTIVE -> {
                val bridge = app.outboundBridge
                if (bridge != null && outboundBridgedCall == null) {
                    Log.i(TAG, "Outbound bridge call active: $number")
                    outboundBridgedCall = call
                    setMuted(true)
                    if (app.speakerMonitorEnabled) {
                        routeMonitoredCallAudio()
                    } else {
                        muteCallVolume()
                    }
                    val hangUp: () -> Unit = {
                        Log.i(TAG, "Outbound bridge hangUp requested")
                        handler.post { call.disconnect() }
                        Unit
                    }
                    bridge.onOutboundCallActive(hangUp)
                } else if (isTracked(call)) {
                    // Already handled; e.g. resumed from hold.
                } else if (isOutgoingCall(call)) {
                    // The owner dialed this one. The agent only joins if they hand it over.
                    Log.i(TAG, "Manual outgoing call - agent stays out: $number")
                    beginManualCall(call, number)
                } else if (!app.agentEnabled) {
                    Log.i(TAG, "Agent disabled - passing call through")
                    beginManualCall(call, number)
                } else {
                    startAgentSession(call, number)
                }
            }

            Call.STATE_DISCONNECTING, Call.STATE_DISCONNECTED -> {
                stopPhoneCallRecording()
                ringingCalls.remove(call)
                val wasKnown = knownCalls.remove(call)
                if (outboundBridgedCall == call) {
                    Log.i(TAG, "Outbound bridge call disconnected: $number")
                    outboundBridgedCall = null
                    setMuted(false)
                    restoreCallVolume()
                    app.outboundBridge?.onOutboundCallEnded()
                    app.outboundBridge = null
                } else if (takenOverCalls.remove(call)) {
                    Log.i(TAG, "Taken-over call disconnected: $number")
                    setMuted(false)
                    restoreCallVolume()
                    app.emitEvent("callEnded", mapOf("number" to number))
                } else if (manualCalls.remove(call)) {
                    Log.i(TAG, "Manual call disconnected: $number")
                    app.emitEvent("callEnded", mapOf("number" to number))
                } else {
                    val session = sessions.remove(call)
                    if (session != null) {
                        setMuted(false)
                        restoreCallVolume()
                        session.stop()
                        if (sessions.isEmpty()) activeCallerLabel = null
                        pendingCleanup++
                        Thread {
                            session.awaitTermination()
                            handler.post {
                                pendingCleanup--
                                updateForeground()
                            }
                        }.start()
                        app.emitEvent("callEnded", mapOf("number" to number))
                    } else if (wasKnown) {
                        // Never reached ACTIVE: declined, missed, or the far end gave up while ringing.
                        Log.i(TAG, "Call ended before it was answered: $number")
                        app.emitEvent("callEnded", mapOf("number" to number))
                    }
                }
            }
        }
    }

    /** The owner is on this call themselves; the agent stays out until handed the call. */
    private fun beginManualCall(call: Call, number: String) {
        manualCalls.add(call)
        val resolved = contactResolver.resolve(number)
        app.emitEvent("callActive", mapOf(
            "number" to number,
            "name" to (resolved.displayName ?: ""),
            "trusted" to (resolved.trusted != null),
            "manual" to true,
        ))
        startPhoneCallRecording()
    }

    private fun startAgentSession(call: Call, number: String) {
        stopPhoneCallRecording()
        manualCalls.remove(call)
        takenOverCalls.remove(call)

        val resolved = contactResolver.resolve(number)
        app.emitEvent("callActive", mapOf(
            "number" to number,
            "name" to (resolved.displayName ?: ""),
            "trusted" to (resolved.trusted != null),
            "manual" to false,
        ))
        setMuted(true)
        Log.i(TAG, "Mic muted - agent takes over")

        if (app.speakerMonitorEnabled) {
            routeMonitoredCallAudio()
            Log.i(TAG, "Call monitor enabled")
        } else {
            muteCallVolume()
        }

        val hangUp: () -> Unit = {
            Log.i(TAG, "Agent requested hangUp")
            handler.post { call.disconnect() }
            Unit
        }

        val session: ActiveCallSession = if (resolved.trusted != null) {
            Log.i(TAG, "Routing to TRUSTED session for ${resolved.trusted.name}")
            TrustedCallSession(
                context = applicationContext,
                callerNumber = number,
                callerConfig = resolved.trusted,
                onHangUp = hangUp,
            )
        } else {
            Log.i(TAG, "Routing to ISOLATED session for $number")
            CallSession(
                context = applicationContext,
                callerNumber = number,
                callerName = resolved.displayName,
                onHangUp = hangUp,
            )
        }

        activeCallerLabel = resolved.displayName ?: resolved.trusted?.name
        sessions[call] = session
        updateForeground()
        session.start()
    }

    /** Owner-initiated: put the agent on a call the owner is currently on themselves. */
    private fun handCallToAgent(attempt: Int = 0) {
        val call = manualCalls.firstOrNull() ?: takenOverCalls.firstOrNull() ?: run {
            Log.w(TAG, "handToAgent: no live call without an agent session")
            return
        }
        val number = call.details?.handle?.schemeSpecificPart ?: "unknown"
        if (stateOf(call) != Call.STATE_ACTIVE) {
            Log.w(TAG, "handToAgent: call is not active")
            return
        }
        if (releasingTakeovers > 0) {
            // A previous take-over is still releasing the agent's audio; retry shortly.
            if (attempt < 20) {
                handler.postDelayed({ handCallToAgent(attempt + 1) }, 250)
            } else {
                Log.w(TAG, "handToAgent: gave up waiting for take-over cleanup")
                app.emitEvent("error", mapOf("message" to "Agent audio is still busy; try again"))
            }
            return
        }
        Log.i(TAG, "Handing call to agent: $number")
        startAgentSession(call, number)
    }

    private fun takeOverActiveCall() {
        val entry = sessions.entries.firstOrNull() ?: run {
            Log.w(TAG, "takeOver: no active session")
            return
        }
        val call = entry.key
        val session = entry.value
        val number = call.details?.handle?.schemeSpecificPart ?: "unknown"

        Log.i(TAG, "Taking over call from agent")
        session.stop()
        sessions.remove(call)
        takenOverCalls.add(call)
        if (sessions.isEmpty()) activeCallerLabel = null
        updateForeground()

        setMuted(false)
        restoreCallVolume()
        Log.i(TAG, "Mic unmuted - owner is live")
        releasingTakeovers++
        Thread({
            session.awaitTermination()
            Log.i(TAG, "Agent call resources released after takeover")
            handler.post {
                releasingTakeovers--
                if (takenOverCalls.contains(call)) startPhoneCallRecording()
            }
        }, "call-takeover-cleanup").start()

        app.emitEvent("callTakenOver", mapOf("number" to number))
    }

    private fun hangUpActiveCall() {
        val call = sessions.keys.firstOrNull() ?: calls?.firstOrNull()
        if (call != null) {
            Log.i(TAG, "Hanging up active call")
            call.disconnect()
        }
    }

    @Suppress("DEPRECATION")
    private fun routeMonitoredCallAudio() {
        val state = callAudioState
        val mask = state?.supportedRouteMask ?: 0
        val route = when {
            mask and CallAudioState.ROUTE_BLUETOOTH != 0 -> CallAudioState.ROUTE_BLUETOOTH
            mask and CallAudioState.ROUTE_WIRED_HEADSET != 0 -> CallAudioState.ROUTE_WIRED_HEADSET
            mask and CallAudioState.ROUTE_SPEAKER != 0 -> CallAudioState.ROUTE_SPEAKER
            else -> CallAudioState.ROUTE_SPEAKER
        }
        setAudioRoute(route)
        Log.i(TAG, "Call monitor route=${callRouteName(route)} supportedMask=$mask")
    }

    private fun callRouteName(route: Int): String {
        return when (route) {
            CallAudioState.ROUTE_BLUETOOTH -> "bluetooth"
            CallAudioState.ROUTE_WIRED_HEADSET -> "wired_headset"
            CallAudioState.ROUTE_SPEAKER -> "speaker"
            CallAudioState.ROUTE_EARPIECE -> "earpiece"
            else -> "route_$route"
        }
    }

    private fun updateForeground() {
        if (sessions.isEmpty() && pendingCleanup == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val intent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val label = activeCallerLabel
        val text = if (label != null) "Handling call from $label..." else "Handling call..."
        return Notification.Builder(this, PocketDaemonApp.CHANNEL_ACTIVE_CALL).apply {
            setContentTitle("PocketDaemon")
            setContentText(text)
            setSmallIcon(android.R.drawable.ic_menu_call)
            setContentIntent(intent)
            setOngoing(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            }
        }.build()
    }

    @Suppress("deprecation")
    private fun startPhoneCallRecording() {
        if (phoneRecordingRunning || !app.recordPhoneCallsEnabled) return
        try {
            val rate = 16000
            val bufSize = maxOf(
                AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
                rate * 2
            )
            val sources = intArrayOf(
                MediaRecorder.AudioSource.VOICE_CALL,
                MediaRecorder.AudioSource.VOICE_DOWNLINK,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.MIC,
            )
            var rec: AudioRecord? = null
            for (src in sources) {
                try {
                    val r = AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
                    if (r.state == AudioRecord.STATE_INITIALIZED) {
                        rec = r
                        break
                    }
                    r.release()
                } catch (_: Exception) {}
            }
            if (rec == null) {
                Log.w(TAG, "Phone call recording: no audio source available")
                return
            }
            phoneAudioRecord = rec
            phoneRecorder = AudioRecorder("phone").also { it.start() }
            phoneRecordingRunning = true
            phoneRecordThread = Thread({
                val buf = ByteArray(rate / 5 * 2)
                try {
                    rec.startRecording()
                    while (phoneRecordingRunning) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n > 0) phoneRecorder?.writeCaptureAudio(buf.copyOf(n))
                        else if (n < 0) break
                    }
                } catch (e: Exception) {
                    if (phoneRecordingRunning) Log.e(TAG, "Phone recording error: ${e.message}")
                } finally {
                    try { rec.stop() } catch (_: Exception) {}
                    try { rec.release() } catch (_: Exception) {}
                }
            }, "phone-call-recording").also { it.start() }
            Log.i(TAG, "Phone call recording started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start phone call recording: ${e.message}")
        }
    }

    private fun stopPhoneCallRecording() {
        if (!phoneRecordingRunning) return
        phoneRecordingRunning = false
        phoneRecordThread?.interrupt()
        phoneRecordThread?.join(2000)
        phoneRecordThread = null
        phoneAudioRecord = null
        phoneRecorder?.stop()
        phoneRecorder = null
        Log.i(TAG, "Phone call recording stopped")
    }

    private fun muteCallVolume() {
        val am = getSystemService(AudioManager::class.java)
        savedCallVolume = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, 0, 0)
        Log.i(TAG, "Call volume muted (was $savedCallVolume)")
    }

    private fun restoreCallVolume() {
        if (savedCallVolume < 0) return
        val am = getSystemService(AudioManager::class.java)
        am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, savedCallVolume, 0)
        Log.i(TAG, "Call volume restored to $savedCallVolume")
        savedCallVolume = -1
    }

    private val app: PocketDaemonApp
        get() = application as PocketDaemonApp
}
