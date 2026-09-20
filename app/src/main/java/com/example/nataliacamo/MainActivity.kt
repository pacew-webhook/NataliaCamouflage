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

    private val captureLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            if (result.resultCode == Activity.RESULT_OK && result.data != null) {

                val serviceIntent =
                    Intent(this, CaptureService::class.java).apply {
                        action = CaptureService.ACTION_START
                        putExtra(
                            CaptureService.EXTRA_RESULT_CODE,
                            result.resultCode
                        )
                        putExtra(
                            CaptureService.EXTRA_DATA,
                            result.data
                        )
                    }

                startForegroundService(serviceIntent)

                status.text = "Status: memulai screen capture..."

                status.postDelayed({
                    refreshStatus()
                }, 700)

            } else {
                status.text =
                    "Status: izin screen capture dibatalkan"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }

        status = TextView(this).apply {
            text = "Status: siap"
            textSize = 20f
        }

        val permission = Button(this).apply {
            text = "1. Izinkan Overlay"

            setOnClickListener {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        val capture = Button(this).apply {
            text = "2. Mulai Screen Capture"

            setOnClickListener {
                requestCapture()
            }
        }

        val stop = Button(this).apply {
            text = "Stop Screen Capture"

            setOnClickListener {

                val intent =
                    Intent(
                        this@MainActivity,
                        CaptureService::class.java
                    ).apply {
                        action = CaptureService.ACTION_STOP
                    }

                startService(intent)

                status.text =
                    "Status: menghentikan capture..."

                status.postDelayed({
                    refreshStatus()
                }, 500)
            }
        }

        val on = Button(this).apply {
            text = "Demo: CAMOUFLAGE ON"

            setOnClickListener {
                CaptureService.setDemoCamouflage(true)
                status.text =
                    "Status: CAMOUFLAGE ON"
            }
        }

        val off = Button(this).apply {
            text = "Demo: CAMOUFLAGE OFF"

            setOnClickListener {
                CaptureService.setDemoCamouflage(false)
                status.text =
                    "Status: NORMAL"
            }
        }

        box.addView(status)
        box.addView(permission)
        box.addView(capture)
        box.addView(stop)
        box.addView(on)
        box.addView(off)

        setContentView(box)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun requestCapture() {

        if (!Settings.canDrawOverlays(this)) {

            status.text =
                "Status: izinkan Overlay terlebih dahulu"

            return
        }

        val manager =
            getSystemService(
                MediaProjectionManager::class.java
            )

        val captureIntent =
            manager.createScreenCaptureIntent()

        captureLauncher.launch(captureIntent)
    }

    private fun refreshStatus() {

        status.text = when {

            CaptureService.isRunning ->
                "Status: screen capture aktif"

            !Settings.canDrawOverlays(this) ->
                "Status: overlay belum diizinkan"

            else ->
                "Status: siap"
        }
    }
}
