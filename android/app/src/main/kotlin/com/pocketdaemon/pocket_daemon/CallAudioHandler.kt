package com.pocketdaemon.pocket_daemon

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log

class CallAudioHandler(
    private val context: Context,
    private val speakerMonitor: Boolean = false,
    private val onCapturedAudio: (ByteArray) -> Unit,
) {
    companion object {
        private const val TAG = "CallAudioHandler"
        const val CAPTURE_RATE = 16000
        const val PLAYBACK_RATE = 24000
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private val audioManager = context.getSystemService(AudioManager::class.java)

    @Volatile private var running = false
    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null
    private var playbackTrack: AudioTrack? = null
    private var monitorTrack: AudioTrack? = null
    private var recorder: AudioRecord? = null

    private val playbackLock = Object()
    private val playbackQueue = ArrayDeque<ByteArray>()

    fun start() {
        if (running) return
        running = true
        synchronized(playbackLock) { playbackQueue.clear() }

        val telephonyOut = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .find { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
        val telephonyIn = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .find { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
        val monitorOut = if (speakerMonitor) {
            AudioRouteHelper.preferredMonitorOutput(audioManager)
        } else {
            null
        }

        Log.i(TAG, "TELEPHONY out=${telephonyOut?.id} in=${telephonyIn?.id}")
        if (speakerMonitor) {
            Log.i(TAG, "Monitor output=${AudioRouteHelper.label(monitorOut)}")
        }

        initPlayback(telephonyOut)
        if (speakerMonitor) initMonitorTrack(monitorOut)
        initCapture(telephonyIn)

        captureThread = Thread({ captureLoop() }, "audio-capture").also { it.start() }
        Log.i(TAG, "Audio handler started (speakerMonitor=$speakerMonitor)")
    }

    fun stop() {
        running = false
        captureThread?.interrupt()
        synchronized(playbackLock) { playbackLock.notifyAll() }
        playbackThread?.interrupt()
    }

    fun awaitTermination() {
        captureThread?.join(3000)
        playbackThread?.join(3000)
        releaseResources()
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

    @Suppress("deprecation")
    private fun initCapture(telephonyIn: AudioDeviceInfo?) {
        val bufSize = maxOf(
            AudioRecord.getMinBufferSize(CAPTURE_RATE, CHANNEL_IN, ENCODING),
            CAPTURE_RATE * 2
        )

        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_DOWNLINK,
            MediaRecorder.AudioSource.VOICE_CALL,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC,
        )
        val names = arrayOf("VOICE_DOWNLINK", "VOICE_CALL", "VOICE_COMMUNICATION", "MIC")

        for (i in sources.indices) {
            try {
                val rec = AudioRecord(sources[i], CAPTURE_RATE, CHANNEL_IN, ENCODING, bufSize)
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    if (telephonyIn != null) {
                        rec.preferredDevice = telephonyIn
                        Log.i(TAG, "Capture: ${names[i]} -> TELEPHONY input")
                    } else {
                        Log.i(TAG, "Capture: ${names[i]} (no telephony input device)")
                    }
                    recorder = rec
                    return
                }
                rec.release()
                Log.i(TAG, "Capture ${names[i]}: failed to init")
            } catch (e: Exception) {
                Log.i(TAG, "Capture ${names[i]}: ${e.message}")
            }
        }
        Log.e(TAG, "No capture source available")
    }

    private fun initPlayback(telephonyOut: AudioDeviceInfo?) {
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
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .build()

        try {
            val track = AudioTrack.Builder()
                .setBufferSizeInBytes(bufSize)
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .build()

            if (telephonyOut != null && track.setPreferredDevice(telephonyOut)) {
                Log.i(TAG, "Playback: TELEPHONY output (id=${telephonyOut.id})")
            } else {
                Log.i(TAG, "Playback: default device (no TELEPHONY route)")
            }

            track.play()
            playbackTrack = track

            playbackThread = Thread({ playbackLoop() }, "audio-playback").also { it.start() }
        } catch (e: Exception) {
            Log.e(TAG, "Playback init failed: ${e.message}")
        }
    }

    private fun initMonitorTrack(output: AudioDeviceInfo?) {
        val bufSize = maxOf(
            AudioTrack.getMinBufferSize(PLAYBACK_RATE, CHANNEL_OUT, ENCODING),
            PLAYBACK_RATE * 2
        )
        val format = AudioFormat.Builder()
            .setSampleRate(PLAYBACK_RATE)
            .setChannelMask(CHANNEL_OUT)
            .setEncoding(ENCODING)
            .build()
        val usage = if (output != null && AudioRouteHelper.isBluetoothCommunication(output)) {
            AudioAttributes.USAGE_VOICE_COMMUNICATION
        } else {
            AudioAttributes.USAGE_MEDIA
        }
        val attrs = AudioAttributes.Builder()
            .setUsage(usage)
            .build()
        try {
            val track = AudioTrack.Builder()
                .setBufferSizeInBytes(bufSize)
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .build()
            if (output != null) {
                val routed = track.setPreferredDevice(output)
                Log.i(TAG, "Monitor route ${AudioRouteHelper.label(output)} routed=$routed usage=$usage")
            }
            track.play()
            monitorTrack = track
            Log.i(TAG, "Monitor track initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Monitor track init failed: ${e.message}")
        }
    }

    private fun captureLoop() {
        val rec = recorder ?: return
        val buf = ByteArray(CAPTURE_RATE / 5 * 2) // 200ms chunks
        try {
            rec.startRecording()
            Log.i(TAG, "Capture recording started")
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    onCapturedAudio(buf.copyOf(n))
                } else if (n < 0) {
                    Log.w(TAG, "AudioRecord.read error: $n")
                    break
                }
            }
        } catch (e: Exception) {
            if (running) Log.e(TAG, "Capture error: ${e.message}")
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            Log.i(TAG, "Capture stopped")
        }
    }

    private fun playbackLoop() {
        val track = playbackTrack ?: return
        try {
            while (running) {
                val chunk: ByteArray
                synchronized(playbackLock) {
                    while (playbackQueue.isEmpty() && running) {
                        playbackLock.wait(500)
                    }
                    if (!running) return
                    chunk = playbackQueue.removeFirst()
                }
                track.write(chunk, 0, chunk.size)
                monitorTrack?.write(chunk, 0, chunk.size)
            }
        } catch (e: InterruptedException) {
            // normal shutdown
        } catch (e: Exception) {
            if (running) Log.e(TAG, "Playback error: ${e.message}")
        } finally {
            Log.i(TAG, "Playback stopped")
        }
    }

    private fun releaseResources() {
        try { recorder?.release() } catch (_: Exception) {}
        try {
            playbackTrack?.stop()
            playbackTrack?.release()
        } catch (_: Exception) {}
        try {
            monitorTrack?.stop()
            monitorTrack?.release()
        } catch (_: Exception) {}
        recorder = null
        playbackTrack = null
        monitorTrack = null
        Log.i(TAG, "Resources released")
    }
}
