package com.example.nataliacamo

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean

class CaptureService : Service() {
    private var projection: MediaProjection?=null
    private var reader: ImageReader?=null
    private var overlay: TextView?=null
    private var wm: WindowManager?=null
    private val camo=AtomicBoolean(false)
    companion object { @Volatile private var instance:CaptureService?=null; fun setDemoCamouflage(on:Boolean){instance?.setCamouflage(on)} }
    override fun onCreate(){ super.onCreate(); instance=this; channel(); startForeground(10, notification()); createOverlay() }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        val code=intent?.getIntExtra("resultCode",Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data=intent?.getParcelableExtra<Intent>("data")
        if(code==Activity.RESULT_OK && data!=null && projection==null){
            val mgr=getSystemService(MediaProjectionManager::class.java); projection=mgr.getMediaProjection(code,data)
            val d=resources.displayMetrics
            reader=ImageReader.newInstance(d.widthPixels,d.heightPixels,PixelFormat.RGBA_8888,2)
            reader?.setOnImageAvailableListener({r -> r.acquireLatestImage()?.close() }, Handler(Looper.getMainLooper()))
            projection?.createVirtualDisplay("NataliaPrototype",d.widthPixels,d.heightPixels,d.densityDpi,0,reader!!.surface,null,null)
        }
        return START_STICKY
    }
    private fun createOverlay(){
        wm=getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlay=TextView(this).apply{ text="CAMOUFLAGE"; textSize=24f; setTextColor(0xffffffff.toInt()); setBackgroundColor(0x66000000); setPadding(24,16,24,16); alpha=0f }
        val p=WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.CENTER_HORIZONTAL; y=120}
        try{wm?.addView(overlay,p)}catch(_:Exception){}
    }
    private fun setCamouflage(on:Boolean){ if(camo.getAndSet(on)==on)return; overlay?.animate()?.alpha(if(on)1f else 0f)?.setDuration(150)?.start() }
    private fun channel(){getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("capture","Screen Capture",NotificationManager.IMPORTANCE_LOW))}
    private fun notification()=Notification.Builder(this,"capture").setContentTitle("Natalia Detector").setContentText("Screen capture aktif").setSmallIcon(android.R.drawable.ic_menu_view).build()
    override fun onDestroy(){overlay?.let{try{wm?.removeView(it)}catch(_:Exception){}}; reader?.close(); projection?.stop(); instance=null; super.onDestroy()}
    override fun onBind(intent:Intent?)=null
}
