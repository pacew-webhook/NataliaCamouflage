package com.example.nataliacamo

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/** V3 control panel. Designed to be usable directly from the phone. */
class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var detectorState: TextView
    private lateinit var roiSummary: TextView
    private lateinit var thresholdValue: TextView
    private lateinit var roiSwitch: Switch
    private lateinit var showRoiSwitch: Switch
    private lateinit var squareSwitch: Switch
    private lateinit var editRoiButton: Button
    private lateinit var normButton: Button
    private lateinit var camoIndexButton: Button
    private var settings = DetectorSettings()
    private var pendingCapture = false
    private var touching = false
    private val sliderSyncs = mutableListOf<() -> Unit>()
    private val handler = Handler(Looper.getMainLooper())

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pendingCapture) {
            pendingCapture = false
            launchScreenCapture()
        } else if (!granted) {
            pendingCapture = false
            status.text = "Kamera belum diizinkan. Efek kamera tidak bisa aktif."
        }
    }

    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startForegroundService(Intent(this, CaptureService::class.java).apply {
                action = CaptureService.START
                putExtra(CaptureService.CODE, result.resultCode)
                putExtra(CaptureService.DATA, result.data)
            })
            status.text = "Memulai detector..."
        } else {
            status.text = "Screen capture dibatalkan."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = DetectorSettings.load(this)
        buildUi()
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 32, 28, 32)
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "Natalia Camouflage V3"
            textSize = 26f
        })
        root.addView(TextView(this).apply {
            text = "Saat Natalia kamuflase → wajahmu di kamera ikut berkamuflase"
            textSize = 14f
        })

        status = TextView(this).apply { textSize = 18f; setPadding(0, 24, 0, 4) }
        detectorState = TextView(this).apply { textSize = 16f }
        roiSummary = TextView(this).apply { textSize = 14f; setPadding(0, 8, 0, 8) }
        root.addView(status)
        root.addView(detectorState)
        root.addView(roiSummary)

        root.addView(Button(this).apply {
            text = "1. Allow Overlay"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        })

        root.addView(Button(this).apply {
            text = "2. Start Detector (sebaiknya saat game sudah landscape)"
            setOnClickListener { startCapture() }
        })

        root.addView(Button(this).apply {
            text = "Stop Detector"
            setOnClickListener {
                startService(Intent(this@MainActivity, CaptureService::class.java).setAction(CaptureService.STOP))
                refreshUi()
            }
        })

        root.addView(section("ROI detector"))
        roiSwitch = Switch(this).apply {
            text = "Gunakan ROI (bukan seluruh layar)"
            isChecked = settings.roiEnabled
            setOnCheckedChangeListener { _, checked ->
                settings = settings.copy(roiEnabled = checked).normalized()
                saveSettings()
            }
        }
        root.addView(roiSwitch)

        showRoiSwitch = Switch(this).apply {
            text = "Tampilkan kotak ROI di atas game"
            isChecked = settings.showRoi
            setOnCheckedChangeListener { _, checked ->
                settings = settings.copy(showRoi = checked).normalized()
                saveSettings()
                CaptureService.setShowRoi(checked)
            }
        }
        root.addView(showRoiSwitch)

        squareSwitch = Switch(this).apply {
            text = "Crop persegi (tidak memeras gambar)"
            isChecked = settings.squareCrop
            setOnCheckedChangeListener { _, checked ->
                settings = settings.copy(squareCrop = checked).normalized()
                saveSettings()
            }
        }
        root.addView(squareSwitch)

        editRoiButton = Button(this).apply {
            text = "Edit ROI di layar game"
            setOnClickListener {
                if (!CaptureService.running) {
                    status.text = "Start Detector dulu sebelum mengedit ROI."
                } else {
                    val editing = tag as? Boolean ?: false
                    tag = !editing
                    text = if (!editing) "Selesai Edit ROI" else "Edit ROI di layar game"
                    CaptureService.setRoiEditor(!editing)
                    status.text = if (!editing) "Edit ROI aktif: drag kotak atau handle kuning." else "Edit ROI selesai."
                }
            }
        }
        root.addView(editRoiButton)

        root.addView(Button(this).apply {
            text = "Preset: Persegi tepat di Natalia (disarankan)"
            setOnClickListener {
                val dm = resources.displayMetrics
                settings = DetectorSettings.centeredOnHero(settings, dm.widthPixels, dm.heightPixels, 0.38f)
                saveSettings()
            }
        })
        root.addView(Button(this).apply {
            text = "Preset: Persegi lebih besar"
            setOnClickListener {
                val dm = resources.displayMetrics
                settings = DetectorSettings.centeredOnHero(settings, dm.widthPixels, dm.heightPixels, 0.55f)
                saveSettings()
            }
        })
        root.addView(Button(this).apply {
            text = "Preset: Full screen"
            setOnClickListener {
                settings = settings.copy(left = 0, top = 0, width = 1000, height = 1000, squareCrop = false).normalized()
                saveSettings()
            }
        })

        addSlider(root, "ROI X / kiri", 0, 900, { settings.left }) { v -> settings = settings.copy(left = v).normalized(); saveSettings(false) }
        addSlider(root, "ROI Y / atas", 0, 900, { settings.top }) { v -> settings = settings.copy(top = v).normalized(); saveSettings(false) }
        addSlider(root, "ROI lebar", 100, 1000, { settings.width }) { v -> settings = settings.copy(width = v).normalized(); saveSettings(false) }
        addSlider(root, "ROI tinggi", 100, 1000, { settings.height }) { v -> settings = settings.copy(height = v).normalized(); saveSettings(false) }

        root.addView(section("AI stability"))
        thresholdValue = TextView(this).apply { textSize = 15f }
        root.addView(thresholdValue)
        addSlider(root, "Confidence threshold", 40, 95, { settings.threshold }) { v -> settings = settings.copy(threshold = v); saveSettings(false) }
        addSlider(root, "Frame ON berturut-turut", 1, 8, { settings.onFrames }) { v -> settings = settings.copy(onFrames = v); saveSettings(false) }
        addSlider(root, "Frame OFF berturut-turut", 1, 10, { settings.offFrames }) { v -> settings = settings.copy(offFrames = v); saveSettings(false) }

        root.addView(section("Model (kalau tetap OFF)"))
        normButton = Button(this).apply {
            setOnClickListener {
                settings = settings.copy(norm = (settings.norm + 1) % 3).normalized()
                saveSettings()
            }
        }
        root.addView(normButton)
        camoIndexButton = Button(this).apply {
            setOnClickListener {
                val next = if (settings.camoIndex >= 1) -1 else settings.camoIndex + 1
                settings = settings.copy(camoIndex = next).normalized()
                saveSettings()
            }
        }
        root.addView(camoIndexButton)

        root.addView(section("Test"))
        root.addView(Button(this).apply {
            text = "TEST CAMOUFLAGE ON (tahan)"
            setOnClickListener { CaptureService.demo(true); refreshUi() }
        })
        root.addView(Button(this).apply {
            text = "TEST CAMOUFLAGE OFF (tahan)"
            setOnClickListener { CaptureService.demo(false); refreshUi() }
        })
        root.addView(Button(this).apply {
            text = "MODE OTOMATIS (pakai AI)"
            setOnClickListener { CaptureService.auto(); refreshUi() }
        })

        root.addView(TextView(this).apply {
            text = "Cara tuning: 1) tekan preset Persegi tepat di Natalia. 2) Saat Natalia kamuflase, lihat angka 'camo %' di overlay kamera. " +
                "3) Kalau camo % tetap rendah (<20%), coba ganti Normalisasi input atau Indeks kelas camouflage. " +
                "4) Turunkan threshold sedikit di atas angka camo % saat kamuflase dan di atas angka saat normal."
            textSize = 14f
            setPadding(0, 24, 0, 0)
        })
        setContentView(scroll)
    }

    private fun section(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 20f
        setPadding(0, 24, 0, 8)
    }

    private fun addSlider(parent: LinearLayout, title: String, min: Int, max: Int, getter: () -> Int, onChanged: (Int) -> Unit) {
        val label = TextView(this).apply { textSize = 14f }
        parent.addView(label)
        val initial = getter().coerceIn(min, max)
        val bar = SeekBar(this).apply {
            this.max = max - min
            progress = initial - min
        }
        label.text = "$title: $initial"
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = progress + min
                label.text = "$title: $v"
                if (fromUser) onChanged(v)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { touching = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { touching = false }
        })
        parent.addView(bar)
        // Sinkronkan slider dengan nilai tersimpan (mis. setelah kotak ROI digeser di layar game).
        sliderSyncs.add {
            val v = getter().coerceIn(min, max)
            if (bar.progress != v - min) bar.progress = v - min
            label.text = "$title: $v"
        }
    }

    private fun saveSettings(refresh: Boolean = true) {
        settings = settings.normalized()
        DetectorSettings.save(this, settings)
        CaptureService.updateSettings(this, settings)
        if (refresh) refreshUi()
    }

    private fun startCapture() {
        if (!Settings.canDrawOverlays(this)) {
            status.text = "Izinkan Overlay terlebih dahulu."
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingCapture = true
            cameraPermission.launch(android.Manifest.permission.CAMERA)
            return
        }
        launchScreenCapture()
    }

    private fun launchScreenCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        capture.launch(manager.createScreenCaptureIntent())
    }

    private fun refreshUi() {
        handler.removeCallbacksAndMessages(null)
        if (!touching) {
            // Ambil nilai terbaru dari penyimpanan (ROI bisa berubah dari editor di layar game).
            settings = DetectorSettings.load(this)
            sliderSyncs.forEach { it() }
        }
        status.text = if (CaptureService.running) "Capture: ACTIVE" else "Capture: READY"
        val manualText = when (CaptureService.manualMode) {
            true -> " • MANUAL ON"
            false -> " • MANUAL OFF"
            null -> ""
        }
        detectorState.text = String.format(
            "AI: %s • camo %.0f%% • top: %s %.0f%%%s",
            if (CaptureService.camouflage) "CAMOUFLAGE ON" else "NORMAL/OFF",
            CaptureService.camoProb * 100f,
            CaptureService.label,
            CaptureService.confidence * 100f,
            manualText
        )
        roiSummary.text = "ROI: x=${settings.left} y=${settings.top} w=${settings.width} h=${settings.height} /1000 • threshold=${settings.threshold}%"
        thresholdValue.text = "Threshold aktif: ${settings.threshold}%"
        normButton.text = "Normalisasi input: " + when (settings.norm) { 0 -> "[-1, 1]"; 1 -> "[0, 1]"; else -> "0–255" } + " (tap untuk ganti)"
        camoIndexButton.text = "Indeks kelas camouflage: " + when (settings.camoIndex) { -1 -> "otomatis (labels.txt)"; else -> "${settings.camoIndex}" } + " (tap untuk ganti)"
        if (roiSwitch.isChecked != settings.roiEnabled) roiSwitch.isChecked = settings.roiEnabled
        if (squareSwitch.isChecked != settings.squareCrop) squareSwitch.isChecked = settings.squareCrop
        handler.postDelayed({ if (!isFinishing) refreshUi() }, 500)
    }
}
