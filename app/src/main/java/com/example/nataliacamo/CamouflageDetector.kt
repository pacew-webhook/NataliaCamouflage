package com.example.nataliacamo

import android.content.Context
import android.graphics.ImageFormat
import android.media.Image
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

/** Result returned by the on-device classifier. */
data class Detection(
    val camouflage: Boolean,
    val confidence: Float,
    val label: String,
    val roi: DetectorSettings,
)

/**
 * V2 detector.
 *
 * The important change from V1 is that the classifier no longer shrinks the
 * entire game screen to 224x224. It crops a configurable ROI first, then
 * resizes that ROI to the model input. The detector also handles FLOAT32 and
 * UINT8 models and converts logits to probabilities when necessary.
 */
class CamouflageDetector(context: Context) : AutoCloseable {
    companion object {
        private const val MODEL = "natalia_camouflage.tflite"
        private const val DEFAULT_SIZE = 224
    }

    private val interpreter: Interpreter
    private val labels: List<String>
    private var settings: DetectorSettings = DetectorSettings.load(context)
    private val inputWidth: Int
    private val inputHeight: Int
    private val inputChannels: Int
    private val inputType: DataType
    private val outputSize: Int
    private val floatOutput: Array<FloatArray>
    private val byteOutput: Array<ByteArray>?

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

        val outputTensor = interpreter.getOutputTensor(0)
        val outputShape = outputTensor.shape()
        outputSize = outputShape.lastOrNull()?.coerceAtLeast(1) ?: 2
        floatOutput = Array(1) { FloatArray(outputSize) }
        byteOutput = if (outputTensor.dataType() == DataType.UINT8 || outputTensor.dataType() == DataType.INT8) Array(1) { ByteArray(outputSize) } else null

        labels = runCatching {
            context.assets.open("labels.txt").bufferedReader().readLines()
                .map { it.trim() }.filter { it.isNotEmpty() }
        }.getOrDefault(listOf("camouflage", "normal"))
    }

    @Synchronized
    fun updateSettings(newSettings: DetectorSettings) {
        settings = newSettings.normalized()
    }

    fun currentSettings(): DetectorSettings = settings

    fun process(image: Image): Detection {
        val plane = image.planes.firstOrNull() ?: return Detection(false, 0f, "normal", settings)
        if (image.format != ImageFormat.RGBA_8888 && image.format != ImageFormat.PRIVATE) {
            // MediaProjection uses RGBA_8888 in this project. PRIVATE is kept
            // for device compatibility, but an inaccessible plane will simply
            // fail safely below.
        }

        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return Detection(false, 0f, "normal", settings)

        val buffer = plane.buffer.duplicate()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride < 3 || buffer.limit() <= 0) {
            return Detection(false, 0f, "normal", settings)
        }

        val s = settings.normalized()
        val left = if (s.roiEnabled) (width * s.left / 1000f).toInt() else 0
        val top = if (s.roiEnabled) (height * s.top / 1000f).toInt() else 0
        val roiWidth = if (s.roiEnabled) (width * s.width / 1000f).toInt().coerceAtLeast(1) else width
        val roiHeight = if (s.roiEnabled) (height * s.height / 1000f).toInt().coerceAtLeast(1) else height
        val cropW = roiWidth.coerceAtMost(width - left).coerceAtLeast(1)
        val cropH = roiHeight.coerceAtMost(height - top).coerceAtLeast(1)

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
                putPixel(r, g, b)
            }
        }

        runInference()
        val scores = readScores()
        val probabilities = toProbabilities(scores)
        val index = probabilities.indices.maxByOrNull { probabilities[it] } ?: 0
        val confidence = probabilities.getOrElse(index) { 0f }.coerceIn(0f, 1f)
        val label = labels.getOrNull(index)?.lowercase()?.trim() ?: "normal"
        val isCamoLabel = label.contains("camouflage") || label.contains("camo")
        val camouflage = isCamoLabel && confidence >= s.threshold / 100f
        return Detection(camouflage, confidence, label, s)
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

    private fun putPixel(r: Int, g: Int, b: Int) {
        when (inputType) {
            DataType.FLOAT32 -> {
                // V1 used [-1, 1], so keep that convention for the existing model.
                activeInput.putFloat(r / 127.5f - 1f)
                if (inputChannels > 1) activeInput.putFloat(g / 127.5f - 1f)
                if (inputChannels > 2) activeInput.putFloat(b / 127.5f - 1f)
                if (inputChannels > 3) activeInput.putFloat(1f)
            }
            DataType.UINT8, DataType.INT8 -> {
                val q = interpreter.getInputTensor(0).quantizationParams()
                val scale = q.scale.takeIf { it != 0f } ?: (1f / 255f)
                val zero = q.zeroPoint
                val minQ = if (inputType == DataType.INT8) -128 else 0
                val maxQ = if (inputType == DataType.INT8) 127 else 255
                fun quant(v: Int): Int = (v / 255f / scale + zero).toInt().coerceIn(minQ, maxQ)
                activeInput.put(quant(r).toByte())
                if (inputChannels > 1) activeInput.put(quant(g).toByte())
                if (inputChannels > 2) activeInput.put(quant(b).toByte())
                if (inputChannels > 3) activeInput.put(quant(255).toByte())
            }
            else -> {
                activeInput.putFloat(r / 127.5f - 1f)
                if (inputChannels > 1) activeInput.putFloat(g / 127.5f - 1f)
                if (inputChannels > 2) activeInput.putFloat(b / 127.5f - 1f)
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
