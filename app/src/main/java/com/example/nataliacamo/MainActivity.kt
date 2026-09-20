package com.example.nataliacamo

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var debug: TextView

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startServiceWithCapture(result.resultCode, result.data!!)
            status.text = "Status: memulai screen capture..."
        } else {
            status.text = "Status: izin screen capture dibatalkan"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 32)
        }
        status = TextView(this).apply { text = "Status: siap"; textSize = 20f }
        debug = TextView(this).apply { text = "Frame: -\nFPS: -\nResolusi: -"; textSize = 16f }

        val overlay = Button(this).apply {
            text = "1. Izinkan Overlay"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        val capture = Button(this).apply {
            text = "2. Mulai Screen Capture"
            setOnClickListener { requestCapture() }
        }
        val stop = Button(this).apply {
            text = "Stop Screen Capture"
            setOnClickListener {
                startService(Intent(this@MainActivity, CaptureService::class.java).apply { action = CaptureService.ACTION_STOP })
                status.text = "Status: menghentikan capture..."
            }
        }
        val demoOn = Button(this).apply {
            text = "Demo: CAMOUFLAGE ON"
            setOnClickListener { CaptureService.setDemoCamouflage(true); status.text = "Status: CAMOUFLAGE ON" }
        }
        val demoOff = Button(this).apply {
            text = "Demo: CAMOUFLAGE OFF"
            setOnClickListener { CaptureService.setDemoCamouflage(false); status.text = "Status: NORMAL" }
        }

        box.addView(status)
        box.addView(debug)
        box.addView(overlay)
        box.addView(capture)
        box.addView(stop)
        box.addView(demoOn)
        box.addView(demoOff)
        setContentView(box)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun requestCapture() {
        if (!Settings.canDrawOverlays(this)) {
            status.text = "Status: izinkan Overlay terlebih dahulu"
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        captureLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun startServiceWithCapture(code: Int, data: Intent) {
        startForegroundService(Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START
            putExtra(CaptureService.EXTRA_RESULT_CODE, code)
            putExtra(CaptureService.EXTRA_DATA, data)
        })
        window.decorView.postDelayed({ refresh() }, 700)
    }

    private fun refresh() {
        status.text = when {
            CaptureService.isRunning -> "Status: screen capture aktif"
            !Settings.canDrawOverlays(this) -> "Status: overlay belum diizinkan"
            else -> "Status: siap"
        }
        debug.text = "Frame: ${CaptureService.frameCount}\nFPS: ${"%.1f".format(CaptureService.fps)}\nResolusi: ${CaptureService.captureWidth} × ${CaptureService.captureHeight}"
        window.decorView.postDelayed({ if (!isFinishing) refresh() }, 1000)
    }
}
