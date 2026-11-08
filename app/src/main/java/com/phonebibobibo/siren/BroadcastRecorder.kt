package com.phonebibobibo.siren

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Push-to-talk broadcast: hold to record from the mic, release to replay through
 * the media stream (speaker). Files stay in cache and are replaced on each use.
 */
class BroadcastRecorder(context: Context) {

    private val appContext = context.applicationContext
    private val file = File(appContext.cacheDir, "broadcast.m4a")

    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null

    var isRecording: Boolean = false
        private set

    fun start(): Boolean {
        stopPlayback()
        releaseRecorder()
        file.delete()
        return try {
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(appContext)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(96_000)
            r.setMaxDuration(30_000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            isRecording = true
            Log.i(TAG, "recording start")
            true
        } catch (e: Exception) {
            Log.e(TAG, "recording start failed", e)
            releaseRecorder()
            false
        }
    }

    fun stop(): Boolean {
        val r = recorder ?: return false
        isRecording = false
        val ok = try {
            r.stop()
            true
        } catch (e: Exception) {
            Log.w(TAG, "recording stop failed: ${e.message}")
            false
        } finally {
            releaseRecorder()
        }
        if (!ok) return false
        val valid = file.exists() && file.length() > 2_000
        Log.i(TAG, "recording stop valid=$valid bytes=${file.length()}")
        return valid
    }

    fun play(onFinished: () -> Unit): Boolean {
        stopPlayback()
        return try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    stopPlayback()
                    onFinished()
                }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "playback error what=$what extra=$extra")
                    stopPlayback()
                    onFinished()
                    true
                }
                prepare()
                start()
            }
            Log.i(TAG, "broadcast playback start")
            true
        } catch (e: Exception) {
            Log.e(TAG, "broadcast playback failed", e)
            stopPlayback()
            false
        }
    }

    fun cancel() {
        releaseRecorder()
        isRecording = false
        file.delete()
    }

    fun stopPlayback() {
        player?.let {
            runCatching { if (it.isPlaying) it.stop() }
            runCatching { it.release() }
        }
        player = null
    }

    fun release() {
        cancel()
        stopPlayback()
    }

    private fun releaseRecorder() {
        recorder?.let {
            runCatching { it.reset() }
            runCatching { it.release() }
        }
        recorder = null
    }

    private companion object {
        const val TAG = "PhoneSiren"
    }
}
