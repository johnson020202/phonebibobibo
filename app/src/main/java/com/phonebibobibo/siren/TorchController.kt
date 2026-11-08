package com.phonebibobibo.siren

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * Drives the rear flash LED with fixed tactical patterns. The pattern runner lives on
 * a dedicated HandlerThread so UI recomposition never jitters the strobe timing.
 */
class TorchController(context: Context) {

    private data class Step(val on: Boolean, val durationMs: Long)

    private val cameraManager = context.applicationContext
        .getSystemService(CameraManager::class.java)

    private val cameraId: String? = runCatching {
        val ids = cameraManager.cameraIdList
        val back = ids.firstOrNull { id ->
            val c = cameraManager.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        back ?: ids.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }.getOrNull()

    val available: Boolean get() = cameraId != null

    var onError: (() -> Unit)? = null

    private val thread = HandlerThread("phone-siren-torch").apply { start() }
    private val handler = Handler(thread.looper)

    private var steps: List<Step>? = null
    private var stepIndex = 0

    private val runner = object : Runnable {
        override fun run() {
            val id = cameraId ?: return
            val pattern = steps ?: return
            stepIndex = (stepIndex + 1) % pattern.size
            val step = pattern[stepIndex]
            if (!setTorch(id, step.on)) {
                stop()
                onError?.invoke()
                return
            }
            handler.postDelayed(this, step.durationMs)
        }
    }

    fun start(mode: LightMode): Boolean {
        val id = cameraId ?: return false
        handler.removeCallbacks(runner)
        steps = null
        if (!setTorch(id, true)) return false
        if (mode == LightMode.STEADY) return true

        val pattern = stepsFor(mode)
        steps = pattern
        stepIndex = 0
        handler.postDelayed(runner, pattern[0].durationMs)
        Log.i(TAG, "torch pattern=${mode.name}")
        return true
    }

    fun stop() {
        handler.removeCallbacks(runner)
        steps = null
        cameraId?.let { setTorch(it, false) }
    }

    fun release() {
        stop()
        thread.quitSafely()
    }

    private fun setTorch(id: String, on: Boolean): Boolean = try {
        cameraManager.setTorchMode(id, on)
        true
    } catch (e: Exception) {
        Log.w(TAG, "setTorchMode($on) failed: ${e.message}")
        false
    }

    private fun stepsFor(mode: LightMode): List<Step> = when (mode) {
        LightMode.AUTO -> listOf(
            Step(true, 80), Step(false, 120),
            Step(true, 80), Step(false, 950)
        )
        LightMode.MODE_1 -> listOf(
            Step(true, 70), Step(false, 120),
            Step(true, 70), Step(false, 1250)
        )
        LightMode.MODE_2 -> listOf(
            Step(true, 50), Step(false, 55),
            Step(true, 50), Step(false, 55),
            Step(true, 50), Step(false, 430)
        )
        LightMode.MODE_3 -> buildList {
            repeat(6) {
                add(Step(true, 55))
                add(Step(false, 55))
            }
            add(Step(false, 620))
            add(Step(true, 240))
            add(Step(false, 240))
            add(Step(true, 240))
            add(Step(false, 1000))
        }
        LightMode.STEADY -> listOf(Step(true, 1_000_000))
    }

    private companion object {
        const val TAG = "PhoneSiren"
    }
}
