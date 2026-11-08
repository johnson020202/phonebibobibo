package com.phonebibobibo.siren.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.phonebibobibo.siren.SirenProgram
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Real-time siren synthesis. A single AudioTrack is shared by every program; the
 * synth switches programs with a 20 ms gain ramp so button presses never click.
 */
class SirenEngine(context: Context) {

    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val sampleRate = 48_000
    private val blockSize = 512
    private val synth = SirenSynth(sampleRate)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var running = true
    @Volatile private var paused = true
    @Volatile private var track: AudioTrack? = null
    @Volatile private var focusHeld = false

    @Volatile var level: Float = 0f
        private set
    @Volatile var sounding: Boolean = false
        private set

    var onFocusLost: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private var focusRequest: AudioFocusRequest? = null

    private val thread = Thread({ audioLoop() }, "phone-siren-audio").apply {
        isDaemon = true
        start()
    }

    fun playSiren(program: SirenProgram) {
        synth.setProgram(program)
        Log.i(TAG, "siren=${program.name}")
        wake()
    }

    fun playAirHorn() {
        synth.triggerAirHorn()
        Log.i(TAG, "air-horn")
        wake()
    }

    fun startManual(high: Boolean) {
        synth.startManual(high)
        Log.i(TAG, "manual=${if (high) "high" else "low"} hold")
        wake()
    }

    fun releaseManual() {
        synth.releaseManual()
        Log.i(TAG, "manual release")
    }

    fun setSubBass(on: Boolean) {
        synth.setSubBass(on)
        Log.i(TAG, "sub-bass=$on")
        if (on) wake()
    }

    fun stopMain() = synth.stopMain()

    fun stopAll() = synth.stopAll()

    fun setLowPower(on: Boolean) = synth.setLowPower(on)

    fun setVolume(value: Float) = synth.setVolume(value)

    fun setTranspose(semitones: Int) = synth.setTranspose(semitones)

    fun release() {
        running = false
        synth.stopAll()
        runCatching { thread.join(600) }
        releaseTrack()
        abandonFocus()
    }

    private fun wake() {
        try {
            val t = track ?: createTrack()
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
            paused = false
            requestFocus()
        } catch (e: Exception) {
            Log.e(TAG, "audio start failed", e)
            mainHandler.post { onError?.invoke("音频初始化失败") }
        }
    }

    private fun createTrack(): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        val bufferBytes = max(minBuffer, blockSize * 4 * 6)
        val newTrack = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        track = newTrack
        return newTrack
    }

    private fun audioLoop() {
        val buffer = FloatArray(blockSize)
        var quietBlocks = 0
        var logCounter = 0
        while (running) {
            if (paused) {
                Thread.sleep(20)
                continue
            }
            synth.render(buffer, blockSize)
            val active = synth.isSounding()
            sounding = active
            level = synth.lastLevel
            val currentTrack = track
            if (currentTrack == null) {
                paused = true
                continue
            }
            if (active) {
                quietBlocks = 0
                if (++logCounter >= 180) {
                    logCounter = 0
                    Log.d(TAG, "rms=${"%.3f".format(synth.lastLevel)}")
                }
            } else {
                quietBlocks++
                if (quietBlocks > 40) {
                    runCatching {
                        if (currentTrack.playState == AudioTrack.PLAYSTATE_PLAYING) currentTrack.pause()
                    }
                    paused = true
                    abandonFocus()
                }
            }
            val written = runCatching {
                currentTrack.write(buffer, 0, blockSize, AudioTrack.WRITE_BLOCKING)
            }.getOrElse { -1 }
            if (written < 0) {
                Log.e(TAG, "AudioTrack.write failed: $written")
                releaseTrack()
                paused = true
                mainHandler.post { onError?.invoke("音频输出失败") }
            }
        }
    }

    private fun requestFocus() {
        if (focusHeld) return
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                ) {
                    synth.stopAll()
                    mainHandler.post { onFocusLost?.invoke() }
                }
            }
            .build()
            .also { focusRequest = it }
        focusHeld = audioManager.requestAudioFocus(request) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        if (focusHeld) {
            runCatching { audioManager.abandonAudioFocusRequest(request) }
            focusHeld = false
        }
    }

    private fun releaseTrack() {
        track?.let { t ->
            runCatching { t.stop() }
            runCatching { t.release() }
        }
        track = null
    }

    private companion object {
        const val TAG = "PhoneSiren"
    }
}

/** Sample-accurate synth shared by all programs. */
private class SirenSynth(private val sr: Int) {

    private enum class WaveType { BRIGHT, SHARP, LOW }
    private enum class Motion { SWEEP, RISE, HILO, STEADY }
    private enum class Mode { OFF, SIREN, AIR, MANUAL_HIGH, MANUAL_LOW }

    private data class Layer(
        val low: Double,
        val high: Double,
        val period: Double,
        val motion: Motion,
        val gain: Double,
        val wave: WaveType,
        val amHz: Double = 0.0,
        val amDepth: Double = 0.0,
        val vibHz: Double = 0.0,
        val vibDepth: Double = 0.0
    )

    private var mode = Mode.OFF
    private var pending = Mode.OFF
    private var program = SirenProgram.WAIL_STD
    private var restart = false
    private var modeGain = 0f
    private var modeT = 0.0

    private var layers: List<Layer> = emptyList()
    private val phases = DoubleArray(2)
    private var rotIndex = 0
    private var rotSlotStart = 0.0
    private var rotEnv = 1.0

    private var airPhase1 = 0.0
    private var airPhase2 = 0.0
    private var airNoise = 0.0
    private var noiseSeed = 0x12345678L

    private var manHeld = false
    private var manFreq = 560.0
    private var manGain = 0.0
    private var manPhase = 0.0

    private var subGain = 0f
    private var subPhase = 0.0
    private var subT = 0.0

    private var volume = 0.85f
    private var lowPower = false
    private var transpose = 0
    private var pitch = 1.0
    private var subBass = false

    var lastLevel = 0f
        private set

    private val rotationOrder = listOf(
        SirenProgram.WAIL_STD,
        SirenProgram.YELP,
        SirenProgram.HI_LO,
        SirenProgram.TREMOLO,
        SirenProgram.PHASER,
        SirenProgram.WAIL_FAST
    )

    @Synchronized
    fun setProgram(p: SirenProgram) {
        program = p
        pending = Mode.SIREN
        if (mode == Mode.SIREN) restart = true
    }

    @Synchronized
    fun triggerAirHorn() {
        if (mode == Mode.AIR) {
            modeT = 0.0
            airPhase1 = 0.0
            airPhase2 = 0.0
        } else {
            pending = Mode.AIR
        }
    }

    @Synchronized
    fun startManual(high: Boolean) {
        val target = if (high) Mode.MANUAL_HIGH else Mode.MANUAL_LOW
        pending = target
        if (mode == target) manHeld = true
    }

    @Synchronized
    fun releaseManual() {
        manHeld = false
    }

    @Synchronized
    fun stopMain() {
        pending = Mode.OFF
        manHeld = false
    }

    @Synchronized
    fun stopAll() {
        pending = Mode.OFF
        manHeld = false
        subBass = false
    }

    @Synchronized
    fun setSubBass(on: Boolean) {
        subBass = on
    }

    @Synchronized
    fun setLowPower(on: Boolean) {
        lowPower = on
    }

    @Synchronized
    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
    }

    @Synchronized
    fun setTranspose(semitones: Int) {
        transpose = semitones.coerceIn(-12, 12)
        pitch = 2.0.pow(transpose / 12.0)
    }

    @Synchronized
    fun isSounding(): Boolean =
        mode != Mode.OFF || pending != Mode.OFF || modeGain > 0.0005f ||
            subBass || subGain > 0.0005f

    @Synchronized
    fun render(out: FloatArray, frames: Int) {
        val dt = 1.0 / sr
        val gainStep = (1.0 / (0.02 * sr)).toFloat()
        var sumSquares = 0.0
        for (i in 0 until frames) {
            val rampUp = mode == pending && mode != Mode.OFF && !restart
            if (rampUp) {
                if (modeGain < 1f) modeGain = min(1f, modeGain + gainStep)
            } else {
                if (modeGain > 0f) modeGain = max(0f, modeGain - gainStep)
                if (modeGain <= 0f && (mode != pending || restart)) {
                    switchMode(pending)
                    restart = false
                }
            }

            var sample = 0.0
            when (mode) {
                Mode.SIREN -> sample = sirenSample() * modeGain
                Mode.AIR -> {
                    sample = airSample() * modeGain
                    if (modeT >= AIR_DURATION) pending = Mode.OFF
                }
                Mode.MANUAL_HIGH -> sample = manualSample(dt, true) * modeGain
                Mode.MANUAL_LOW -> sample = manualSample(dt, false) * modeGain
                Mode.OFF -> Unit
            }

            val subTarget = if (subBass) 1f else 0f
            subGain += (subTarget - subGain) * (dt / 0.05).toFloat().coerceIn(0f, 1f)
            if (subGain > 0.0004f) sample += subSample(dt) * subGain

            val master = volume * (if (lowPower) 0.25f else 1f)
            val y = tanh(sample * master * 1.25) * 0.92
            out[i] = y.toFloat()
            sumSquares += y * y
            if (mode != Mode.OFF) modeT += dt
        }
        lastLevel = sqrt(sumSquares / frames).toFloat()
    }

    private fun switchMode(newMode: Mode) {
        mode = newMode
        modeT = 0.0
        when (newMode) {
            Mode.SIREN -> {
                if (program == SirenProgram.ROTATE) {
                    rotIndex = 0
                    rotSlotStart = 0.0
                    layers = layersFor(SirenProgram.WAIL_STD)
                } else {
                    layers = layersFor(program)
                }
                rotEnv = 1.0
                phases.fill(0.0)
            }
            Mode.AIR -> {
                airPhase1 = 0.0
                airPhase2 = 0.0
                airNoise = 0.0
            }
            Mode.MANUAL_HIGH -> initManual(true)
            Mode.MANUAL_LOW -> initManual(false)
            Mode.OFF -> Unit
        }
    }

    private fun initManual(high: Boolean) {
        manHeld = true
        manFreq = (if (high) 560.0 else 250.0) * pitch
        manGain = 0.0
        manPhase = 0.0
    }

    private fun sirenSample(): Double {
        if (program == SirenProgram.ROTATE) {
            val slot = 4.5
            val index = (modeT / slot).toInt()
            if (index != rotIndex) {
                rotIndex = index
                layers = layersFor(rotationOrder[index % rotationOrder.size])
                phases.fill(0.0)
            }
            val inSlot = modeT - index * slot
            rotEnv = min(1.0, inSlot / 0.12) * min(1.0, (slot - inSlot) / 0.18)
        } else {
            rotEnv = 1.0
        }

        var sum = 0.0
        for (index in layers.indices) {
            val layer = layers[index]
            val freq = layerFrequency(layer, modeT)
            var phase = phases[index] + freq / sr
            if (phase >= 1.0) phase -= floor(phase)
            phases[index] = phase
            var amp = layer.gain
            if (layer.amHz > 0.0) {
                amp *= (1.0 - layer.amDepth) +
                    layer.amDepth * (0.5 + 0.5 * cos(2 * PI * layer.amHz * modeT + index))
            }
            sum += wave(layer.wave, phase) * amp
        }
        return sum * rotEnv * 0.58
    }

    private fun layerFrequency(layer: Layer, time: Double): Double {
        val fraction = (time % layer.period) / layer.period
        val x = when (layer.motion) {
            Motion.SWEEP -> 0.5 - 0.5 * cos(2 * PI * fraction)
            Motion.RISE -> if (fraction < 0.28) fraction / 0.28 else 1.0 - (fraction - 0.28) / 0.72
            Motion.HILO -> if ((time / layer.period).toInt() % 2 == 0) 1.0 else 0.0
            Motion.STEADY -> 0.5
        }
        var freq = layer.low + (layer.high - layer.low) * x
        if (layer.vibHz > 0.0) {
            freq *= 1.0 + layer.vibDepth * sin(2 * PI * layer.vibHz * time)
        }
        return freq * pitch
    }

    private fun airSample(): Double {
        val x = (modeT / AIR_DURATION).coerceIn(0.0, 1.0)
        val envelope = when {
            x < 0.012 -> x / 0.012
            x < 0.72 -> 1.0
            else -> ((1.0 - x) / 0.28).coerceIn(0.0, 1.0)
        }
        val f1 = 392.0 * (1.0 + 0.025 * exp(-9.0 * x)) * pitch
        val f2 = f1 * 1.26
        airPhase1 += f1 / sr
        if (airPhase1 >= 1.0) airPhase1 -= 1.0
        airPhase2 += f2 / sr
        if (airPhase2 >= 1.0) airPhase2 -= 1.0

        val tone = wave(WaveType.SHARP, airPhase1) * 0.55 +
            wave(WaveType.SHARP, airPhase2) * 0.45

        noiseSeed = noiseSeed * 1664525L + 1013904223L
        val rawNoise = (((noiseSeed ushr 16) and 0xFFFF).toDouble() / 32768.0) - 1.0
        airNoise = airNoise * 0.86 + rawNoise * 0.14
        val burst = if (x < 0.10) airNoise * (1.0 - x / 0.10) * 0.4 else 0.0
        val rough = 1.0 + 0.035 * sin(2 * PI * 31.0 * modeT)
        return (tone + burst) * envelope * rough * 0.62
    }

    private fun manualSample(dt: Double, high: Boolean): Double {
        val target = if (manHeld) {
            (if (high) 1450.0 else 620.0) * pitch
        } else {
            (if (high) 430.0 else 180.0) * pitch
        }
        val tau = when {
            manHeld -> if (high) 0.30 else 0.55
            high -> 0.16
            else -> 0.75
        }
        manFreq += (target - manFreq) * (1.0 - exp(-dt / tau))

        if (manGain < 0.9) manGain = min(0.9, manGain + dt / 0.025)
        if (!manHeld) manGain *= exp(-dt / (if (high) 0.30 else 0.85))
        if (!manHeld && manGain < 0.002) pending = Mode.OFF

        manPhase += manFreq / sr
        if (manPhase >= 1.0) manPhase -= floor(manPhase)

        val tone = if (high) {
            wave(WaveType.SHARP, manPhase) * 0.8 + wave(WaveType.BRIGHT, manPhase) * 0.2
        } else {
            wave(WaveType.BRIGHT, manPhase) * 0.7 + sin(PI * manPhase) * 0.15
        }
        return tone * manGain * 0.7
    }

    private fun subSample(dt: Double): Double {
        subT += dt
        subPhase += (46.0 + 4.0 * sin(2 * PI * 0.2 * subT)) / sr
        if (subPhase >= 1.0) subPhase -= 1.0
        val pulse = 0.5 + 0.5 * cos(2 * PI * 2.0 * subT)
        val envelope = 0.08 + 0.92 * pulse * pulse * pulse
        val tone = sin(2 * PI * subPhase) * 0.7 +
            sin(4 * PI * subPhase) * 0.2 +
            sin(6 * PI * subPhase) * 0.1
        return tone * envelope * 0.55
    }

    private fun wave(type: WaveType, phase: Double): Double = when (type) {
        WaveType.BRIGHT ->
            sin(2 * PI * phase) * 0.62 +
                sin(4 * PI * phase) * 0.24 +
                sin(6 * PI * phase) * 0.10 +
                sin(8 * PI * phase) * 0.04

        WaveType.SHARP -> tanh(5.0 * sin(2 * PI * phase)) * 0.85

        WaveType.LOW ->
            sin(2 * PI * phase) * 0.68 +
                sin(4 * PI * phase) * 0.22 +
                sin(6 * PI * phase) * 0.10
    }

    private fun layersFor(p: SirenProgram): List<Layer> = when (p) {
        SirenProgram.WAIL_STD -> listOf(
            Layer(640.0, 1520.0, 4.2, Motion.SWEEP, 1.0, WaveType.BRIGHT)
        )
        SirenProgram.WAIL_SLOW -> listOf(
            Layer(620.0, 1460.0, 7.0, Motion.SWEEP, 1.0, WaveType.BRIGHT)
        )
        SirenProgram.WAIL_FAST -> listOf(
            Layer(700.0, 1600.0, 2.6, Motion.SWEEP, 1.0, WaveType.BRIGHT)
        )
        SirenProgram.YELP -> listOf(
            Layer(880.0, 1680.0, 0.34, Motion.SWEEP, 1.0, WaveType.BRIGHT)
        )
        SirenProgram.PURSUIT -> listOf(
            Layer(
                720.0, 1800.0, 1.2, Motion.RISE, 1.0, WaveType.BRIGHT,
                vibHz = 5.0, vibDepth = 0.012
            )
        )
        SirenProgram.PHASER -> listOf(
            Layer(1250.0, 2050.0, 0.19, Motion.SWEEP, 0.9, WaveType.SHARP),
            Layer(1300.0, 2100.0, 0.23, Motion.SWEEP, 0.35, WaveType.SHARP)
        )
        SirenProgram.TREMOLO -> listOf(
            Layer(
                1180.0, 1180.0, 1.0, Motion.STEADY, 1.0, WaveType.BRIGHT,
                amHz = 21.0, amDepth = 0.93
            )
        )
        SirenProgram.HI_LO -> listOf(
            Layer(700.0, 980.0, 0.34, Motion.HILO, 1.0, WaveType.BRIGHT)
        )
        SirenProgram.DUAL_A -> listOf(
            Layer(640.0, 1520.0, 4.2, Motion.SWEEP, 0.55, WaveType.BRIGHT),
            Layer(880.0, 1680.0, 0.34, Motion.SWEEP, 0.50, WaveType.BRIGHT)
        )
        SirenProgram.DUAL_B -> listOf(
            Layer(880.0, 1680.0, 0.34, Motion.SWEEP, 0.50, WaveType.BRIGHT),
            Layer(
                1180.0, 1180.0, 1.0, Motion.STEADY, 0.50, WaveType.BRIGHT,
                amHz = 21.0, amDepth = 0.93
            )
        )
        SirenProgram.ROTATE -> layersFor(SirenProgram.WAIL_STD)
    }

    private companion object {
        const val AIR_DURATION = 1.18
    }
}
