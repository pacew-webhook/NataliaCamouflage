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

/** V2 control panel. Designed to be usable directly from the phone. */
class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var detectorState: TextView
    private lateinit var roiSummary: TextView
    private lateinit var thresholdValue: TextView
    private lateinit var roiSwitch: Switch
    private lateinit var showRoiSwitch: Switch
    private lateinit var editRoiButton: Button
    private var settings = DetectorSettings()
    private var pendingCapture = false
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
        settings = DetectorSettings.load(this)
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

        val title = TextView(this).apply {
            text = "Natalia Camouflage V2"
            textSize = 26f
        }
        root.addView(title)
        root.addView(TextView(this).apply {
            text = "ROI-first detector • confidence • stabilizer • live camera mask"
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
            text = "2. Start Detector"
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
            text = "Preset: Tengah / Natalia"
            setOnClickListener {
                settings = settings.copy(left = 250, top = 180, width = 500, height = 560).normalized()
                saveSettings()
            }
        })
        root.addView(Button(this).apply {
            text = "Preset: Area tengah lebih kecil"
            setOnClickListener {
                settings = settings.copy(left = 320, top = 250, width = 360, height = 400).normalized()
                saveSettings()
            }
        })
        root.addView(Button(this).apply {
            text = "Preset: Full screen"
            setOnClickListener {
                settings = settings.copy(left = 0, top = 0, width = 1000, height = 1000).normalized()
                saveSettings()
            }
        })

        addSlider(root, "ROI X / kiri", settings.left, 0, 900) { v -> settings = settings.copy(left = v).normalized(); saveSettings(false) }
        addSlider(root, "ROI Y / atas", settings.top, 0, 900) { v -> settings = settings.copy(top = v).normalized(); saveSettings(false) }
        addSlider(root, "ROI lebar", settings.width, 100, 1000) { v -> settings = settings.copy(width = v).normalized(); saveSettings(false) }
        addSlider(root, "ROI tinggi", settings.height, 100, 1000) { v -> settings = settings.copy(height = v).normalized(); saveSettings(false) }

        root.addView(section("AI stability"))
        thresholdValue = TextView(this).apply { textSize = 15f }
        root.addView(thresholdValue)
        addSlider(root, "Confidence threshold", settings.threshold, 40, 95) { v -> settings = settings.copy(threshold = v); saveSettings(false) }
        addSlider(root, "Frame ON berturut-turut", settings.onFrames, 1, 8) { v -> settings = settings.copy(onFrames = v); saveSettings(false) }
        addSlider(root, "Frame OFF berturut-turut", settings.offFrames, 1, 10) { v -> settings = settings.copy(offFrames = v); saveSettings(false) }

        root.addView(section("Test"))
        root.addView(Button(this).apply {
            text = "TEST CAMOUFLAGE ON"
            setOnClickListener { CaptureService.demo(true); refreshUi() }
        })
        root.addView(Button(this).apply {
            text = "TEST CAMOUFLAGE OFF"
            setOnClickListener { CaptureService.demo(false); refreshUi() }
        })

        root.addView(TextView(this).apply {
            text = "Tip: mulai dengan preset Tengah / Natalia. Jika masih OFF saat Natalia camouflage, aktifkan Edit ROI lalu geser kotak tepat ke posisi Natalia."
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

    private fun addSlider(parent: LinearLayout, title: String, initial: Int, min: Int, max: Int, onChanged: (Int) -> Unit) {
        val label = TextView(this).apply { textSize = 14f }
        parent.addView(label)
        val bar = SeekBar(this).apply {
            this.max = max - min
            progress = initial.coerceIn(min, max) - min
        }
        label.text = "$title: $initial"
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = progress + min
                label.text = "$title: $v"
                if (fromUser) onChanged(v)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        parent.addView(bar)
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
        status.text = if (CaptureService.running) "Capture: ACTIVE" else "Capture: READY"
        detectorState.text = String.format(
            "AI: %s • confidence %.0f%% • %s",
            if (CaptureService.camouflage) "CAMOUFLAGE ON" else "NORMAL/OFF",
            CaptureService.confidence * 100f,
            CaptureService.label
        )
        roiSummary.text = "ROI: x=${settings.left} y=${settings.top} w=${settings.width} h=${settings.height} /1000 • threshold=${settings.threshold}%"
        thresholdValue.text = "Threshold aktif: ${settings.threshold}%"
        handler.postDelayed({ if (!isFinishing) refreshUi() }, 500)
    }
}
