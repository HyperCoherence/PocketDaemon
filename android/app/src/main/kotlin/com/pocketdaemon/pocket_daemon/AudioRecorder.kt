package com.pocketdaemon.pocket_daemon

import android.os.Environment
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records two concurrent audio streams (capture + playback) into a single
 * mono 16-bit WAV file with correct time alignment.
 *
 * Each stream writes to its own temp file during the session. Capture audio
 * is written directly (continuous, real-time aligned by nature). Playback
 * audio is aligned to its wall-clock position at the start of each model turn,
 * then appended contiguously for that turn. The model may deliver PCM in
 * jittery bursts even when playback remains smooth, so padding every callback
 * would create artificial gaps in the recording. On stop(), both streams are
 * mixed sample-by-sample into the final WAV.
 *
 * Playback audio arriving at a different sample rate is linearly resampled
 * to match the output rate before writing.
 */
class AudioRecorder(
    private val type: String,
    private val outputRate: Int = OUTPUT_SAMPLE_RATE,
) {
    companion object {
        private const val TAG = "AudioRecorder"
        const val OUTPUT_SAMPLE_RATE = 16000
        private const val BITS_PER_SAMPLE = 16
        private const val NUM_CHANNELS = 1
        private const val WAV_HEADER_SIZE = 44
        private const val MIX_BUF_BYTES = 8192
    }

    private val capLock = Object()
    private val playLock = Object()

    private var capRaf: RandomAccessFile? = null
    private var playRaf: RandomAccessFile? = null
    private var outFile: File? = null
    private var capTmpFile: File? = null
    private var playTmpFile: File? = null

    private var playBytesWritten = 0L
    private var playbackTurnOpen = false
    private var startNanos = 0L
    @Volatile private var closed = false

    fun start() {
        val dir = File(Environment.getExternalStorageDirectory(), "PocketDaemon/recordings").also { it.mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        outFile = File(dir, "${type}_$ts.wav")
        capTmpFile = File(dir, ".cap_$ts.tmp")
        playTmpFile = File(dir, ".play_$ts.tmp")

        capRaf = RandomAccessFile(capTmpFile, "rw")
        playRaf = RandomAccessFile(playTmpFile, "rw")
        playBytesWritten = 0
        playbackTurnOpen = false
        startNanos = System.nanoTime()
        closed = false
        Log.i(TAG, "Recording started: ${outFile?.absolutePath}")
    }

    /** Write captured (mic/caller) PCM at the output sample rate. */
    fun writeCaptureAudio(pcm: ByteArray) {
        synchronized(capLock) {
            if (closed) return
            try {
                capRaf?.write(pcm)
            } catch (e: Exception) {
                Log.w(TAG, "Capture write error: ${e.message}")
            }
        }
    }

    /** Write playback (agent/speaker) PCM, resampling from [srcRate] to [outputRate] if needed. */
    fun writePlaybackAudio(pcm: ByteArray, srcRate: Int) {
        val data = if (srcRate != outputRate) resample(pcm, srcRate, outputRate) else pcm
        synchronized(playLock) {
            if (closed) return
            try {
                val pr = playRaf ?: return
                if (!playbackTurnOpen) {
                    val targetBytes = playbackWallClockBytes()
                    if (targetBytes > playBytesWritten) {
                        writeSilence(pr, targetBytes - playBytesWritten)
                        playBytesWritten = targetBytes
                    }
                    playbackTurnOpen = true
                }
                pr.write(data)
                playBytesWritten += data.size
            } catch (e: Exception) {
                Log.w(TAG, "Playback write error: ${e.message}")
            }
        }
    }

    /** Mark the end of one agent/model playback turn so the next turn realigns to wall-clock time. */
    fun finishPlaybackTurn() {
        synchronized(playLock) {
            playbackTurnOpen = false
        }
    }

    fun stop() {
        synchronized(capLock) { synchronized(playLock) {
            if (closed) return
            closed = true
        }}
        synchronized(capLock) {
            try { capRaf?.close() } catch (_: Exception) {}
            capRaf = null
        }
        synchronized(playLock) {
            try { playRaf?.close() } catch (_: Exception) {}
            playRaf = null
        }
        try {
            mixToWav()
        } catch (e: Exception) {
            Log.e(TAG, "Mix failed: ${e.message}")
        } finally {
            capTmpFile?.delete()
            playTmpFile?.delete()
        }
    }

    private fun mixToWav() {
        val out = outFile ?: return
        val capFile = capTmpFile
        val playFile = playTmpFile

        val capLen = capFile?.length() ?: 0
        val playLen = playFile?.length() ?: 0

        if (capLen == 0L && playLen == 0L) {
            Log.i(TAG, "Nothing recorded")
            return
        }

        val wav = RandomAccessFile(out, "rw")
        writeWavHeader(wav)

        val capIn = if (capLen > 0) RandomAccessFile(capFile, "r") else null
        val playIn = if (playLen > 0) RandomAccessFile(playFile, "r") else null

        val bufA = ByteArray(MIX_BUF_BYTES)
        val bufB = ByteArray(MIX_BUF_BYTES)
        var capRemaining = capLen
        var playRemaining = playLen
        var totalWritten = 0L

        while (capRemaining > 0 || playRemaining > 0) {
            val toProcess = minOf(
                maxOf(capRemaining, playRemaining),
                MIX_BUF_BYTES.toLong()
            ).toInt()

            val nA: Int
            if (capRemaining > 0 && capIn != null) {
                val r = capIn.read(bufA, 0, minOf(toProcess.toLong(), capRemaining).toInt())
                if (r > 0) { nA = r; capRemaining -= r } else { nA = 0; capRemaining = 0 }
            } else {
                nA = 0
            }
            if (nA < toProcess) bufA.fill(0, nA, toProcess)

            val nB: Int
            if (playRemaining > 0 && playIn != null) {
                val r = playIn.read(bufB, 0, minOf(toProcess.toLong(), playRemaining).toInt())
                if (r > 0) { nB = r; playRemaining -= r } else { nB = 0; playRemaining = 0 }
            } else {
                nB = 0
            }
            if (nB < toProcess) bufB.fill(0, nB, toProcess)

            var i = 0
            while (i < toProcess - 1) {
                val sA = (bufA[i].toInt() and 0xFF) or (bufA[i + 1].toInt() shl 8)
                val sB = (bufB[i].toInt() and 0xFF) or (bufB[i + 1].toInt() shl 8)
                val mixed = (sA + sB).coerceIn(-32768, 32767)
                bufA[i] = (mixed and 0xFF).toByte()
                bufA[i + 1] = ((mixed shr 8) and 0xFF).toByte()
                i += 2
            }

            wav.write(bufA, 0, toProcess)
            totalWritten += toProcess
        }

        capIn?.close()
        playIn?.close()

        wav.seek(4)
        wav.writeIntLE((totalWritten + WAV_HEADER_SIZE - 8).toInt())
        wav.seek(40)
        wav.writeIntLE(totalWritten.toInt())
        wav.close()

        Log.i(TAG, "Recording stopped — mixed ${totalWritten / 1024}KB → ${out.name}")
    }

    private fun writeSilence(raf: RandomAccessFile, byteCount: Long) {
        val buf = ByteArray(minOf(byteCount, 4096L).toInt())
        var remaining = byteCount
        while (remaining > 0) {
            val n = minOf(remaining, buf.size.toLong()).toInt()
            raf.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun playbackWallClockBytes(): Long {
        val elapsedNanos = System.nanoTime() - startNanos
        return (elapsedNanos * outputRate / 1_000_000_000L) * 2L
    }

    private fun writeWavHeader(raf: RandomAccessFile) {
        val header = ByteArray(WAV_HEADER_SIZE)
        val byteRate = outputRate * NUM_CHANNELS * (BITS_PER_SAMPLE / 8)
        val blockAlign = NUM_CHANNELS * (BITS_PER_SAMPLE / 8)

        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()

        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        writeInt32LE(header, 16, 16)
        writeInt16LE(header, 20, 1)
        writeInt16LE(header, 22, NUM_CHANNELS)
        writeInt32LE(header, 24, outputRate)
        writeInt32LE(header, 28, byteRate)
        writeInt16LE(header, 32, blockAlign)
        writeInt16LE(header, 34, BITS_PER_SAMPLE)

        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()

        raf.write(header)
    }

    private fun RandomAccessFile.writeIntLE(v: Int) {
        write(v and 0xFF)
        write((v shr 8) and 0xFF)
        write((v shr 16) and 0xFF)
        write((v shr 24) and 0xFF)
    }

    private fun writeInt32LE(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = ((v shr 8) and 0xFF).toByte()
        buf[off + 2] = ((v shr 16) and 0xFF).toByte()
        buf[off + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun writeInt16LE(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = ((v shr 8) and 0xFF).toByte()
    }

    /** Linear interpolation resample from [srcRate] to [dstRate]. Input: 16-bit LE mono PCM. */
    private fun resample(pcm: ByteArray, srcRate: Int, dstRate: Int): ByteArray {
        val srcSamples = pcm.size / 2
        if (srcSamples == 0) return pcm
        val dstSamples = (srcSamples.toLong() * dstRate / srcRate).toInt()
        val out = ByteArray(dstSamples * 2)
        val ratio = srcRate.toDouble() / dstRate

        for (i in 0 until dstSamples) {
            val srcPos = i * ratio
            val idx = srcPos.toInt()
            val frac = srcPos - idx
            val s0 = readSample(pcm, idx)
            val s1 = if (idx + 1 < srcSamples) readSample(pcm, idx + 1) else s0
            val sample = (s0 + frac * (s1 - s0)).toInt().coerceIn(-32768, 32767)
            out[i * 2] = (sample and 0xFF).toByte()
            out[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun readSample(pcm: ByteArray, index: Int): Int {
        val off = index * 2
        return (pcm[off].toInt() and 0xFF) or (pcm[off + 1].toInt() shl 8)
    }
}
