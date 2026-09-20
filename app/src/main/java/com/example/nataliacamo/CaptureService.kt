package com.example.nataliacamo

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean

class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var overlay: TextView? = null
    private var wm: WindowManager? = null
    private val camo = AtomicBoolean(false)
    private val detector: FrameDetector = CamouflageDetector()

    companion object {
        const val ACTION_START = "com.example.nataliacamo.START"
        const val ACTION_STOP = "com.example.nataliacamo.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        @Volatile var isRunning = false; private set
        @Volatile var isCamouflageDetected = false; private set
        @Volatile var confidence = 0f; private set
        @Volatile var frameCount = 0L; private set
        @Volatile var fps = 0.0; private set
        @Volatile private var instance: CaptureService? = null
        fun setDemoCamouflage(on: Boolean) { instance?.setCamouflage(on, if (on) 1f else 0f) }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { stopCaptureOnly() }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(10, notification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture(intent)
            ACTION_STOP -> stopCaptureAndService()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        if (isRunning) return
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = intent.parcelableIntent(EXTRA_DATA)
        if (code != Activity.RESULT_OK || data == null) { stopSelf(); return }

        val manager = getSystemService(MediaProjectionManager::class.java)
        projection = manager.getMediaProjection(code, data)
        projection?.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        val metrics = resources.displayMetrics
        reader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
        var windowStart = System.nanoTime()
        var windowFrames = 0L

        reader?.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                frameCount++
                windowFrames++
                val now = System.nanoTime()
                val elapsed = now - windowStart
                if (elapsed >= 1_000_000_000L) {
                    fps = windowFrames * 1_000_000_000.0 / elapsed
                    windowFrames = 0
                    windowStart = now
                }
                val result = detector.process(image)
                confidence = result.confidence
                if (result.camouflage != isCamouflageDetected) {
                    setCamouflage(result.camouflage, result.confidence)
                }
            } finally { image.close() }
        }, Handler(Looper.getMainLooper()))

        virtualDisplay = projection?.createVirtualDisplay(
            "NataliaFinalDetector", metrics.widthPixels, metrics.heightPixels,
            metrics.densityDpi, 0, reader!!.surface, null, null
        )
        createOverlayIfNeeded()
        isRunning = true
    }

    private fun createOverlayIfNeeded() {
        if (overlay != null) return
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlay = TextView(this).apply {
            text = "CAMOUFLAGE"
            textSize = 24f
            setTextColor(0xffffffff.toInt())
            setBackgroundColor(0x66000000)
            setPadding(24, 16, 24, 16)
            alpha = 0f
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 120 }
        try { wm?.addView(overlay, params) } catch (_: Exception) {}
    }

    private fun setCamouflage(on: Boolean, score: Float) {
        if (camo.getAndSet(on) == on) return
        isCamouflageDetected = on
        confidence = score
        overlay?.animate()?.alpha(if (on) 1f else 0f)?.setDuration(180)?.start()
    }

    private fun stopCaptureOnly() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        try { projection?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        detector.reset()
        setCamouflage(false, 0f)
        frameCount = 0
        fps = 0.0
        confidence = 0f
        isRunning = false
    }

    private fun stopCaptureAndService() { stopCaptureOnly(); stopSelf() }
    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("capture", "Screen Capture", NotificationManager.IMPORTANCE_LOW)
        )
    }
    private fun notification(): Notification = Notification.Builder(this, "capture")
        .setContentTitle("Natalia Camouflage Final")
        .setContentText("Detector visual aktif")
        .setSmallIcon(android.R.drawable.ic_menu_view)
        .build()

    override fun onDestroy() {
        stopCaptureOnly()
        overlay?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        overlay = null; wm = null; instance = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null

    @Suppress("DEPRECATION")
    private fun Intent.parcelableIntent(key: String): Intent? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, Intent::class.java) else getParcelableExtra(key)
}
