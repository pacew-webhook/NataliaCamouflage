package com.example.nataliacamo

import android.content.Context
import android.graphics.Bitmap
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
        interpreter = Interpreter(ByteBuffer.allocateDirect(model.size).order(ByteOrder.nativeOrder()).apply {
            put(model)
            rewind()
        })
        labels = runCatching {
            context.assets.open("labels.txt").bufferedReader().readLines()
                .map { it.trim() }.filter { it.isNotEmpty() }
        }.getOrDefault(listOf("camouflage", "normal"))
    }

    fun process(image: Image): Detection {
        val bitmap = imageToBitmap(image) ?: return Detection(false, 0f)
        val resized = Bitmap.createScaledBitmap(bitmap, SIZE, SIZE, true)
        inputBuffer.rewind()
        val pixels = IntArray(SIZE * SIZE)
        resized.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        for (pixel in pixels) {
            // MobileNetV2 training preprocessing: RGB [0,255] -> [-1,1].
            inputBuffer.putFloat((((pixel shr 16) and 0xff) / 127.5f) - 1f)
            inputBuffer.putFloat((((pixel shr 8) and 0xff) / 127.5f) - 1f)
            inputBuffer.putFloat(((pixel and 0xff) / 127.5f) - 1f)
        }
        interpreter.run(inputBuffer, output)

        val scores = output[0]
        val index = scores.indices.maxByOrNull { scores[it] } ?: 0
        val confidence = scores.getOrElse(index) { 0f }.coerceIn(0f, 1f)
        val label = labels.getOrNull(index)?.lowercase() ?: "normal"
        val camouflage = label.contains("camouflage")
        return Detection(camouflage, confidence)
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        if (image.planes.size != 1) return null
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer.duplicate()
        val width = image.width
        val height = image.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride < 4 || width <= 0 || height <= 0) return null
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride
        val temp = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        temp.copyPixelsFromBuffer(buffer)
        return if (paddedWidth == width) temp else Bitmap.createBitmap(temp, 0, 0, width, height)
    }

    override fun close() {
        interpreter.close()
    }
}
