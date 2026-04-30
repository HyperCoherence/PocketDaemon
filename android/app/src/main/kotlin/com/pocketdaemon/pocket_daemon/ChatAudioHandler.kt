package com.pocketdaemon.pocket_daemon

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log

/**
 * Audio handler for push-to-talk chat mode.
 * Uses communication routing when possible and falls back to speaker.
 */
class ChatAudioHandler(
    private val context: Context,
    private val onCapturedAudio: (ByteArray) -> Unit,
) {
    companion object {
        private const val TAG = "ChatAudioHandler"
        const val CAPTURE_RATE = 16000
        const val PLAYBACK_RATE = 24000
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile private var capturing = false
    @Volatile private var playing = false
    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null
    private var playbackTrack: AudioTrack? = null
    private var recorder: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var forcedSpeaker = false
    private var bluetoothScoStarted = false
    private var playbackDevice: AudioDeviceInfo? = null
    private var captureDevice: AudioDeviceInfo? = null
    private var playbackUsage = AudioAttributes.USAGE_VOICE_COMMUNICATION

    private val playbackLock = Object()
    private val playbackQueue = ArrayDeque<ByteArray>()

    fun startCapture() {
        if (capturing) return
        capturing = true
        synchronized(playbackLock) { playbackQueue.clear() }
        routeToBestOutput()
        initCapture()
        initPlayback()
        captureThread = Thread({ captureLoop() }, "chat-capture").also { it.start() }
        Log.i(TAG, "Capture started")
    }

    fun stopCapture() {
        capturing = false
        captureThread?.interrupt()
        Log.i(TAG, "Capture stopped")
    }

    fun enqueuePlayback(pcm: ByteArray) {
        synchronized(playbackLock) {
            playbackQueue.addLast(pcm)
            playbackLock.notify()
        }
    }

    fun flushPlayback() {
        synchronized(playbackLock) {
            val dropped = playbackQueue.size
            playbackQueue.clear()
            if (dropped > 0) Log.i(TAG, "Flushed $dropped queued chunks (barge-in)")
        }
    }

    fun awaitPlaybackDrain(timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            synchronized(playbackLock) {
                if (playbackQueue.isEmpty()) return
            }
            Thread.sleep(50)
        }
        Log.w(TAG, "Playback drain timed out after ${timeoutMs}ms")
    }

    fun release() {
        capturing = false
        playing = false
        captureThread?.interrupt()
        synchronized(playbackLock) { playbackLock.notifyAll() }
        playbackThread?.interrupt()
        captureThread?.join(2000)
        playbackThread?.join(2000)
        try { aec?.release() } catch (_: Exception) {}
        try { ns?.release() } catch (_: Exception) {}
        aec = null
        ns = null
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        try { playbackTrack?.stop() } catch (_: Exception) {}
        try { playbackTrack?.release() } catch (_: Exception) {}
        recorder = null
        playbackTrack = null
        playbackThread = null
        synchronized(playbackLock) { playbackQueue.clear() }
        clearRoute()
        Log.i(TAG, "Released")
    }

    private fun routeToBestOutput() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = false

        val communicationOut = AudioRouteHelper.communicationOutput(audioManager)
        val externalOut = AudioRouteHelper.preferredExternalOutput(audioManager)
        val selectedOut = communicationOut ?: externalOut ?: AudioRouteHelper.builtInSpeaker(audioManager)

        playbackDevice = selectedOut
        captureDevice = AudioRouteHelper.preferredCommunicationInput(audioManager, selectedOut)
        playbackUsage = if (selectedOut != null &&
            AudioRouteHelper.isBluetoothMedia(selectedOut) &&
            !AudioRouteHelper.isBluetoothCommunication(selectedOut)) {
            AudioAttributes.USAGE_MEDIA
        } else {
            AudioAttributes.USAGE_VOICE_COMMUNICATION
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && communicationOut != null) {
            val routed = audioManager.setCommunicationDevice(communicationOut)
            Log.i(TAG, "Communication route ${AudioRouteHelper.label(communicationOut)} routed=$routed")
        } else if (selectedOut != null && AudioRouteHelper.isBluetoothCommunication(selectedOut)) {
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = true
            bluetoothScoStarted = true
            Log.i(TAG, "Bluetooth SCO requested for ${AudioRouteHelper.label(selectedOut)}")
        } else if (selectedOut?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
            forcedSpeaker = true
            Log.i(TAG, "No headset - speaker + AEC active")
        } else if (selectedOut != null) {
            Log.i(TAG, "External media route selected: ${AudioRouteHelper.label(selectedOut)}")
        } else {
            Log.i(TAG, "No explicit output route available")
        }
    }

    private fun clearRoute() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
        if (bluetoothScoStarted) {
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = false
            bluetoothScoStarted = false
            Log.i(TAG, "Bluetooth SCO stopped")
        }
        if (forcedSpeaker) {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
            forcedSpeaker = false
            Log.i(TAG, "Speaker restored")
        }
        audioManager.mode = AudioManager.MODE_NORMAL
        playbackDevice = null
        captureDevice = null
    }

    @Suppress("deprecation")
    private fun initCapture() {
        val bufSize = maxOf(
            AudioRecord.getMinBufferSize(CAPTURE_RATE, CHANNEL_IN, ENCODING),
            CAPTURE_RATE * 2
        )
        try {
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                CAPTURE_RATE, CHANNEL_IN, ENCODING, bufSize
            )
            if (rec.state == AudioRecord.STATE_INITIALIZED) {
                val input = captureDevice
                if (input != null) {
                    val routed = rec.setPreferredDevice(input)
                    Log.i(TAG, "Capture route ${AudioRouteHelper.label(input)} routed=$routed")
                }
                recorder = rec
                attachAudioEffects(rec.audioSessionId)
                Log.i(TAG, "VOICE_COMMUNICATION capture initialized")
            } else {
                rec.release()
                Log.e(TAG, "VOICE_COMMUNICATION init failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Capture init error: ${e.message}")
        }
    }

    private fun attachAudioEffects(sessionId: Int) {
        if (AcousticEchoCanceler.isAvailable()) {
            try {
                aec = AcousticEchoCanceler.create(sessionId)?.also { it.enabled = true }
                Log.i(TAG, "AEC enabled: ${aec != null}")
            } catch (e: Exception) {
                Log.w(TAG, "AEC create failed: ${e.message}")
            }
        } else {
            Log.i(TAG, "AEC not available on this device")
        }
        if (NoiseSuppressor.isAvailable()) {
            try {
                ns = NoiseSuppressor.create(sessionId)?.also { it.enabled = true }
                Log.i(TAG, "NS enabled: ${ns != null}")
            } catch (e: Exception) {
                Log.w(TAG, "NS create failed: ${e.message}")
            }
        }
    }

    private fun initPlayback() {
        val bufSize = maxOf(
            AudioTrack.getMinBufferSize(PLAYBACK_RATE, CHANNEL_OUT, ENCODING),
            PLAYBACK_RATE * 2
        )
        val format = AudioFormat.Builder()
            .setSampleRate(PLAYBACK_RATE)
            .setChannelMask(CHANNEL_OUT)
            .setEncoding(ENCODING)
            .build()
        val attrs = AudioAttributes.Builder()
            .setUsage(playbackUsage)
            .build()
        try {
            val track = AudioTrack.Builder()
                .setBufferSizeInBytes(bufSize)
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .build()
            val output = playbackDevice
            if (output != null) {
                val routed = track.setPreferredDevice(output)
                Log.i(TAG, "Playback route ${AudioRouteHelper.label(output)} routed=$routed usage=$playbackUsage")
            }
            track.play()
            playbackTrack = track
            playing = true
            playbackThread = Thread({ playbackLoop() }, "chat-playback").also { it.start() }
            Log.i(TAG, "Playback initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Playback init failed: ${e.message}")
        }
    }

    private fun captureLoop() {
        val rec = recorder ?: return
        val buf = ByteArray(CAPTURE_RATE / 5 * 2)
        try {
            rec.startRecording()
            while (capturing) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    onCapturedAudio(buf.copyOf(n))
                } else if (n < 0) {
                    Log.w(TAG, "AudioRecord.read error: $n")
                    break
                }
            }
        } catch (e: Exception) {
            if (capturing) Log.e(TAG, "Capture error: ${e.message}")
        } finally {
            try { rec.stop() } catch (_: Exception) {}
        }
    }

    private fun playbackLoop() {
        val track = playbackTrack ?: return
        try {
            while (playing) {
                val chunk: ByteArray
                synchronized(playbackLock) {
                    while (playbackQueue.isEmpty() && playing) {
                        playbackLock.wait(500)
                    }
                    if (!playing) return
                    chunk = playbackQueue.removeFirst()
                }
                track.write(chunk, 0, chunk.size)
            }
        } catch (e: InterruptedException) {
            // shutdown
        } catch (e: Exception) {
            if (playing) Log.e(TAG, "Playback error: ${e.message}")
        }
    }
}
