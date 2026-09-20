package com.example.nataliacamo

import android.media.Image
import java.nio.ByteBuffer
import kotlin.math.abs

data class Detection(val camouflage:Boolean,val confidence:Float)

class CamouflageDetector {
    private var baseline=0f
    private var state=false
    private var onFrames=0
    private var offFrames=0
    fun process(image:Image):Detection {
        val p=image.planes.firstOrNull() ?: return Detection(state,0f)
        val b=p.buffer.duplicate()
        val score=darkScore(b)
        if(baseline==0f) baseline=score
        baseline=baseline*0.98f+score*0.02f
        val delta=score-baseline
        val confidence=(abs(delta)/255f).coerceIn(0f,1f)
        // Production hook: replace this classifier with the trained Natalia TFLite model.
        val candidate=score < baseline-8f
        if(candidate){onFrames++;offFrames=0}else{offFrames++;onFrames=0}
        if(!state && onFrames>=3)state=true
        if(state && offFrames>=5)state=false
        return Detection(state,confidence)
    }
    fun reset(){baseline=0f;state=false;onFrames=0;offFrames=0}
    private fun darkScore(buf:ByteBuffer):Float{
        val n=buf.remaining(); if(n<4)return 255f
        var total=0L; var count=0
        val step=maxOf(4,n/64)
        var i=0
        while(i<n){ total += (buf.get(i).toInt() and 255); count++; i+=step }
        return (total.toFloat()/count).coerceIn(0f,255f)
    }
}
