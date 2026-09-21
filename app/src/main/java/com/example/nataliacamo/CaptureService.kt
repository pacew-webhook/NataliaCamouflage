package com.example.nataliacamo

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.util.DisplayMetrics
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
    private var lastSizeCheck = 0L
    private var captureW = 0
    private var captureH = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    /** null = otomatis (AI), true/false = dipaksa lewat tombol TEST. */
    @Volatile private var manual: Boolean? = null

    companion object {
        private const val ROI_BASE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

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
        @Volatile var camoProb = 0f
            private set
        /** Pesan error terakhir (ditampilkan di panel), null jika tidak ada. */
        @Volatile var lastError: String? = null

        fun reportError(message: String) { lastError = message }
        @Volatile var manualMode: Boolean? = null
            private set
        @Volatile var label = "normal"
            private set

        private var instance: CaptureService? = null

        /** Paksa ON/OFF (bertahan sampai auto() dipanggil). */
        fun demo(v: Boolean) { instance?.setManual(v) }
        fun auto() { instance?.setManual(null) }

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
                // WAJIB: startForeground harus dipanggil untuk SETIAP startForegroundService(),
                // termasuk saat capture sudah jalan / gagal. Kalau tidak, aplikasi crash
                // (ForegroundServiceDidNotStartInTimeException).
                if (!ensureForeground()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                startCapture(intent)
            }
            STOP -> stopAll()
        }
        return START_NOT_STICKY
    }

    /** Jadikan service foreground. Tipe kamera hanya dipakai jika izin kamera sudah diberikan. */
    private fun ensureForeground(): Boolean {
        val hasCamera = checkSelfPermission(android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                var type = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                if (hasCamera) type = type or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                startForeground(7, notification(), type)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(7, notification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(7, notification())
            }
            return true
        } catch (_: Throwable) {
            // Cadangan: tanpa tipe kamera.
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(7, notification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                } else {
                    startForeground(7, notification())
                }
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    private fun startCapture(intent: Intent) {
        try {
            startCaptureInner(intent)
        } catch (t: Throwable) {
            fail("Start capture gagal", t)
        }
    }

    private fun fail(message: String, t: Throwable?) {
        lastError = message + (t?.let { " (${it::class.java.simpleName}: ${it.message})" } ?: "")
        try { stopCapture() } catch (_: Throwable) {}
        stopSelf()
    }

    private fun startCaptureInner(intent: Intent) {
        if (running) return
        lastError = null
        val code = intent.getIntExtra(CODE, Activity.RESULT_CANCELED)
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(DATA)
        }
        if (code != Activity.RESULT_OK || data == null) {
            fail("Izin screen capture ditolak", null)
            return
        }

        val activeDetector = try {
            detector ?: CamouflageDetector(applicationContext).also { detector = it }
        } catch (t: Throwable) {
            fail("Model AI gagal dimuat", t)
            return
        }
        activeDetector.updateSettings(DetectorSettings.load(this))
        resetHysteresis()

        val manager = getSystemService(MediaProjectionManager::class.java)
        projection = try { manager.getMediaProjection(code, data) } catch (_: Throwable) { null }
        if (projection == null) {
            fail("MediaProjection tidak tersedia", null)
            return
        }
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopCapture() }
        }, Handler(Looper.getMainLooper()))

        val (captureWidth, captureHeight) = captureSize()
        captureW = captureWidth
        captureH = captureHeight

        captureThread = HandlerThread("NataliaCaptureV3").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)
        reader = createReader(captureWidth, captureHeight)

        display = projection?.createVirtualDisplay(
            "NataliaCamouflageV3",
            captureWidth,
            captureHeight,
            resources.displayMetrics.densityDpi,
            0,
            reader!!.surface,
            null,
            captureHandler
        )

        makeOverlays()
        running = true
        updateStatusText()
    }

    /** Ukuran layar nyata saat ini (ikut rotasi), dikecilkan maks 1280 px. */
    private fun captureSize(): Pair<Int, Int> {
        val windowManager = getSystemService(WindowManager::class.java)
        val realW: Int
        val realH: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.maximumWindowMetrics.bounds
            realW = b.width()
            realH = b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(m)
            realW = m.widthPixels
            realH = m.heightPixels
        }
        val scale = minOf(1f, 1280f / maxOf(realW, realH).toFloat())
        return (realW * scale).toInt().coerceAtLeast(320) to (realH * scale).toInt().coerceAtLeast(320)
    }

    private fun createReader(w: Int, h: Int): ImageReader {
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ ir -> handleImage(ir) }, captureHandler)
        return r
    }

    private fun handleImage(r: ImageReader) {
        val image = try { r.acquireLatestImage() } catch (_: Throwable) { null }
        if (image == null) return
        val activeDetector = detector
        if (activeDetector == null || !processing.compareAndSet(false, true)) {
            try { image.close() } catch (_: Throwable) {}
            return
        }
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastInferenceAt < 80L) return
            lastInferenceAt = now
            if (now - lastSizeCheck > 1000L) {
                lastSizeCheck = now
                mainHandler.post { resizeCapture() }
            }
            val d = activeDetector.process(image)
            lastConfidence = d.confidence
            lastLabel = d.label
            confidence = d.confidence
            camoProb = d.camoProb
            label = d.label
            updateStableState(d)
            mainHandler.post { updateStatusText() }
        } catch (_: Throwable) {
            // A malformed frame never terminates the service.
        } finally {
            try { image.close() } catch (_: Throwable) {}
            processing.set(false)
        }
    }

    /** Dipanggil saat layar berputar: sesuaikan ukuran capture supaya crop ROI tetap benar. */
    private fun resizeCapture() {
        val disp = display ?: return
        val (w, h) = captureSize()
        if (w == captureW && h == captureH) return
        val oldReader = reader
        val newReader = createReader(w, h)
        try {
            disp.surface = newReader.surface
            disp.resize(w, h, resources.displayMetrics.densityDpi)
            reader = newReader
            captureW = w
            captureH = h
            oldReader?.setOnImageAvailableListener(null, null)
            if (oldReader != null) {
                mainHandler.postDelayed({ try { oldReader.close() } catch (_: Throwable) {} }, 1500L)
            }
        } catch (_: Throwable) {
            try { newReader.close() } catch (_: Throwable) {}
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (running) resizeCapture()
    }

    private fun setManual(v: Boolean?) {
        manual = v
        manualMode = v
        onCount = 0
        offCount = 0
        if (v != null) setCamo(v)
        updateStatusText()
    }

    private fun updateStableState(d: Detection) {
        if (manual != null) return
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
        } catch (t: Throwable) {
            lastError = "Overlay gagal dibuat (${t::class.java.simpleName}: ${t.message})"
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
            ROI_BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Overlay harus menutupi seluruh layar (termasuk area notch) supaya
            // koordinat kotak ROI sama dengan koordinat frame capture.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        roiParams = params
        try { wm?.addView(view, params) } catch (_: Throwable) { roiOverlay = null }
    }

    private fun updateStatusText() {
        val state = if (camouflage) "ON" else "OFF"
        val mode = if (manual != null) " (manual)" else ""
        overlay?.text = String.format("CAMOUFLAGE: %s%s  •  camo %.0f%%  •  %s", state, mode, camoProb * 100f, lastLabel)
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
                ROI_BASE_FLAGS
            } else {
                ROI_BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            try { wm?.updateViewLayout(roiOverlay, p) } catch (_: Throwable) {}
        }
    }

    private fun resetHysteresis() {
        onCount = 0
        offCount = 0
        manual = null
        manualMode = null
        setCamo(false)
        confidence = 0f
        camoProb = 0f
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
        captureW = 0
        captureH = 0
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
