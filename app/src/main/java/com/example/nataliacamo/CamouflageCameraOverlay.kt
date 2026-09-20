package com.example.nataliacamo

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Small camera overlay used by the live camouflage effect.
 * The camera stays visible normally; when camouflage is active, the person's
 * segmented area is covered by a lightweight camouflage pattern while the
 * background remains visible.
 */
class CamouflageCameraOverlay(context: Context) : android.widget.FrameLayout(context) {
    private val preview = PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }
    private val maskView = CamouflageMaskView(context)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var segmenter: Segmenter? = null
    private var analyzing = false

    init {
        preview.scaleX = -1f
        maskView.scaleX = -1f
        addView(preview, LayoutParams(-1, -1))
        addView(maskView, LayoutParams(-1, -1))
        maskView.setWillNotDraw(false)
    }

    fun setCamouflage(active: Boolean) {
        maskView.setActive(active)
    }

    fun start() {
        if (provider != null) return
        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
            .enableRawSizeMask()
            .build()
        segmenter = Segmentation.getClient(options)

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val selector = CameraSelector.DEFAULT_FRONT_CAMERA
                val previewUseCase = Preview.Builder().build().also {
                    it.setSurfaceProvider(preview.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(android.util.Size(480, 480))
                    .build()
                analysis.setAnalyzer(executor) { proxy ->
                    val media = proxy.image
                    if (media == null || analyzing) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    analyzing = true
                    try {
                        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                        val task = segmenter?.process(image)
                        if (task == null) {
                            analyzing = false
                            proxy.close()
                            return@setAnalyzer
                        }
                        task.addOnSuccessListener(executor) { mask ->
                            maskView.setMask(mask)
                        }.addOnCompleteListener(executor) {
                            analyzing = false
                            proxy.close()
                        }
                    } catch (_: Throwable) {
                        analyzing = false
                        proxy.close()
                    }
                }
                p.unbindAll()
                p.bindToLifecycle(context as androidx.lifecycle.LifecycleOwner, selector, previewUseCase, analysis)
            } catch (_: Throwable) {
                // Camera is optional; the game detector continues to work if camera setup fails.
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        try { provider?.unbindAll() } catch (_: Throwable) {}
        provider = null
        try { segmenter?.close() } catch (_: Throwable) {}
        segmenter = null
        executor.shutdownNow()
        maskView.clearMask()
    }
}

private class CamouflageMaskView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    // ML Kit delivers masks from the analyzer executor. Never recycle the
    // bitmap currently being drawn from that background thread. Instead, queue
    // the newest bitmap and swap/recycle it on the View's UI thread in onDraw.
    @Volatile private var pendingMask: Bitmap? = null
    private var mask: Bitmap? = null
    private var active = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val patternPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pattern = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)

    init {
        val c = Canvas(pattern)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val colors = intArrayOf(0xAA263238.toInt(), 0xAA4E342E.toInt(), 0xAA33691E.toInt(), 0xAA827717.toInt())
        val r = java.util.Random(42)
        repeat(28) {
            patternPaint.color = colors[r.nextInt(colors.size)]
            val left = r.nextInt(96).toFloat()
            val top = r.nextInt(96).toFloat()
            val right = (left + 20 + r.nextInt(45)).coerceAtMost(120f)
            val bottom = (top + 20 + r.nextInt(45)).coerceAtMost(120f)
            c.drawOval(left, top, right, bottom, patternPaint)
        }
    }

    fun setActive(v: Boolean) {
        active = v
        invalidate()
    }

    fun setMask(segmentation: SegmentationMask) {
        val w = segmentation.width
        val h = segmentation.height
        if (w <= 0 || h <= 0) return
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        val buffer: ByteBuffer = segmentation.buffer
        buffer.rewind()
        for (i in pixels.indices) {
            val a = (buffer.getFloat().coerceIn(0f, 1f) * 255f).toInt()
            pixels[i] = (a shl 24)
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        val previousPending = pendingMask
        pendingMask = out
        previousPending?.recycle()
        postInvalidateOnAnimation()
    }

    fun clearMask() {
        post {
            pendingMask?.recycle()
            pendingMask = null
            mask?.recycle()
            mask = null
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        pendingMask?.let { next ->
            pendingMask = null
            val old = mask
            mask = next
            old?.recycle()
        }
        if (!active) return
        val m = mask ?: return
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        val dst = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val shader = BitmapShader(pattern, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        patternPaint.shader = shader
        patternPaint.alpha = 235
        canvas.drawRect(dst, patternPaint)
        patternPaint.shader = null
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        canvas.drawBitmap(m, null, dst, paint)
        paint.xfermode = null
        canvas.restoreToCount(layer)
    }
}
