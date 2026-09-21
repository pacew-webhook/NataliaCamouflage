package com.example.nataliacamo

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.util.concurrent.atomic.AtomicBoolean

/** Foreground service: screen capture -> ROI classifier -> camera overlay. */
class CaptureService : Service(), LifecycleOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: android.hardware.display.VirtualDisplay? = null
    private var overlay: TextView? = null
    private var overlayContainer: FrameLayout? = null
    private var cameraOverlay: CamouflageCameraOverlay? = null
    private var roiOverlay: RoiOverlayView? = null
    private var roiParams: WindowManager.LayoutParams? = null
    private var wm: WindowManager? = null
    private var detector: CamouflageDetector? = null
    private val camo = AtomicBoolean(false)
    private val processing = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var onCount = 0
    private var offCount = 0
    private var lastConfidence = 0f
    private var lastLabel = "normal"
    private var lastInferenceAt = 0L

    companion object {
        const val START = "START"
        const val STOP = "STOP"
        const val CODE = "code"
        const val DATA = "data"
        @Volatile var running = false
            private set
        @Volatile var camouflage = false
            private set
        @Volatile var confidence = 0f
            private set
        @Volatile var label = "normal"
            private set

        private var instance: CaptureService? = null

        fun demo(v: Boolean) { instance?.setCamo(v) }

        fun updateSettings(context: Context, settings: DetectorSettings) {
            settings.save(context)
            instance?.applySettings(settings)
        }

        fun setRoiEditor(enabled: Boolean) { instance?.setRoiEditor(enabled) }
        fun setShowRoi(show: Boolean) { instance?.setShowRoi(show) }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                startCapture(intent)
            }
            STOP -> stopAll()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        if (running) return
        val code = intent.getIntExtra(CODE, Activity.RESULT_CANCELED)
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(DATA)
        }
        if (code != Activity.RESULT_OK || data == null) {
            stopSelf()
            return
        }

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
            } else startForeground(7, notification())
        } catch (_: Throwable) {
            stopSelf()
            return
        }

        val activeDetector = try {
            detector ?: CamouflageDetector(applicationContext).also { detector = it }
        } catch (_: Throwable) {
            stopSelf()
            return
        }
        activeDetector.updateSettings(DetectorSettings.load(this))
        resetHysteresis()

        val manager = getSystemService(MediaProjectionManager::class.java)
        projection = try { manager.getMediaProjection(code, data) } catch (_: Throwable) { null }
        if (projection == null) {
            stopSelf()
            return
        }
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopCapture() }
        }, Handler(Looper.getMainLooper()))

        val dm = resources.displayMetrics
        val maxDimension = 1280
        val scale = minOf(1f, maxDimension.toFloat() / maxOf(dm.widthPixels, dm.heightPixels).toFloat())
        val captureWidth = (dm.widthPixels * scale).toInt().coerceAtLeast(320)
        val captureHeight = (dm.heightPixels * scale).toInt().coerceAtLeast(320)

        reader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
        captureThread = HandlerThread("NataliaCaptureV2").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)
        reader?.setOnImageAvailableListener({ r ->
            val image = try { r.acquireLatestImage() } catch (_: Throwable) { null }
            if (image == null) return@setOnImageAvailableListener
            if (!processing.compareAndSet(false, true)) {
                image.close()
                return@setOnImageAvailableListener
            }
            try {
                val now = SystemClock.elapsedRealtime()
                if (now - lastInferenceAt < 80L) return@setOnImageAvailableListener
                lastInferenceAt = now
                val d = activeDetector.process(image)
                lastConfidence = d.confidence
                lastLabel = d.label
                confidence = d.confidence
                label = d.label
                updateStableState(d)
                Handler(Looper.getMainLooper()).post { updateStatusText() }
            } catch (_: Throwable) {
                // A malformed frame never terminates the service.
            } finally {
                try { image.close() } catch (_: Throwable) {}
                processing.set(false)
            }
        }, captureHandler)

        display = projection?.createVirtualDisplay(
            "NataliaCamouflageV2",
            captureWidth,
            captureHeight,
            dm.densityDpi,
            0,
            reader!!.surface,
            null,
            captureHandler
        )

        makeOverlays()
        running = true
        updateStatusText()
    }

    private fun updateStableState(d: Detection) {
        val s = d.roi
        if (!camouflage) {
            offCount = 0
            onCount = if (d.camouflage) onCount + 1 else 0
            if (onCount >= s.onFrames) setCamo(true)
        } else {
            onCount = 0
            offCount = if (!d.camouflage) offCount + 1 else 0
            if (offCount >= s.offFrames) setCamo(false)
        }
    }

    private fun makeOverlays() {
        if (overlayContainer != null || !Settings.canDrawOverlays(this)) return
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val root = FrameLayout(this)
        val camera = CamouflageCameraOverlay(this)
        cameraOverlay = camera
        root.addView(camera, FrameLayout.LayoutParams(-1, -1))

        val labelView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            setPadding(12, 8, 12, 8)
            text = "CAMOUFLAGE: OFF"
        }
        root.addView(labelView, FrameLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.TOP or Gravity.START
        })
        overlay = labelView
        overlayContainer = root

        val screenW = resources.displayMetrics.widthPixels
        val cameraW = (screenW * 0.30f).toInt().coerceAtLeast(300)
        val cameraH = (cameraW * 0.75f).toInt()
        val cameraParams = WindowManager.LayoutParams(
            cameraW,
            cameraH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (screenW * 0.012f).toInt()
            y = (resources.displayMetrics.heightPixels * 0.09f).toInt()
        }

        try {
            wm?.addView(root, cameraParams)
            camera.start()
            makeRoiOverlay()
        } catch (_: Throwable) {
            cameraOverlay?.stop()
            cameraOverlay = null
            overlayContainer = null
            overlay = null
        }
    }

    private fun makeRoiOverlay() {
        if (roiOverlay != null || wm == null) return
        val view = RoiOverlayView(this)
        view.setSettings(DetectorSettings.load(this))
        view.setOnChanged { s ->
            val normalized = s.normalized()
            normalized.save(this)
            detector?.updateSettings(normalized)
            view.setSettings(normalized)
        }
        roiOverlay = view
        val params = WindowManager.LayoutParams(
            -1,
            -1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        roiParams = params
        try { wm?.addView(view, params) } catch (_: Throwable) { roiOverlay = null }
    }

    private fun updateStatusText() {
        val state = if (camouflage) "ON" else "OFF"
        overlay?.text = String.format("CAMOUFLAGE: %s  •  %.0f%%", state, lastConfidence * 100f)
        roiOverlay?.setSettings(DetectorSettings.load(this))
    }

    private fun setCamo(v: Boolean) {
        camo.set(v)
        camouflage = v
        cameraOverlay?.setCamouflage(v)
    }

    private fun applySettings(settings: DetectorSettings) {
        val s = settings.normalized()
        detector?.updateSettings(s)
        roiOverlay?.setSettings(s)
        updateStatusText()
    }

    private fun setShowRoi(show: Boolean) {
        val s = DetectorSettings.load(this).copy(showRoi = show)
        s.save(this)
        roiOverlay?.setSettings(s)
    }

    private fun setRoiEditor(enabled: Boolean) {
        roiOverlay?.setEditMode(enabled)
        roiParams?.let { p ->
            p.flags = if (enabled) {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            } else {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            try { wm?.updateViewLayout(roiOverlay, p) } catch (_: Throwable) {}
        }
    }

    private fun resetHysteresis() {
        onCount = 0
        offCount = 0
        setCamo(false)
        confidence = 0f
        label = "normal"
    }

    private fun stopCapture() {
        if (!stopping.compareAndSet(false, true)) return
        try { lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP) } catch (_: Throwable) {}
        try { display?.release() } catch (_: Throwable) {}
        display = null
        try { reader?.close() } catch (_: Throwable) {}
        reader = null
        try { projection?.stop() } catch (_: Throwable) {}
        projection = null
        processing.set(false)
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        cameraOverlay?.stop()
        cameraOverlay = null
        overlayContainer?.let { try { wm?.removeView(it) } catch (_: Throwable) {} }
        overlayContainer = null
        overlay = null
        roiOverlay?.let { try { wm?.removeView(it) } catch (_: Throwable) {} }
        roiOverlay = null
        roiParams = null
        resetHysteresis()
        lastInferenceAt = 0L
        running = false
        stopping.set(false)
    }

    private fun stopAll() {
        stopCapture()
        stopSelf()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("final", "Natalia Camouflage V2", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification() = Notification.Builder(this, "final")
        .setContentTitle("Natalia Camouflage V2")
        .setContentText("ROI detector aktif")
        .setSmallIcon(android.R.drawable.ic_menu_view)
        .setOngoing(true)
        .build()

    override fun onDestroy() {
        stopCapture()
        try { detector?.close() } catch (_: Throwable) {}
        detector = null
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
