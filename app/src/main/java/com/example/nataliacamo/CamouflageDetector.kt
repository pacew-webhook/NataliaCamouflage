package com.example.nataliacamo

import android.content.Context
import android.media.Image
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.Interpreter

/** Real on-device TFLite classifier for the trained Natalia dataset. */
data class Detection(val camouflage: Boolean, val confidence: Float)

class CamouflageDetector(context: Context) : AutoCloseable {
    companion object {
        private const val MODEL = "natalia_camouflage.tflite"
        private const val SIZE = 224
    }

    private val interpreter: Interpreter
    private val inputBuffer = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4)
        .order(ByteOrder.nativeOrder())
    private val output = Array(1) { FloatArray(2) }
    private val labels: List<String>

    init {
        val model = context.assets.open(MODEL).use { it.readBytes() }
        val modelBuffer = ByteBuffer.allocateDirect(model.size).order(ByteOrder.nativeOrder()).apply {
            put(model)
            rewind()
        }
        val options = Interpreter.Options().setNumThreads(2)
        interpreter = Interpreter(modelBuffer, options)
        labels = runCatching {
            context.assets.open("labels.txt").bufferedReader().readLines()
                .map { it.trim() }.filter { it.isNotEmpty() }
        }.getOrDefault(listOf("camouflage", "normal"))
    }

    /**
     * Converts the screen frame directly into the 224x224 model input.
     * This avoids allocating a full-resolution Bitmap for every screen frame,
     * which can otherwise cause crashes/OOM on phones with large displays.
     */
    fun process(image: Image): Detection {
        if (image.format != android.graphics.ImageFormat.PRIVATE && image.planes.isEmpty()) {
            return Detection(false, 0f)
        }
        val plane = image.planes.firstOrNull() ?: return Detection(false, 0f)
        val buffer = plane.buffer.duplicate()
        val width = image.width
        val height = image.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (width <= 0 || height <= 0 || pixelStride < 3) return Detection(false, 0f)

        inputBuffer.rewind()

        // Scale the complete screen to 224x224 using nearest-neighbour sampling.
        // This keeps memory use very low compared with creating a full-screen Bitmap.
        for (y in 0 until SIZE) {
            val srcY = ((y.toLong() * height) / SIZE).toInt().coerceIn(0, height - 1)
            val rowStart = srcY.toLong() * rowStride
            for (x in 0 until SIZE) {
                val srcX = ((x.toLong() * width) / SIZE).toInt().coerceIn(0, width - 1)
                val offset = (rowStart + srcX.toLong() * pixelStride).toInt()
                if (offset < 0 || offset + 2 >= buffer.limit()) {
                    inputBuffer.putFloat(-1f)
                    inputBuffer.putFloat(-1f)
                    inputBuffer.putFloat(-1f)
                    continue
                }
                val r = buffer.get(offset).toInt() and 0xff
                val g = buffer.get(offset + 1).toInt() and 0xff
                val b = buffer.get(offset + 2).toInt() and 0xff
                inputBuffer.putFloat((r / 127.5f) - 1f)
                inputBuffer.putFloat((g / 127.5f) - 1f)
                inputBuffer.putFloat((b / 127.5f) - 1f)
            }
        }

        interpreter.run(inputBuffer, output)

        val scores = output[0]
        val index = scores.indices.maxByOrNull { scores[it] } ?: 0
        val confidence = scores.getOrElse(index) { 0f }.coerceIn(0f, 1f)
        val label = labels.getOrNull(index)?.lowercase() ?: "normal"
        val camouflage = label.contains("camouflage")
        return Detection(camouflage, confidence)
    }

    fun reset() {
        inputBuffer.clear()
        output[0].fill(0f)
    }

    override fun close() {
        interpreter.close()
    }
}
