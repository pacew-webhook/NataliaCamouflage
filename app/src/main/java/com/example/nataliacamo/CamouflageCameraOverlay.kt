package com.example.nataliacamo

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Small live front-camera window with a segmented camouflage effect. */
class CamouflageCameraOverlay(context: Context) : FrameLayout(context) {
    private val preview = PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FILL_CENTER
        scaleX = -1f
    }
    private val maskView = CamouflageMaskView(context).apply {
        scaleX = -1f
    }
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var segmenter: Segmenter? = null
    private var analyzing = false

    init {
        addView(preview, LayoutParams(-1, -1))
        addView(maskView, LayoutParams(-1, -1))
        maskView.setWillNotDraw(false)
    }

    fun setCamouflage(active: Boolean) = maskView.setActive(active)

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
                    .setTargetResolution(android.util.Size(480, 360))
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
                        segmenter?.process(image)
                            ?.addOnSuccessListener(executor) { mask -> maskView.setMask(mask) }
                            ?.addOnCompleteListener(executor) {
                                analyzing = false
                                proxy.close()
                            }
                            ?: run {
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
                // Camera is optional. Screen detection can continue without it.
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
        val random = java.util.Random(42)
        repeat(30) {
            patternPaint.color = colors[random.nextInt(colors.size)]
            val left = random.nextInt(96).toFloat()
            val top = random.nextInt(96).toFloat()
            c.drawOval(left, top, left + 18 + random.nextInt(42), top + 18 + random.nextInt(42), patternPaint)
        }
    }

    fun setActive(v: Boolean) {
        active = v
        postInvalidateOnAnimation()
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
            val alpha = (buffer.getFloat().coerceIn(0f, 1f) * 255f).toInt()
            pixels[i] = alpha shl 24
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        val oldPending = pendingMask
        pendingMask = out
        oldPending?.recycle()
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
        val src = centerCropSource(m.width, m.height, width, height)
        val dst = RectF(0f, 0f, width.toFloat(), height.toFloat())
        patternPaint.shader = BitmapShader(pattern, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        patternPaint.alpha = 238
        canvas.drawRect(dst, patternPaint)
        patternPaint.shader = null
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        canvas.drawBitmap(m, src, dst, paint)
        paint.xfermode = null
        canvas.restoreToCount(layer)
    }

    private fun centerCropSource(sw: Int, sh: Int, dw: Int, dh: Int): Rect {
        val sourceAspect = sw.toFloat() / sh.toFloat()
        val destAspect = dw.toFloat() / dh.toFloat()
        return if (sourceAspect > destAspect) {
            val newW = (sh * destAspect).toInt().coerceAtLeast(1)
            val left = (sw - newW) / 2
            Rect(left, 0, left + newW, sh)
        } else {
            val newH = (sw / destAspect).toInt().coerceAtLeast(1)
            val top = (sh - newH) / 2
            Rect(0, top, sw, top + newH)
        }
    }
}
