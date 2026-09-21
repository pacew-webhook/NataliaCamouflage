package com.example.nataliacamo

import android.content.Context
import android.media.Image
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

/** Result returned by the on-device classifier. */
data class Detection(
    val camouflage: Boolean,
    /** Keyakinan kelas teratas. */
    val confidence: Float,
    /** Label kelas teratas. */
    val label: String,
    val roi: DetectorSettings,
    /** Probabilitas kelas "camouflage" (ini yang dibandingkan dengan threshold). */
    val camoProb: Float = 0f,
)

/**
 * V3 detector.
 *
 * Perubahan dari V2:
 * - crop persegi (tidak memeras ROI) sebelum di-resize ke input model
 * - mode normalisasi input bisa dipilih (model FLOAT32)
 * - keputusan ON = probabilitas kelas camouflage >= threshold
 * - indeks kelas camouflage bisa dipaksa jika urutan labels salah
 */
class CamouflageDetector(context: Context) : AutoCloseable {
    companion object {
        private const val TAG = "NataliaCamo"
        private const val MODEL = "natalia_camouflage.tflite"
        private const val DEFAULT_SIZE = 224
    }

    private val interpreter: Interpreter
    private val labels: List<String>
    @Volatile private var settings: DetectorSettings = DetectorSettings.load(context)
    private val inputWidth: Int
    private val inputHeight: Int
    private val inputChannels: Int
    private val inputType: DataType
    private val inQScale: Float
    private val inQZero: Int
    private val outputSize: Int
    private val floatOutput: Array<FloatArray>
    private val byteOutput: Array<ByteArray>?
    private var lastLog = 0L

    init {
        val model = context.assets.open(MODEL).use { it.readBytes() }
        val modelBuffer = ByteBuffer.allocateDirect(model.size).order(ByteOrder.nativeOrder()).apply {
            put(model)
            rewind()
        }
        interpreter = Interpreter(modelBuffer, Interpreter.Options().setNumThreads(2))

        val inputTensor = interpreter.getInputTensor(0)
        val shape = inputTensor.shape()
        inputHeight = shape.getOrNull(1) ?: DEFAULT_SIZE
        inputWidth = shape.getOrNull(2) ?: DEFAULT_SIZE
        inputChannels = shape.getOrNull(3) ?: 3
        inputType = inputTensor.dataType()
        val iq = inputTensor.quantizationParams()
        inQScale = iq.scale.takeIf { it != 0f } ?: (1f / 255f)
        inQZero = iq.zeroPoint

        val outputTensor = interpreter.getOutputTensor(0)
        val outputShape = outputTensor.shape()
        outputSize = outputShape.lastOrNull()?.coerceAtLeast(1) ?: 2
        floatOutput = Array(1) { FloatArray(outputSize) }
        byteOutput = if (outputTensor.dataType() == DataType.UINT8 || outputTensor.dataType() == DataType.INT8) Array(1) { ByteArray(outputSize) } else null

        labels = runCatching {
            context.assets.open("labels.txt").bufferedReader().readLines()
                .map { it.trim() }.filter { it.isNotEmpty() }
        }.getOrDefault(listOf("camouflage", "normal"))

        Log.i(TAG, "model input=${shape.toList()} type=$inputType output=${outputShape.toList()} labels=$labels")
    }

    @Synchronized
    fun updateSettings(newSettings: DetectorSettings) {
        settings = newSettings.normalized()
    }

    fun currentSettings(): DetectorSettings = settings

    private fun camoIndex(s: DetectorSettings): Int {
        if (s.camoIndex in 0 until outputSize) return s.camoIndex
        val byLabel = labels.indexOfFirst { it.lowercase().contains("camo") }
        return if (byLabel in 0 until outputSize) byLabel else 0
    }

    fun process(image: Image): Detection {
        val s = settings.normalized()
        val plane = image.planes.firstOrNull() ?: return Detection(false, 0f, "normal", s)

        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return Detection(false, 0f, "normal", s)

        val buffer = plane.buffer.duplicate()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride < 3 || buffer.limit() <= 0) {
            return Detection(false, 0f, "normal", s)
        }

        var left = if (s.roiEnabled) (width * s.left / 1000f).toInt() else 0
        var top = if (s.roiEnabled) (height * s.top / 1000f).toInt() else 0
        val roiWidth = if (s.roiEnabled) (width * s.width / 1000f).toInt().coerceAtLeast(1) else width
        val roiHeight = if (s.roiEnabled) (height * s.height / 1000f).toInt().coerceAtLeast(1) else height
        var cropW = roiWidth.coerceAtMost(width - left).coerceAtLeast(1)
        var cropH = roiHeight.coerceAtMost(height - top).coerceAtLeast(1)

        if (s.squareCrop) {
            // Ambil persegi di tengah ROI supaya gambar tidak terdistorsi.
            val side = minOf(cropW, cropH)
            left += (cropW - side) / 2
            top += (cropH - side) / 2
            cropW = side
            cropH = side
        }

        inputBufferFor(inputType).clear()
        for (y in 0 until inputHeight) {
            val srcY = top + ((y.toLong() * cropH) / inputHeight).toInt().coerceIn(0, cropH - 1)
            val rowStart = srcY.toLong() * rowStride
            for (x in 0 until inputWidth) {
                val srcX = left + ((x.toLong() * cropW) / inputWidth).toInt().coerceIn(0, cropW - 1)
                val offset = (rowStart + srcX.toLong() * pixelStride).toInt()
                val r = safeByte(buffer, offset)
                val g = safeByte(buffer, offset + 1)
                val b = safeByte(buffer, offset + 2)
                putPixel(r, g, b, s.norm)
            }
        }

        runInference()
        val scores = readScores()
        val probabilities = toProbabilities(scores)
        val index = probabilities.indices.maxByOrNull { probabilities[it] } ?: 0
        val confidence = probabilities.getOrElse(index) { 0f }.coerceIn(0f, 1f)
        val label = labels.getOrNull(index)?.lowercase()?.trim() ?: "normal"
        val camoProb = probabilities.getOrElse(camoIndex(s)) { 0f }.coerceIn(0f, 1f)
        val camouflage = camoProb >= s.threshold / 100f

        val now = System.currentTimeMillis()
        if (now - lastLog > 1000L) {
            lastLog = now
            Log.d(TAG, "scores=${scores.toList()} probs=${probabilities.toList()} camoProb=$camoProb norm=${s.norm} crop=${cropW}x$cropH@$left,$top frame=${width}x$height")
        }
        return Detection(camouflage, confidence, label, s, camoProb)
    }

    private fun safeByte(buffer: ByteBuffer, index: Int): Int {
        return if (index >= 0 && index < buffer.limit()) buffer.get(index).toInt() and 0xff else 0
    }

    private var activeInput: ByteBuffer = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())

    private fun inputBufferFor(type: DataType): ByteBuffer {
        val bytesPerValue = when (type) {
            DataType.FLOAT32 -> 4
            DataType.UINT8, DataType.INT8 -> 1
            else -> 4
        }
        val needed = inputWidth * inputHeight * inputChannels * bytesPerValue
        if (activeInput.capacity() < needed) {
            activeInput = ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder())
        }
        return activeInput
    }

    private fun conv(v: Int, norm: Int): Float = when (norm) {
        1 -> v / 255f
        2 -> v.toFloat()
        else -> v / 127.5f - 1f
    }

    private fun putPixel(r: Int, g: Int, b: Int, norm: Int) {
        when (inputType) {
            DataType.UINT8, DataType.INT8 -> {
                val minQ = if (inputType == DataType.INT8) -128 else 0
                val maxQ = if (inputType == DataType.INT8) 127 else 255
                fun quant(v: Int): Int = (v / 255f / inQScale + inQZero).toInt().coerceIn(minQ, maxQ)
                activeInput.put(quant(r).toByte())
                if (inputChannels > 1) activeInput.put(quant(g).toByte())
                if (inputChannels > 2) activeInput.put(quant(b).toByte())
                if (inputChannels > 3) activeInput.put(quant(255).toByte())
            }
            else -> {
                activeInput.putFloat(conv(r, norm))
                if (inputChannels > 1) activeInput.putFloat(conv(g, norm))
                if (inputChannels > 2) activeInput.putFloat(conv(b, norm))
                if (inputChannels > 3) activeInput.putFloat(conv(255, norm))
            }
        }
    }

    private fun runInference() {
        activeInput.rewind()
        if (byteOutput != null) {
            interpreter.run(activeInput, byteOutput[0])
        } else {
            interpreter.run(activeInput, floatOutput)
        }
    }

    private fun readScores(): FloatArray {
        if (byteOutput != null) {
            val q = interpreter.getOutputTensor(0).quantizationParams()
            val scale = q.scale.takeIf { it != 0f } ?: (1f / 255f)
            val zero = q.zeroPoint
            val type = interpreter.getOutputTensor(0).dataType()
            return FloatArray(outputSize) { i ->
                val raw = if (type == DataType.INT8) byteOutput[0][i].toInt() else (byteOutput[0][i].toInt() and 0xff)
                (raw - zero) * scale
            }
        }
        return floatOutput[0].clone()
    }

    private fun toProbabilities(scores: FloatArray): FloatArray {
        if (scores.isEmpty()) return floatArrayOf(1f, 0f)
        val sum = scores.sum()
        val alreadyProbabilities = scores.all { it in 0f..1f } && kotlin.math.abs(sum - 1f) < 0.05f
        if (alreadyProbabilities) return scores.map { it.coerceIn(0f, 1f) }.toFloatArray()
        val max = scores.maxOrNull() ?: 0f
        val exps = FloatArray(scores.size) { exp((scores[it] - max).toDouble()).toFloat() }
        val denom = exps.sum().coerceAtLeast(1e-9f)
        return FloatArray(scores.size) { exps[it] / denom }
    }

    override fun close() = interpreter.close()
}
