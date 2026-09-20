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

class MainActivity : Activity() {
    companion object { private const val REQUEST_CAPTURE = 1001 }
    private lateinit var status: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(32,48,32,32) }
        status = TextView(this).apply { text="Status: siap"; textSize=20f }
        val permission = Button(this).apply { text="1. Izinkan Overlay"; setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }}
        val capture = Button(this).apply { text="2. Mulai Screen Capture"; setOnClickListener {
            val m=getSystemService(MediaProjectionManager::class.java)
            startActivityForResult(m.createScreenCaptureIntent(), REQUEST_CAPTURE)
        }}
        val on = Button(this).apply { text="Demo: CAMOUFLAGE ON"; setOnClickListener { CaptureService.setDemoCamouflage(true); status.text="Status: CAMOUFLAGE ON" } }
        val off = Button(this).apply { text="Demo: CAMOUFLAGE OFF"; setOnClickListener { CaptureService.setDemoCamouflage(false); status.text="Status: NORMAL" } }
        box.addView(status); box.addView(permission); box.addView(capture); box.addView(on); box.addView(off); setContentView(box)
    }
    @Deprecated("Prototype")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==REQUEST_CAPTURE && resultCode==RESULT_OK && data!=null) {
            startForegroundService(Intent(this,CaptureService::class.java).apply { putExtra("resultCode",resultCode); putExtra("data",data) })
            status.text="Status: capture aktif"
        }
    }
}
