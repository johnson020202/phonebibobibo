package com.phonebibobibo.siren

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.phonebibobibo.siren.audio.SampleBank
import com.phonebibobibo.siren.audio.SirenEngine

class SirenController(context: Context) {

    private val appContext = context.applicationContext
    val engine = SirenEngine(appContext)
    private val samples = SampleBank(appContext)
    private val torch = TorchController(appContext)
    private val recorder = BroadcastRecorder(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    var activeSiren by mutableStateOf<SirenProgram?>(null)
        private set
    var lightMode by mutableStateOf<LightMode?>(null)
        private set
    var lightRunning by mutableStateOf(false)
        private set
    var manualHeld by mutableStateOf(false)
        private set
    var manualHigh by mutableStateOf(true)
        private set
    var manualTail by mutableStateOf(false)
        private set
    var airHornActive by mutableStateOf(false)
        private set
    var activeEffect by mutableStateOf<SoundEffect?>(null)
        private set
    var muteFlash by mutableStateOf(false)
        private set
    var subBassOn by mutableStateOf(false)
        private set
    var lowPower by mutableStateOf(false)
        private set
    var recording by mutableStateOf(false)
        private set
    var replaying by mutableStateOf(false)
        private set
    var volume by mutableFloatStateOf(0.85f)
        private set
    var transpose by mutableIntStateOf(0)
        private set
    var statusText by mutableStateOf("系统就绪")
        private set

    val keepScreenOn: Boolean
        get() = activeSiren != null || activeEffect != null || manualHeld || manualTail ||
            recording || replaying || subBassOn || lightRunning

    private var manualToken = 0
    private var airToken = 0
    private var effectToken = 0
    private var muteToken = 0

    init {
        engine.setVolume(volume)
        engine.onFocusLost = { onAudioLost() }
        engine.onError = { message ->
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
        }
        torch.onError = { mainHandler.post { onTorchError() } }
    }

    fun toggleSiren(program: SirenProgram) {
        if (recording) cancelRecording()
        if (replaying) {
            recorder.stopPlayback()
            replaying = false
        }
        activeEffect = null
        effectToken++
        if (activeSiren == program) {
            activeSiren = null
            stopAllAudio()
            if (lightMode == LightMode.AUTO) {
                torch.stop()
                lightRunning = false
            }
            refreshStatus()
            return
        }

        manualHeld = false
        manualTail = false
        airHornActive = false
        samples.stop()
        activeSiren = program
        engine.playSiren(program)
        if (lightMode == LightMode.AUTO) {
            lightRunning = torch.start(LightMode.AUTO)
        }
        refreshStatus()
    }

    fun playEffect(effect: SoundEffect) {
        if (recording) cancelRecording()
        if (replaying) {
            recorder.stopPlayback()
            replaying = false
        }
        activeSiren = null
        manualHeld = false
        manualTail = false
        airHornActive = false
        activeEffect = effect
        if (samples.has(effect)) {
            engine.stopAll()
            samples.play(effect)
        } else {
            samples.stop()
            engine.playEffect(effect)
        }
        if (lightMode == LightMode.AUTO) {
            torch.stop()
            lightRunning = false
        }
        effectToken++
        refreshStatus()
        val token = effectToken
        mainHandler.postDelayed({
            if (token == effectToken) {
                activeEffect = null
                refreshStatus()
            }
        }, effect.durationMs + 120)
    }

    fun toggleEffect(effect: SoundEffect) {
        if (activeEffect == effect) {
            activeEffect = null
            effectToken++
            stopAllAudio()
            refreshStatus()
        } else {
            playEffect(effect)
        }
    }

    fun triggerAirHorn() {
        if (airHornActive) {
            airHornActive = false
            airToken++
            stopAllAudio()
            refreshStatus()
            return
        }
        if (recording) cancelRecording()
        if (replaying) {
            recorder.stopPlayback()
            replaying = false
        }
        activeSiren = null
        activeEffect = null
        effectToken++
        manualHeld = false
        manualTail = false
        subBassOn = false
        samples.stop()
        engine.setSubBass(false)
        engine.playAirHorn()
        airHornActive = true
        if (lightMode == LightMode.AUTO) {
            torch.stop()
            lightRunning = false
        }
        refreshStatus()
    }

    fun startManual(high: Boolean) {
        if (recording) cancelRecording()
        if (replaying) {
            recorder.stopPlayback()
            replaying = false
        }
        activeSiren = null
        activeEffect = null
        effectToken++
        airHornActive = false
        samples.stop()
        manualHigh = high
        manualHeld = true
        manualTail = false
        manualToken++
        engine.startManual(high)
        if (lightMode == LightMode.AUTO) {
            torch.stop()
            lightRunning = false
        }
        refreshStatus()
    }

    fun toggleManual(high: Boolean) {
        if (manualHeld && manualHigh == high) {
            manualHeld = false
            manualTail = false
            manualToken++
            stopAllAudio()
            refreshStatus()
            return
        }
        startManual(high)
    }

    fun toggleLight(mode: LightMode) {
        if (lightMode == mode) {
            lightMode = null
            lightRunning = false
            torch.stop()
            refreshStatus()
            return
        }

        if (mode == LightMode.AUTO) {
            lightMode = mode
            if (activeSiren != null) {
                val ok = torch.start(LightMode.AUTO)
                lightRunning = ok
                if (!ok) {
                    lightMode = null
                    showTorchUnavailable()
                }
            } else {
                torch.stop()
                lightRunning = false
            }
            refreshStatus()
            return
        }

        val ok = torch.start(mode)
        if (ok) {
            lightMode = mode
            lightRunning = true
        } else {
            lightMode = null
            lightRunning = false
            showTorchUnavailable()
        }
        refreshStatus()
    }

    fun toggleSubBass() {
        subBassOn = !subBassOn
        engine.setSubBass(subBassOn)
        refreshStatus()
    }

    fun toggleLowPower() {
        lowPower = !lowPower
        engine.setLowPower(lowPower)
        samples.setLowPower(lowPower)
    }

    fun updateVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        engine.setVolume(volume)
        samples.setVolume(volume)
    }

    fun updateTranspose(semitones: Int) {
        transpose = semitones.coerceIn(-6, 6)
        engine.setTranspose(transpose)
    }

    fun muteAll() {
        activeSiren = null
        activeEffect = null
        effectToken++
        manualHeld = false
        manualTail = false
        airHornActive = false
        subBassOn = false
        if (recording) cancelRecording()
        recorder.stopPlayback()
        replaying = false
        stopAllAudio()
        torch.stop()
        lightRunning = false
        lightMode = null
        muteFlash = true
        manualToken++
        airToken++
        refreshStatus()
        val token = ++muteToken
        mainHandler.postDelayed({
            if (token == muteToken) muteFlash = false
        }, 750)
    }

    fun startRecording() {
        if (recording) return
        activeSiren = null
        activeEffect = null
        effectToken++
        manualHeld = false
        manualTail = false
        airHornActive = false
        if (replaying) {
            recorder.stopPlayback()
            replaying = false
        }
        stopAllAudio()
        torch.stop()
        lightRunning = false
        lightMode = null
        if (recorder.start()) {
            recording = true
            refreshStatus()
        } else {
            Toast.makeText(appContext, "录音启动失败，请检查麦克风权限", Toast.LENGTH_SHORT).show()
            refreshStatus()
        }
    }

    fun stopRecordingAndPlay() {
        if (!recording) return
        recording = false
        val ok = recorder.stop()
        if (!ok) {
            Toast.makeText(appContext, "录音太短，请按住说话", Toast.LENGTH_SHORT).show()
            refreshStatus()
            return
        }
        replaying = true
        refreshStatus()
        val started = recorder.play {
            replaying = false
            refreshStatus()
        }
        if (!started) {
            replaying = false
            refreshStatus()
            Toast.makeText(appContext, "回放失败", Toast.LENGTH_SHORT).show()
        }
    }

    fun onBackground() {
        activeSiren = null
        activeEffect = null
        effectToken++
        manualHeld = false
        manualTail = false
        airHornActive = false
        subBassOn = false
        if (recording) cancelRecording()
        recorder.stopPlayback()
        replaying = false
        stopAllAudio()
        torch.stop()
        lightRunning = false
        lightMode = null
        refreshStatus()
    }

    fun release() {
        onBackground()
        engine.release()
        samples.release()
        recorder.release()
        torch.release()
    }

    private fun stopAllAudio() {
        engine.stopAll()
        samples.stop()
    }

    private fun cancelRecording() {
        recorder.cancel()
        recording = false
        replaying = false
    }

    private fun onAudioLost() {
        activeSiren = null
        activeEffect = null
        effectToken++
        manualHeld = false
        manualTail = false
        airHornActive = false
        subBassOn = false
        stopAllAudio()
        torch.stop()
        lightRunning = false
        lightMode = null
        refreshStatus()
        Toast.makeText(appContext, "音频被其他应用占用，已停止", Toast.LENGTH_SHORT).show()
    }

    private fun onTorchError() {
        lightMode = null
        lightRunning = false
        torch.stop()
        refreshStatus()
        showTorchUnavailable()
    }

    private fun showTorchUnavailable() {
        Toast.makeText(appContext, "手电筒不可用或已被其他应用占用", Toast.LENGTH_SHORT).show()
    }

    private fun refreshStatus() {
        statusText = when {
            recording -> "录音中 · 松开结束"
            replaying -> "回放中 · 车外广播"
            manualHeld -> "手动电笛 · " + if (manualHigh) "高音持续" else "低音持续"
            manualTail -> "手动电笛 · " + if (manualHigh) "高音持续" else "低音持续"
            airHornActive -> "气笛号角 · 触发"
            activeEffect != null -> "车辆音效 · ${activeEffect?.label.orEmpty()}"
            activeSiren != null -> "运行中 · ${activeSiren?.label.orEmpty()}"
            lightRunning -> "警灯 · ${lightMode?.label.orEmpty()}"
            subBassOn -> "重低音脉冲 · 开启"
            else -> "系统就绪"
        }
    }
}
