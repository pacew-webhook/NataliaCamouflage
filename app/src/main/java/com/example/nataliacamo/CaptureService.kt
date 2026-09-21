package com.example.nataliacamo

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.*
import android.widget.TextView
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.util.concurrent.atomic.AtomicBoolean

class CaptureService : Service(), LifecycleOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: android.hardware.display.VirtualDisplay? = null
    private var overlay: TextView? = null
    private var overlayContainer: FrameLayout? = null
    private var cameraOverlay: CamouflageCameraOverlay? = null
    private var wm: WindowManager? = null
    private var detector: CamouflageDetector? = null
    private val camo = AtomicBoolean(false)
    private val processing = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    companion object {
        const val START = "START"
        const val STOP = "STOP"
        const val CODE = "code"
        const val DATA = "data"
        @Volatile var running = false
            private set
        @Volatile var camouflage = false
            private set
        private var instance: CaptureService? = null
        fun demo(v: Boolean) { instance?.setCamo(v) }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        channel()
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        when (i?.action) {
            START -> {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                startCapture(i)
            }
            STOP -> stopAll()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(i: Intent) {
        if (running) return

        val code = i.getIntExtra(CODE, Activity.RESULT_CANCELED)
        val data = if (Build.VERSION.SDK_INT >= 33) {
            i.getParcelableExtra(DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            i.getParcelableExtra(DATA)
        }
        if (code != Activity.RESULT_OK || data == null) {
            stopSelf()
            return
        }

        // Android 14+ requires each foreground-service type used by the
        // service to be declared in the manifest and supplied to startForeground.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    7,
                    notification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    7,
                    notification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(7, notification())
            }
        } catch (_: SecurityException) {
            stopSelf()
            return
        } catch (_: IllegalArgumentException) {
            stopSelf()
            return
        }

        val activeDetector = try {
            detector ?: CamouflageDetector(applicationContext).also { detector = it }
        } catch (_: Throwable) {
            stopSelf()
            return
        }

        val m = getSystemService(MediaProjectionManager::class.java)
        projection = m.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopCapture() }
        }, Handler(Looper.getMainLooper()))

        val dm = resources.displayMetrics
        // Do not allocate full-resolution RGBA buffers for every screen frame.
        // The classifier consumes 224x224, so a capped capture resolution is
        // sufficient and dramatically reduces memory pressure on high-DPI phones.
        val maxDimension = 1280
        val scale = minOf(1f, maxDimension.toFloat() / maxOf(dm.widthPixels, dm.heightPixels).toFloat())
        val captureWidth = (dm.widthPixels * scale).toInt().coerceAtLeast(320)
        val captureHeight = (dm.heightPixels * scale).toInt().coerceAtLeast(320)

        reader = ImageReader.newInstance(
            captureWidth,
            captureHeight,
            PixelFormat.RGBA_8888,
            2
        )

        captureThread = HandlerThread("NataliaCapture").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)
        reader?.setOnImageAvailableListener({ r ->
            // Drop frames while inference is running. This prevents a backlog
            // of large Image objects and keeps the game/app responsive.
            if (!processing.compareAndSet(false, true)) return@setOnImageAvailableListener
            val image = try {
                r.acquireLatestImage()
            } catch (_: Exception) {
                null
            }
            if (image == null) {
                processing.set(false)
                return@setOnImageAvailableListener
            }

            try {
                val d = activeDetector.process(image)
                if (d.camouflage != camouflage) {
                    // Only the small UI update returns to the main thread.
                    Handler(Looper.getMainLooper()).post {
                        setCamo(d.camouflage)
                    }
                }
            } catch (_: Throwable) {
                // A bad frame must not bring down the foreground service.
            } finally {
                try { image.close() } catch (_: Exception) {}
                processing.set(false)
            }
        }, captureHandler)

        display = projection?.createVirtualDisplay(
            "NataliaCamouflage",
            captureWidth,
            captureHeight,
            dm.densityDpi,
            0,
            reader!!.surface,
            null,
            captureHandler
        )

        makeOverlay()
        running = true
    }

    private fun makeOverlay() {
        if (overlayContainer != null) return
        if (!android.provider.Settings.canDrawOverlays(this)) return

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val container = FrameLayout(this)
        val camera = CamouflageCameraOverlay(this)
        cameraOverlay = camera
        container.addView(camera, FrameLayout.LayoutParams(-1, -1))

        val label = TextView(this).apply {
            text = "CAMOUFLAGE: OFF"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(0x88000000.toInt())
            setPadding(12, 8, 12, 8)
        }
        val labelParams = FrameLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        container.addView(label, labelParams)
        overlay = label
        overlayContainer = container

        val p = WindowManager.LayoutParams(
            360,
            270,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 18
            y = 120
        }
        try {
            wm?.addView(container, p)
            camera.start()
        } catch (_: Throwable) {
            cameraOverlay?.stop()
            cameraOverlay = null
            overlayContainer = null
            overlay = null
        }
    }

    private fun setCamo(v: Boolean) {
        camo.set(v)
        camouflage = v
        cameraOverlay?.setCamouflage(v)
        overlay?.text = if (v) "CAMOUFLAGE: ON" else "CAMOUFLAGE: OFF"
    }

    private fun stopCapture() {
        if (!stopping.compareAndSet(false, true)) return
        try { lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP) } catch (_: Exception) {}
        try { display?.release() } catch (_: Exception) {}
        display = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        processing.set(false)
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        cameraOverlay?.stop()
        cameraOverlay = null
        overlayContainer?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        overlayContainer = null
        overlay = null
        setCamo(false)
        running = false
        stopping.set(false)
    }

    private fun stopAll() {
        stopCapture()
        stopSelf()
    }

    private fun channel() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel("final", "Natalia Final", NotificationManager.IMPORTANCE_LOW)
            )
    }

    private fun notification() = Notification.Builder(this, "final")
        .setContentTitle("Natalia Camouflage Final")
        .setContentText("Detector aktif")
        .setSmallIcon(android.R.drawable.ic_menu_view)
        .build()

    override fun onDestroy() {
        stopCapture()
        try { detector?.close() } catch (_: Exception) {}
        detector = null
        overlayContainer?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        overlayContainer = null
        overlay = null
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        instance = null
        super.onDestroy()
    }

    override fun onBind(i: Intent?) = null
}
