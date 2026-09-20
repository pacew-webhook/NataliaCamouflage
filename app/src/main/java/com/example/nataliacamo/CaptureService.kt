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

    companion object {
        const val ACTION_START = "com.example.nataliacamo.START"
        const val ACTION_STOP = "com.example.nataliacamo.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        @Volatile var isRunning: Boolean = false
            private set
        @Volatile private var instance: CaptureService? = null
        fun setDemoCamouflage(on: Boolean) { instance?.setCamouflage(on) }
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
        reader?.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, Handler(Looper.getMainLooper()))
        virtualDisplay = projection?.createVirtualDisplay(
            "NataliaPrototype", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            0, reader!!.surface, null, null
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

    private fun setCamouflage(on: Boolean) {
        if (camo.getAndSet(on) == on) return
        overlay?.animate()?.alpha(if (on) 1f else 0f)?.setDuration(150)?.start()
    }

    private fun stopCaptureOnly() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        try { projection?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        setCamouflage(false)
        isRunning = false
    }

    private fun stopCaptureAndService() { stopCaptureOnly(); stopSelf() }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("capture", "Screen Capture", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(): Notification = Notification.Builder(this, "capture")
        .setContentTitle("Natalia Detector")
        .setContentText("Screen capture aktif")
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
    private fun Intent.parcelableIntent(key: String): Intent? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(key, Intent::class.java)
    } else getParcelableExtra(key)
}
