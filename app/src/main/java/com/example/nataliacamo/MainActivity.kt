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
    private lateinit var detectorStatus: TextView
    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startForegroundService(Intent(this, CaptureService::class.java).apply {
                action = CaptureService.ACTION_START
                putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(CaptureService.EXTRA_DATA, result.data)
            })
            status.text = "Status: memulai screen capture..."
            status.postDelayed({ refreshStatus() }, 700)
        } else status.text = "Status: izin screen capture dibatalkan"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32,48,32,32) }
        status = TextView(this).apply { text="Status: siap"; textSize=20f }
        detectorStatus = TextView(this).apply { text="Detector: NORMAL"; textSize=18f }
        fun btn(label:String, action:()->Unit)=Button(this).apply { text=label; setOnClickListener{action()} }
        box.addView(status); box.addView(detectorStatus)
        box.addView(btn("1. Izinkan Overlay") { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) })
        box.addView(btn("2. Mulai Screen Capture") { requestCapture() })
        box.addView(btn("Stop Screen Capture") { startService(Intent(this, CaptureService::class.java).apply { action=CaptureService.ACTION_STOP }); status.text="Status: menghentikan capture..."; status.postDelayed({refreshStatus()},500) })
        box.addView(btn("Test: CAMOUFLAGE ON") { CaptureService.setDemoCamouflage(true); detectorStatus.text="Detector: CAMOUFLAGE" })
        box.addView(btn("Test: CAMOUFLAGE OFF") { CaptureService.setDemoCamouflage(false); detectorStatus.text="Detector: NORMAL" })
        setContentView(box)
    }
    override fun onResume(){ super.onResume(); refreshStatus() }
    private fun requestCapture(){
        if(!Settings.canDrawOverlays(this)){ status.text="Status: izinkan Overlay terlebih dahulu"; return }
        val manager=getSystemService(MediaProjectionManager::class.java)
        captureLauncher.launch(manager.createScreenCaptureIntent())
    }
    private fun refreshStatus(){
        status.text=when { CaptureService.isRunning->"Status: screen capture aktif"; !Settings.canDrawOverlays(this)->"Status: overlay belum diizinkan"; else->"Status: siap" }
        detectorStatus.text=if(CaptureService.isCamouflageDetected) "Detector: CAMOUFLAGE" else "Detector: NORMAL"
    }
}
