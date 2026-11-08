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
import com.phonebibobibo.siren.EffectCategory
import com.phonebibobibo.siren.SirenProgram
import com.phonebibobibo.siren.SoundEffect
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

    fun playEffect(effect: SoundEffect) {
        synth.setEffect(effect)
        Log.i(TAG, "effect=${effect.name}")
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
    private enum class Mode { OFF, SIREN, AIR, MANUAL_HIGH, MANUAL_LOW, EFFECT }

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

    private var effect = SoundEffect.TRUCK_HORN
    private var fxPhaseA = 0.0
    private var fxPhaseB = 0.0
    private var fxNoise = 0.0
    private var fxSeed = 0x2468ACE1L

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
    fun setEffect(e: SoundEffect) {
        effect = e
        pending = Mode.EFFECT
        if (mode == Mode.EFFECT) restart = true
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
                Mode.EFFECT -> {
                    sample = effectSample() * modeGain
                    if (modeT >= effect.durationMs / 1000.0) pending = Mode.OFF
                }
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
            Mode.EFFECT -> {
                fxPhaseA = 0.0
                fxPhaseB = 0.0
                fxNoise = 0.0
                fxSeed = 0x2468ACE1L
            }
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

    private fun effectSample(): Double = when (effect.category) {
        EffectCategory.HORN -> hornSample(effect)
        EffectCategory.SHIP_RAIL -> when (effect) {
            SoundEffect.STATION_CHIME,
            SoundEffect.METRO_CHIME,
            SoundEffect.CROSSING_BELL -> chimeSample(effect)
            else -> hornSample(effect)
        }
        EffectCategory.ENGINE -> engineSample(effect)
        EffectCategory.SIGNAL -> when (effect) {
            SoundEffect.AIRPORT_CHIME,
            SoundEffect.BICYCLE_BELL -> chimeSample(effect)
            SoundEffect.CODE_BEEP -> codeBeepSample()
            SoundEffect.MORSE -> morseSample()
            SoundEffect.RADAR -> radarSample()
            SoundEffect.SCHOOL_BELL -> schoolBellSample()
            else -> 0.0
        }
        EffectCategory.SCENE -> when (effect) {
            SoundEffect.ICE_CREAM -> iceCreamSample()
            SoundEffect.REVERSING_BEEP -> reverseBeepSample(false)
            SoundEffect.TRUCK_REVERSE -> reverseBeepSample(true)
            SoundEffect.CONSTRUCTION_ALARM -> constructionSample()
            SoundEffect.AIR_RAID -> airRaidSample()
            SoundEffect.POLICE_PA -> policePaSample()
            else -> 0.0
        }
    }

    private data class Blast(val start: Double, val length: Double, val gain: Double = 1.0)

    private data class HornSpec(
        val freqs: List<Double>,
        val wave: WaveType,
        val attack: Double,
        val release: Double,
        val noise: Double,
        val vibrato: Double = 0.0,
        val vibratoHz: Double = 5.5,
        val sub: Double = 0.0,
        val wobble: Double = 0.0,
        val blasts: List<Blast>? = null
    )

    private fun hornSpec(e: SoundEffect): HornSpec = when (e) {
        SoundEffect.TRUCK_HORN -> HornSpec(
            freqs = listOf(196.0, 247.0),
            wave = WaveType.SHARP,
            attack = 0.06,
            release = 0.28,
            noise = 0.16,
            vibrato = 0.004,
            sub = 0.5
        )
        SoundEffect.CAR_HORN -> HornSpec(
            freqs = listOf(415.0, 523.0),
            wave = WaveType.BRIGHT,
            attack = 0.012,
            release = 0.14,
            noise = 0.0
        )
        SoundEffect.BUS_HORN -> HornSpec(
            freqs = listOf(233.0, 311.0),
            wave = WaveType.SHARP,
            attack = 0.09,
            release = 0.30,
            noise = 0.10,
            vibrato = 0.003,
            vibratoHz = 4.5,
            sub = 0.5
        )
        SoundEffect.MOTO_HORN -> HornSpec(
            freqs = listOf(520.0, 660.0),
            wave = WaveType.SHARP,
            attack = 0.010,
            release = 0.10,
            noise = 0.02
        )
        SoundEffect.VINTAGE_HORN -> HornSpec(
            freqs = listOf(190.0, 240.0),
            wave = WaveType.SHARP,
            attack = 0.040,
            release = 0.18,
            noise = 0.06,
            wobble = 0.14
        )
        SoundEffect.TRAIN_HORN -> HornSpec(
            freqs = listOf(311.0, 370.0, 466.0),
            wave = WaveType.SHARP,
            attack = 0.20,
            release = 0.40,
            noise = 0.12,
            vibrato = 0.002,
            vibratoHz = 4.0,
            sub = 0.5
        )
        SoundEffect.SHIP_HORN -> HornSpec(
            freqs = listOf(110.0, 164.81),
            wave = WaveType.LOW,
            attack = 0.30,
            release = 0.55,
            noise = 0.06,
            vibrato = 0.006,
            vibratoHz = 0.18,
            sub = 0.5
        )
        SoundEffect.WARSHIP_HORN -> HornSpec(
            freqs = listOf(220.0, 277.18),
            wave = WaveType.SHARP,
            attack = 0.025,
            release = 0.10,
            noise = 0.05,
            blasts = listOf(
                Blast(0.0, 0.45),
                Blast(0.65, 0.45),
                Blast(1.30, 0.90)
            )
        )
        SoundEffect.CRUISE_HORN -> HornSpec(
            freqs = listOf(147.0, 196.0, 247.0),
            wave = WaveType.BRIGHT,
            attack = 0.25,
            release = 0.50,
            noise = 0.08,
            vibrato = 0.004,
            vibratoHz = 0.12
        )
        else -> HornSpec(
            freqs = listOf(200.0),
            wave = WaveType.SHARP,
            attack = 0.05,
            release = 0.20,
            noise = 0.05
        )
    }

    private fun hornSample(e: SoundEffect): Double {
        val spec = hornSpec(e)
        val dur = e.durationMs / 1000.0
        val env = spec.blasts?.let { blasts ->
            var sum = 0.0
            for (b in blasts) {
                sum += blastEnv(modeT, b.start, b.length, spec.attack, spec.release) * b.gain
            }
            min(1.0, sum)
        } ?: attackRelease(modeT, dur, spec.attack, spec.release)
        if (env <= 0.0) return 0.0
        val vib = 1.0 + spec.vibrato * sin(2 * PI * spec.vibratoHz * modeT)
        val wobble = if (spec.wobble > 0.0) {
            1.0 + spec.wobble * sin(2 * PI * 3.0 * modeT)
        } else {
            1.0
        }
        var tone = 0.0
        spec.freqs.forEachIndexed { index, f ->
            val gain = when (index) {
                0 -> 0.50
                1 -> 0.45
                else -> 0.25
            }
            tone += wave(spec.wave, f * pitch * vib * wobble * modeT) * gain
        }
        if (spec.sub > 0.0) {
            tone += wave(WaveType.BRIGHT, spec.freqs.first() * spec.sub * modeT) * 0.18
        }
        val burst = if (spec.noise > 0.0) {
            nextNoise() * spec.noise * exp(-modeT * 8.0)
        } else {
            0.0
        }
        return (tone + burst) * env * 0.90
    }

    private enum class Timbre { BELL, SOFT, ELECTRONIC, MUSIC_BOX }

    private data class Note(
        val start: Double,
        val freq: Double,
        val decay: Double,
        val gain: Double = 1.0
    )

    private data class ChimeSpec(
        val notes: List<Note>,
        val timbre: Timbre,
        val amplitude: Double = 0.62
    )

    private fun chimeSpec(e: SoundEffect): ChimeSpec = when (e) {
        SoundEffect.STATION_CHIME -> ChimeSpec(
            notes = listOf(
                Note(start = 0.05, freq = 1046.50, decay = 0.55),
                Note(start = 0.62, freq = 783.99, decay = 0.75),
                Note(start = 1.55, freq = 1046.50, decay = 0.55, gain = 0.85),
                Note(start = 2.12, freq = 783.99, decay = 0.85, gain = 0.85)
            ),
            timbre = Timbre.BELL
        )
        SoundEffect.METRO_CHIME -> ChimeSpec(
            notes = listOf(
                Note(start = 0.00, freq = 1318.51, decay = 0.35),
                Note(start = 0.18, freq = 1046.50, decay = 0.35),
                Note(start = 0.36, freq = 783.99, decay = 0.45),
                Note(start = 0.54, freq = 1046.50, decay = 0.55)
            ),
            timbre = Timbre.ELECTRONIC,
            amplitude = 0.60
        )
        SoundEffect.AIRPORT_CHIME -> ChimeSpec(
            notes = listOf(
                Note(start = 0.00, freq = 523.25, decay = 0.80),
                Note(start = 0.35, freq = 659.25, decay = 0.80),
                Note(start = 0.70, freq = 783.99, decay = 0.90),
                Note(start = 1.05, freq = 1046.50, decay = 1.10)
            ),
            timbre = Timbre.SOFT,
            amplitude = 0.55
        )
        SoundEffect.CROSSING_BELL -> ChimeSpec(
            notes = List(6) { i ->
                Note(
                    start = i * 0.5,
                    freq = if (i % 2 == 0) 600.0 else 750.0,
                    decay = 0.35
                )
            },
            timbre = Timbre.BELL,
            amplitude = 0.55
        )
        SoundEffect.BICYCLE_BELL -> ChimeSpec(
            notes = listOf(
                Note(start = 0.00, freq = 2200.0, decay = 0.40),
                Note(start = 0.22, freq = 2400.0, decay = 0.45)
            ),
            timbre = Timbre.BELL,
            amplitude = 0.50
        )
        else -> ChimeSpec(
            notes = listOf(Note(start = 0.0, freq = 880.0, decay = 0.5)),
            timbre = Timbre.BELL,
            amplitude = 0.5
        )
    }

    private fun chimeSample(e: SoundEffect): Double {
        val spec = chimeSpec(e)
        var sum = 0.0
        for (n in spec.notes) {
            sum += noteSample(modeT - n.start, n.freq * pitch, n.decay, spec.timbre) * n.gain
        }
        return sum * spec.amplitude
    }

    private fun noteSample(dt: Double, freq: Double, decay: Double, timbre: Timbre): Double {
        if (dt < 0.0) return 0.0
        val attack = min(1.0, dt / if (timbre == Timbre.SOFT) 0.02 else 0.008)
        val env = attack * exp(-dt / decay)
        val tone = when (timbre) {
            Timbre.BELL -> sin(2 * PI * freq * dt) * 0.62 +
                sin(2 * PI * freq * 2.01 * dt) * 0.24 +
                sin(2 * PI * freq * 3.02 * dt) * 0.10 +
                sin(2 * PI * freq * 4.20 * dt) * 0.04
            Timbre.SOFT -> sin(2 * PI * freq * dt) * 0.70 +
                sin(2 * PI * freq * 2.00 * dt) * 0.15 +
                sin(2 * PI * freq * 3.00 * dt) * 0.05
            Timbre.ELECTRONIC -> sin(2 * PI * freq * dt) * 0.55 +
                sin(2 * PI * freq * 2.00 * dt) * 0.25 +
                sin(2 * PI * freq * 3.00 * dt) * 0.08
            Timbre.MUSIC_BOX -> sin(2 * PI * freq * dt) * 0.60 +
                sin(2 * PI * freq * 2.01 * dt) * 0.20 +
                sin(2 * PI * freq * 3.90 * dt) * 0.08
        }
        return tone * env
    }

    private enum class RevStyle { PORSCHE, ACCEL, MOTO, DIESEL, V8, RACE }

    private data class EngineSpec(
        val idle: Double,
        val top: Double,
        val harmonics: Int,
        val noise: Double,
        val whineHz: Double,
        val pulseDepth: Double,
        val style: RevStyle,
        val shifts: List<Double> = emptyList()
    )

    private fun engineSpec(e: SoundEffect): EngineSpec = when (e) {
        SoundEffect.PORSCHE_REV -> EngineSpec(
            idle = 48.0, top = 160.0, harmonics = 14, noise = 0.10,
            whineHz = 1800.0, pulseDepth = 0.05, style = RevStyle.PORSCHE
        )
        SoundEffect.ACCEL -> EngineSpec(
            idle = 62.0, top = 240.0, harmonics = 14, noise = 0.10,
            whineHz = 2600.0, pulseDepth = 0.05, style = RevStyle.ACCEL
        )
        SoundEffect.MOTO_REV -> EngineSpec(
            idle = 90.0, top = 300.0, harmonics = 10, noise = 0.08,
            whineHz = 3000.0, pulseDepth = 0.10, style = RevStyle.MOTO
        )
        SoundEffect.DIESEL_IDLE -> EngineSpec(
            idle = 32.0, top = 78.0, harmonics = 8, noise = 0.14,
            whineHz = 400.0, pulseDepth = 0.30, style = RevStyle.DIESEL
        )
        SoundEffect.V8_REV -> EngineSpec(
            idle = 42.0, top = 115.0, harmonics = 18, noise = 0.12,
            whineHz = 900.0, pulseDepth = 0.35, style = RevStyle.V8
        )
        SoundEffect.RACE_SHIFT -> EngineSpec(
            idle = 70.0, top = 250.0, harmonics = 14, noise = 0.12,
            whineHz = 2800.0, pulseDepth = 0.08, style = RevStyle.RACE,
            shifts = listOf(0.36, 0.62)
        )
        else -> EngineSpec(
            idle = 50.0, top = 150.0, harmonics = 12, noise = 0.10,
            whineHz = 1200.0, pulseDepth = 0.05, style = RevStyle.PORSCHE
        )
    }

    private fun engineSample(e: SoundEffect): Double {
        val spec = engineSpec(e)
        val dur = e.durationMs / 1000.0
        val x = (modeT / dur).coerceIn(0.0, 1.0)
        val curve = when (spec.style) {
            RevStyle.PORSCHE -> when {
                x < 0.08 -> 0.0
                x < 0.52 -> smoothstep((x - 0.08) / 0.44)
                x < 0.72 -> 1.0
                x < 0.92 -> 1.0 - 0.55 * smoothstep((x - 0.72) / 0.20)
                else -> 0.45 * (1.0 - smoothstep((x - 0.92) / 0.08))
            }
            RevStyle.ACCEL -> smoothstep(x)
            RevStyle.MOTO -> when {
                x < 0.35 -> smoothstep(x / 0.35)
                x < 0.72 -> 1.0
                else -> 1.0 - 0.65 * smoothstep((x - 0.72) / 0.28)
            }
            RevStyle.DIESEL -> 0.15 + 0.55 * smoothstep(x)
            RevStyle.V8 -> when {
                x < 0.55 -> smoothstep(x / 0.55)
                x < 0.80 -> 1.0
                else -> 1.0 - 0.60 * smoothstep((x - 0.80) / 0.20)
            }
            RevStyle.RACE -> when {
                x < 0.05 -> 0.0
                x < 0.85 -> smoothstep((x - 0.05) / 0.80)
                else -> 1.0 - 0.80 * smoothstep((x - 0.85) / 0.15)
            }
        }
        var shift = 1.0
        for (s in spec.shifts) {
            if (x > s) shift *= 0.78
        }
        val f = (spec.idle + (spec.top - spec.idle) * curve) * pitch * shift
        fxPhaseA += f / sr
        if (fxPhaseA >= 1.0) fxPhaseA -= floor(fxPhaseA)
        fxPhaseB += (400.0 + spec.whineHz * curve) / sr
        if (fxPhaseB >= 1.0) fxPhaseB -= floor(fxPhaseB)

        var saw = 0.0
        for (n in 1..spec.harmonics) {
            saw += sin(2 * PI * fxPhaseA * n) / n
        }
        saw *= 0.22
        val fundamental = sin(2 * PI * fxPhaseA) * 0.30
        val pulseHz = if (spec.style == RevStyle.DIESEL || spec.style == RevStyle.V8) {
            f * 0.5
        } else {
            f * 2.0
        }
        val pulse = 1.0 + spec.pulseDepth * sin(2 * PI * pulseHz * modeT)
        val lump = if (spec.style == RevStyle.DIESEL) {
            1.0 + 0.10 * sin(2 * PI * 3.1 * modeT)
        } else {
            1.0
        }
        val whine = sin(2 * PI * fxPhaseB) * 0.05 * curve
        val noise = nextNoise() * (0.04 + spec.noise * curve)
        val env = attackRelease(modeT, dur, 0.08, 0.35)
        return (saw * pulse * lump + fundamental * pulse + whine + noise) * env * 0.95
    }

    /** Alternating high/low short beeps, like a recognition code. */
    private fun codeBeepSample(): Double {
        val cycle = 0.155
        val lastIndex = 11
        val index = (modeT / cycle).toInt().coerceAtMost(lastIndex)
        val local = modeT - index * cycle
        val on = if (index == lastIndex) 0.20 else 0.075
        val freq = (if (index % 2 == 0) 1280.0 else 960.0) * pitch
        val env = if (local < on) {
            min(1.0, local / 0.008) * min(1.0, (on - local) / 0.015)
        } else {
            0.0
        }
        val tone = sin(2 * PI * freq * modeT) * 0.55 +
            sin(2 * PI * freq * 2.0 * modeT) * 0.20
        return tone * env * 0.85
    }

    /** Morse SOS: three dots, three dashes, three dots. */
    private fun morseSample(): Double {
        val unit = 0.12
        var cursor = 0.0
        val marks = ArrayList<DoubleArray>()
        val groups = listOf(
            listOf(1, 1, 1),
            listOf(3, 3, 3),
            listOf(1, 1, 1)
        )
        for ((gi, group) in groups.withIndex()) {
            for (symbol in group) {
                marks.add(doubleArrayOf(cursor, symbol * unit))
                cursor += symbol * unit + unit
            }
            if (gi < groups.lastIndex) cursor += 2.0 * unit
        }
        var env = 0.0
        for (m in marks) {
            val start = m[0]
            val length = m[1]
            if (modeT >= start && modeT < start + length) {
                val local = modeT - start
                env = min(1.0, local / 0.008) * min(1.0, (length - local) / 0.008)
                break
            }
        }
        if (env <= 0.0) return 0.0
        val tone = sin(2 * PI * 700.0 * pitch * modeT) * 0.55 +
            sin(2 * PI * 1400.0 * pitch * modeT) * 0.15
        return tone * env * 0.80
    }

    /** Sonar ping: a short decaying tone with a downward sweep. */
    private fun radarSample(): Double {
        val period = 1.10
        val local = modeT % period
        val env = if (local < 0.55) {
            min(1.0, local / 0.004) * exp(-local / 0.22) * min(1.0, (0.55 - local) / 0.05)
        } else {
            0.0
        }
        if (env <= 0.0) return 0.0
        val ping = sin(2 * PI * 1200.0 * pitch * local) * 0.55 +
            sin(2 * PI * 2400.0 * pitch * local) * 0.20
        val sweep = sin(2 * PI * (300.0 + 500.0 * local) * local) * 0.10
        return (ping + sweep) * env * 0.80
    }

    /** Continuous trilled electric school bell. */
    private fun schoolBellSample(): Double {
        val dur = effect.durationMs / 1000.0
        val env = attackRelease(modeT, dur, 0.02, 0.25)
        val trill = 0.5 + 0.5 * sin(2 * PI * 22.0 * modeT)
        val tone = sin(2 * PI * 900.0 * pitch * modeT) * 0.50 +
            sin(2 * PI * 1800.0 * pitch * modeT) * 0.20 +
            sin(2 * PI * 2700.0 * pitch * modeT) * 0.08
        return tone * trill * env * 0.80
    }

    /** Simple music-box melody associated with ice-cream trucks. */
    private fun iceCreamSample(): Double {
        val melody = listOf(
            523.25 to 0.00,
            659.25 to 0.30,
            783.99 to 0.60,
            659.25 to 0.90,
            523.25 to 1.20,
            659.25 to 1.50,
            783.99 to 1.80,
            880.00 to 2.10,
            783.99 to 2.40,
            659.25 to 2.70,
            523.25 to 3.00
        )
        var sum = 0.0
        for ((freq, start) in melody) {
            sum += noteSample(modeT - start, freq * pitch, 0.50, Timbre.MUSIC_BOX)
        }
        return sum * 0.48
    }

    /** Slow or fast reversing beep; the truck version adds a diesel rumble. */
    private fun reverseBeepSample(deep: Boolean): Double {
        val period = 1.0
        val local = modeT % period
        val on = 0.20
        if (local >= on) return 0.0
        val env = min(1.0, local / 0.01) * min(1.0, (on - local) / 0.03)
        val freq = if (deep) 500.0 else 800.0
        val tone = sin(2 * PI * freq * pitch * modeT) * 0.55 +
            sin(2 * PI * freq * 2.0 * pitch * modeT) * 0.15
        val diesel = if (deep) {
            sin(2 * PI * 60.0 * modeT) * 0.12 + nextNoise() * 0.03
        } else {
            0.0
        }
        return (tone + diesel) * env * 0.85
    }

    /** Rapid alternating two-tone construction alarm. */
    private fun constructionSample(): Double {
        val period = 0.25
        val index = (modeT / period).toInt()
        val local = modeT - index * period
        val on = 0.12
        if (local >= on) return 0.0
        val env = min(1.0, local / 0.006) * min(1.0, (on - local) / 0.02)
        val freq = if (index % 2 == 0) 1000.0 else 800.0
        val tone = sin(2 * PI * freq * pitch * modeT) * 0.55 +
            sin(2 * PI * freq * 2.0 * pitch * modeT) * 0.15
        return tone * env * 0.80
    }

    /** Air-raid siren: a slow rise and fall over the full duration. */
    private fun airRaidSample(): Double {
        val dur = effect.durationMs / 1000.0
        val x = (modeT / dur).coerceIn(0.0, 1.0)
        val f = (300.0 + 650.0 * (0.5 - 0.5 * cos(2 * PI * x))) * pitch
        fxPhaseA += f / sr
        if (fxPhaseA >= 1.0) fxPhaseA -= floor(fxPhaseA)
        val env = attackRelease(modeT, dur, 0.10, 0.40)
        val tone = sin(2 * PI * fxPhaseA) * 0.55 +
            sin(2 * PI * fxPhaseA * 2.0) * 0.18 +
            sin(2 * PI * fxPhaseA * 3.0) * 0.08
        return tone * env * 0.85
    }

    /** Police radio: a noise squelch followed by a two-tone test. */
    private fun policePaSample(): Double {
        val squelch = if (modeT < 0.08) {
            nextNoise() * (1.0 - modeT / 0.08) * 0.35
        } else {
            0.0
        }
        val local = modeT - 0.10
        val active = local >= 0.0 && local < 0.30
        val env = if (active) {
            min(1.0, local / 0.01) * min(1.0, (0.30 - local) / 0.02)
        } else {
            0.0
        }
        val tone = if (active) {
            val f = if (local < 0.15) 900.0 else 1100.0
            sin(2 * PI * f * pitch * local) * 0.45 +
                sin(2 * PI * f * 2.0 * pitch * local) * 0.12
        } else {
            0.0
        }
        return (tone * env + squelch) * 0.85
    }

    private fun smoothstep(x: Double): Double {
        val t = x.coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    private fun attackRelease(
        t: Double, duration: Double, attack: Double, release: Double
    ): Double {
        if (t < 0.0 || t > duration) return 0.0
        return min(1.0, t / attack) * min(1.0, (duration - t) / release)
    }

    private fun blastEnv(
        t: Double, start: Double, length: Double, attack: Double, release: Double
    ): Double {
        val x = t - start
        if (x < 0.0 || x > length) return 0.0
        return min(1.0, x / attack) * min(1.0, (length - x) / release)
    }

    private fun nextNoise(): Double {
        fxSeed = fxSeed * 1664525L + 1013904223L
        val raw = (((fxSeed ushr 16) and 0xFFFF).toDouble() / 32768.0) - 1.0
        fxNoise = fxNoise * 0.84 + raw * 0.16
        return fxNoise
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
