package com.example.nataliacamo

import android.media.Image
import java.nio.ByteBuffer

/** Lightweight temporal detector placeholder. Not a trained Natalia model. */
class CamouflageDetector(private val onFramesRequired:Int=3, private val offFramesRequired:Int=5){
    private var onCount=0; private var offCount=0; private var state=false
    fun process(image:Image):Boolean{
        val plane=image.planes.firstOrNull() ?: return state
        val buffer=plane.buffer
        if(!buffer.hasRemaining()) return state
        val candidate=sampleLuma(buffer,32)<55
        if(candidate){ onCount++; offCount=0; if(!state && onCount>=onFramesRequired) state=true }
        else { offCount++; onCount=0; if(state && offCount>=offFramesRequired) state=false }
        return state
    }
    fun reset(){onCount=0;offCount=0;state=false}
    private fun sampleLuma(buffer:ByteBuffer,samples:Int):Int{
        val b=buffer.duplicate(); val size=b.remaining(); if(size<=0)return 255
        var total=0L; val count=minOf(samples,size)
        for(i in 0 until count){ val index=(i.toLong()*size/count).toInt(); total += (b.get(index).toInt() and 0xFF) }
        return (total/count).toInt().coerceIn(0,255)
    }
}
