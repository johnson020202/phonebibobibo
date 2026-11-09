package com.phonebibobibo.siren.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import com.phonebibobibo.siren.R
import com.phonebibobibo.siren.SoundEffect

/**
 * Plays the real recordings that back the newer scene effects. The older eight
 * effects stay on the synth so their tuning and response remain unchanged.
 */
class SampleBank(context: Context) {

    private val appContext = context.applicationContext
    private val pool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val resources = mapOf(
        SoundEffect.BUS_HORN to R.raw.sfx_bus_horn,
        SoundEffect.MOTO_HORN to R.raw.sfx_moto_horn,
        SoundEffect.VINTAGE_HORN to R.raw.sfx_vintage_horn,
        SoundEffect.TRAIN_HORN to R.raw.sfx_train_horn,
        SoundEffect.CRUISE_HORN to R.raw.sfx_cruise_horn,
        SoundEffect.METRO_CHIME to R.raw.sfx_metro_chime,
        SoundEffect.CROSSING_BELL to R.raw.sfx_crossing_bell,
        SoundEffect.MOTO_REV to R.raw.sfx_moto_rev,
        SoundEffect.DIESEL_IDLE to R.raw.sfx_diesel_idle,
        SoundEffect.V8_REV to R.raw.sfx_v8_rev,
        SoundEffect.RACE_SHIFT to R.raw.sfx_race_shift,
        SoundEffect.MORSE to R.raw.sfx_morse,
        SoundEffect.RADAR to R.raw.sfx_radar,
        SoundEffect.AIRPORT_CHIME to R.raw.sfx_airport_chime,
        SoundEffect.SCHOOL_BELL to R.raw.sfx_school_bell,
        SoundEffect.BICYCLE_BELL to R.raw.sfx_bicycle_bell,
        SoundEffect.ICE_CREAM to R.raw.sfx_ice_cream,
        SoundEffect.REVERSING_BEEP to R.raw.sfx_reversing_beep,
        SoundEffect.TRUCK_REVERSE to R.raw.sfx_truck_reverse,
        SoundEffect.CONSTRUCTION_ALARM to R.raw.sfx_construction_alarm,
        SoundEffect.AIR_RAID to R.raw.sfx_air_raid,
        SoundEffect.POLICE_PA to R.raw.sfx_police_pa,
        SoundEffect.PORSCHE_REV to R.raw.sfx_porsche_911,
        SoundEffect.FERRARI_REV to R.raw.sfx_ferrari_v8,
        SoundEffect.LAMBORGHINI_REV to R.raw.sfx_lamborghini_v10,
        SoundEffect.MCLAREN_REV to R.raw.sfx_mclaren,
        SoundEffect.TESLA_ACCEL to R.raw.sfx_tesla,
        SoundEffect.BMW_M3_REV to R.raw.sfx_bmw_m3,
        SoundEffect.AMG_REV to R.raw.sfx_amg_s63,
        SoundEffect.AUDI_R8_REV to R.raw.sfx_audi_r8,
        SoundEffect.CORVETTE_REV to R.raw.sfx_corvette,
        SoundEffect.MUSTANG_REV to R.raw.sfx_mustang,
        SoundEffect.AIRPORT_BOARDING to R.raw.sfx_airport_boarding,
        SoundEffect.AIRPORT_ANNOUNCE to R.raw.sfx_airport_announce,
        SoundEffect.AIRPORT_TRAIN to R.raw.sfx_airport_train,
        SoundEffect.METRO_ARRIVAL to R.raw.sfx_metro_arrival,
        SoundEffect.BUS_STOP to R.raw.sfx_bus_stop,
        SoundEffect.BUS_ANNOUNCE to R.raw.sfx_bus_announce,
        SoundEffect.STATION_CHIME to R.raw.sfx_rail_station,
        SoundEffect.RAIL_DEPART to R.raw.sfx_rail_depart,
        SoundEffect.RAIL_DOOR to R.raw.sfx_rail_door
    )

    private val soundIds = mutableMapOf<Int, SoundEffect>()
    private val loaded = mutableSetOf<Int>()
    private var pending: SoundEffect? = null
    private var playing: SoundEffect? = null
    private var streamId = 0
    private var volume = 0.85f
    private var lowPower = false

    private val gain: Float
        get() = volume * if (lowPower) 0.25f else 1f

    init {
        pool.setOnLoadCompleteListener { _, soundId, status ->
            val effect = soundIds[soundId]
            if (status == 0 && effect != null) {
                loaded += soundId
                if (pending == effect) {
                    pending = null
                    start(effect, soundId)
                }
            } else {
                Log.e(TAG, "sample load failed: $soundId status=$status")
            }
        }
        resources.forEach { (effect, resId) ->
            soundIds[pool.load(appContext, resId, 1)] = effect
        }
    }

    fun has(effect: SoundEffect): Boolean = resources.containsKey(effect)

    fun play(effect: SoundEffect): Boolean {
        if (!resources.containsKey(effect)) return false
        stop()
        pending = effect
        val soundId = soundIds.entries.firstOrNull { it.value == effect }?.key
        if (soundId != null && soundId in loaded) {
            pending = null
            start(effect, soundId)
        }
        return true
    }

    private fun start(effect: SoundEffect, soundId: Int) {
        val g = gain
        val id = pool.play(soundId, g, g, 1, 0, 1f)
        if (id != 0) {
            streamId = id
            playing = effect
            Log.i(TAG, "sample=${effect.name}")
        } else {
            Log.e(TAG, "sample play failed: ${effect.name}")
        }
    }

    fun stop() {
        val hadPlayback = streamId != 0 || pending != null
        if (streamId != 0) {
            pool.stop(streamId)
            streamId = 0
        }
        pending = null
        playing = null
        if (hadPlayback) Log.i(TAG, "sample-stop")
    }

    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        if (streamId != 0) {
            val g = gain
            pool.setVolume(streamId, g, g)
        }
    }

    fun setLowPower(on: Boolean) {
        lowPower = on
        if (streamId != 0) {
            val g = gain
            pool.setVolume(streamId, g, g)
        }
    }

    fun release() {
        stop()
        pool.release()
    }

    private companion object {
        const val TAG = "PhoneSiren"
    }
}
