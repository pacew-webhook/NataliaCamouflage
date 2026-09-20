package com.example.nataliacamo

import android.media.Image
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Production-ready detector pipeline interface.
 *
 * This implementation is a model-free visual transition detector. It extracts
 * ROI statistics from RGBA_8888 frames, learns a NORMAL baseline, and requires
 * several consecutive frames before changing state. It is intentionally not
 * described as a trained Natalia classifier.
 *
 * A trained TFLite/ONNX model can later implement the same Detector contract
 * without changing CaptureService.
 */
interface FrameDetector {
    fun process(image: Image): DetectionResult
    fun reset()
}

data class DetectionResult(
    val camouflage: Boolean,
    val confidence: Float,
    val brightness: Float,
    val variation: Float
)

class CamouflageDetector(
    private val onFramesRequired: Int = 4,
    private val offFramesRequired: Int = 6,
    private val roiLeft: Float = 0.08f,
    private val roiTop: Float = 0.08f,
    private val roiRight: Float = 0.92f,
    private val roiBottom: Float = 0.92f
) : FrameDetector {
    private var onCount = 0
    private var offCount = 0
    private var state = false
    private var baselineBrightness = Float.NaN
    private var baselineVariation = Float.NaN
    private var warmupFrames = 0

    override fun process(image: Image): DetectionResult {
        val stats = sample(image)
        if (warmupFrames < 20) {
            warmupFrames++
            baselineBrightness = if (baselineBrightness.isNaN()) stats.brightness
            else baselineBrightness * 0.9f + stats.brightness * 0.1f
            baselineVariation = if (baselineVariation.isNaN()) stats.variation
            else baselineVariation * 0.9f + stats.variation * 0.1f
            return DetectionResult(false, 0f, stats.brightness, stats.variation)
        }

        // Camouflage transitions usually produce a persistent visual change.
        // We combine brightness and local variation so a single dark frame does
        // not trigger the state machine.
        val db = abs(stats.brightness - baselineBrightness)
        val dv = abs(stats.variation - baselineVariation)
        val signal = max(db / 38f, dv / 24f).coerceIn(0f, 1f)
        val candidate = signal >= 0.58f

        if (!state) {
            if (candidate) {
                onCount++
                offCount = 0
                if (onCount >= onFramesRequired) state = true
            } else {
                onCount = 0
                // Slowly follow normal gameplay so lighting changes do not
                // permanently become the baseline.
                baselineBrightness = baselineBrightness * 0.985f + stats.brightness * 0.015f
                baselineVariation = baselineVariation * 0.985f + stats.variation * 0.015f
            }
        } else {
            if (!candidate) {
                offCount++
                onCount = 0
                if (offCount >= offFramesRequired) state = false
            } else {
                offCount = 0
            }
        }

        val confidence = if (state) signal else (1f - signal)
        return DetectionResult(state, confidence.coerceIn(0f, 1f), stats.brightness, stats.variation)
    }

    override fun reset() {
        onCount = 0
        offCount = 0
        state = false
        baselineBrightness = Float.NaN
        baselineVariation = Float.NaN
        warmupFrames = 0
    }

    private data class Stats(val brightness: Float, val variation: Float)

    private fun sample(image: Image): Stats {
        val plane = image.planes.firstOrNull() ?: return Stats(0f, 0f)
        val buffer = plane.buffer.duplicate()
        val width = image.width
        val height = image.height
        val left = (width * roiLeft).toInt().coerceIn(0, width - 1)
        val top = (height * roiTop).toInt().coerceIn(0, height - 1)
        val right = (width * roiRight).toInt().coerceIn(left + 1, width)
        val bottom = (height * roiBottom).toInt().coerceIn(top + 1, height)
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val stepX = max(1, (right - left) / 24)
        val stepY = max(1, (bottom - top) / 14)

        var count = 0
        var sum = 0.0
        var sumSq = 0.0
        var prev = -1
        var variationSum = 0.0
        var variationCount = 0

        fun rgbaLuma(x: Int, y: Int): Int {
            val index = y * rowStride + x * pixelStride
            if (index < 0 || index + 2 >= buffer.limit()) return 0
            val r = buffer.get(index).toInt() and 0xFF
            val g = buffer.get(index + 1).toInt() and 0xFF
            val b = buffer.get(index + 2).toInt() and 0xFF
            return (0.2126 * r + 0.7152 * g + 0.0722 * b).toInt()
        }

        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val l = rgbaLuma(x, y)
                sum += l
                sumSq += l.toDouble() * l
                count++
                if (prev >= 0) {
                    variationSum += abs(l - prev)
                    variationCount++
                }
                prev = l
                x += stepX
            }
            y += stepY
        }

        if (count == 0) return Stats(0f, 0f)
        val mean = sum / count
        val variance = max(0.0, sumSq / count - mean * mean)
        val std = sqrt(variance)
        val variation = if (variationCount == 0) 0f else (variationSum / variationCount).toFloat()
        return Stats(mean.toFloat(), (std * 0.6 + variation * 0.4).toFloat())
    }
}
