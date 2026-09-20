package com.example.nataliacamo

import android.app.*
import android.content.*
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.*
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean

class CaptureService:Service(){
    private var projection:MediaProjection?=null
    private var reader:ImageReader?=null
    private var display:android.hardware.display.VirtualDisplay?=null
    private var overlay:TextView?=null
    private var wm:WindowManager?=null
    private val detector: CamouflageDetector by lazy { CamouflageDetector(applicationContext) }
    private val camo=AtomicBoolean(false)
    companion object{
        const val START="START"; const val STOP="STOP"; const val CODE="code"; const val DATA="data"
        @Volatile var running=false; private set
        @Volatile var camouflage=false; private set
        private var instance:CaptureService?=null
        fun demo(v:Boolean){instance?.setCamo(v)}
    }
    override fun onCreate(){super.onCreate();instance=this;detector;channel();startForeground(7,notification())}
    override fun onStartCommand(i:Intent?,f:Int,id:Int):Int{
        when(i?.action){START->startCapture(i);STOP->stopAll()}
        return START_NOT_STICKY
    }
    private fun startCapture(i:Intent){
        if(running)return
        val code=i.getIntExtra(CODE,Activity.RESULT_CANCELED)
        val data=if(Build.VERSION.SDK_INT>=33)i.getParcelableExtra(DATA,Intent::class.java) else @Suppress("DEPRECATION") i.getParcelableExtra(DATA)
        if(code!=Activity.RESULT_OK||data==null){stopSelf();return}
        val m=getSystemService(MediaProjectionManager::class.java)
        projection=m.getMediaProjection(code,data)
        projection?.registerCallback(object:MediaProjection.Callback(){override fun onStop(){stopCapture()}},Handler(Looper.getMainLooper()))
        val dm=resources.displayMetrics
        reader=ImageReader.newInstance(dm.widthPixels,dm.heightPixels,PixelFormat.RGBA_8888,2)
        reader?.setOnImageAvailableListener({r->
            val image=r.acquireLatestImage()?:return@setOnImageAvailableListener
            try{val d=detector.process(image); if(d.camouflage!=camouflage)setCamo(d.camouflage)}finally{image.close()}
        },Handler(Looper.getMainLooper()))
        display=projection?.createVirtualDisplay("NataliaFinal",dm.widthPixels,dm.heightPixels,dm.densityDpi,0,reader!!.surface,null,null)
        makeOverlay();running=true
    }
    private fun makeOverlay(){
        if(overlay!=null)return
        wm=getSystemService(WINDOW_SERVICE) as WindowManager
        overlay=TextView(this).apply{text="CAMOUFLAGE";textSize=24f;setTextColor(0xffffffff.toInt());setBackgroundColor(0x66000000);setPadding(24,14,24,14);alpha=0f}
        val p=WindowManager.LayoutParams(-2,-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.CENTER_HORIZONTAL;y=120}
        try{wm?.addView(overlay,p)}catch(_:Exception){}
    }
    private fun setCamo(v:Boolean){camo.set(v);camouflage=v;overlay?.animate()?.alpha(if(v)1f else 0f)?.setDuration(160)?.start()}
    private fun stopCapture(){try{display?.release()}catch(_:Exception){};display=null;try{reader?.close()}catch(_:Exception){};reader=null;try{projection?.stop()}catch(_:Exception){};projection=null;setCamo(false);running=false}
    private fun stopAll(){stopCapture();stopSelf()}
    private fun channel(){getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("final","Natalia Final",NotificationManager.IMPORTANCE_LOW))}
    private fun notification()=Notification.Builder(this,"final").setContentTitle("Natalia Camouflage Final").setContentText("Detector aktif").setSmallIcon(android.R.drawable.ic_menu_view).build()
    override fun onDestroy(){stopCapture();detector.close();overlay?.let{try{wm?.removeView(it)}catch(_:Exception){}};instance=null;super.onDestroy()}
    override fun onBind(i:Intent?)=null
}
