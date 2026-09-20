package com.example.nataliacamo

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private var pendingCapture = false
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pendingCapture) {
            pendingCapture = false
            launchScreenCapture()
        } else if (!granted) {
            pendingCapture = false
            status.text = "Izin kamera diperlukan untuk efek camouflage"
        }
    }
    private lateinit var status: TextView
    private lateinit var detector: TextView
    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null) {
            startForegroundService(Intent(this, CaptureService::class.java).apply {
                action=CaptureService.START
                putExtra(CaptureService.CODE,r.resultCode)
                putExtra(CaptureService.DATA,r.data)
            })
            status.text="Capture: starting..."
        } else status.text="Capture: cancelled"
    }
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(32,40,32,24) }
        status=TextView(this).apply { textSize=19f }
        detector=TextView(this).apply { textSize=18f }
        root.addView(status); root.addView(detector)
        root.addView(Button(this).apply { text="Allow Overlay"; setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }})
        root.addView(Button(this).apply { text="Start Final Detector"; setOnClickListener { startCapture() }})
        root.addView(Button(this).apply { text="Stop"; setOnClickListener {
            startService(Intent(this@MainActivity,CaptureService::class.java).setAction(CaptureService.STOP))
        }})
        root.addView(Button(this).apply { text="Test CAMOUFLAGE ON"; setOnClickListener { CaptureService.demo(true) }})
        root.addView(Button(this).apply { text="Test CAMOUFLAGE OFF"; setOnClickListener { CaptureService.demo(false) }})
        setContentView(root); refresh()
    }
    override fun onResume(){super.onResume();refresh()}
    private fun startCapture(){
        if(!Settings.canDrawOverlays(this)){ status.text="Allow overlay first"; return }
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingCapture = true
            cameraPermission.launch(android.Manifest.permission.CAMERA)
            return
        }
        launchScreenCapture()
    }

    private fun launchScreenCapture() {
        val m=getSystemService(MediaProjectionManager::class.java)
        capture.launch(m.createScreenCaptureIntent())
    }
    private fun refresh(){
        status.text=if(CaptureService.running)"Capture: ACTIVE" else "Capture: READY"
        detector.text=if(CaptureService.camouflage)"AI state: CAMOUFLAGE" else "AI state: NORMAL"
    }
}
