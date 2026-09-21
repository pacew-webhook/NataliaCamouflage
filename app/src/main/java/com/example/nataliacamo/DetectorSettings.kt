package com.example.nataliacamo

import android.content.Context

data class DetectorSettings(
    val left: Int = 417,
    val top: Int = 347,
    val width: Int = 170,
    val height: Int = 380,
    val threshold: Int = 65,
    val onFrames: Int = 2,
    val offFrames: Int = 3,
    val roiEnabled: Boolean = true,
    val showRoi: Boolean = true,
    /** Crop persegi (tidak diperas) sebelum masuk ke model. */
    val squareCrop: Boolean = true,
    /** 0 = [-1,1], 1 = [0,1], 2 = 0..255 (hanya model FLOAT32). */
    val norm: Int = 0,
    /** -1 = otomatis dari labels.txt, 0/1 = paksa indeks output "camouflage". */
    val camoIndex: Int = -1,
) {
    companion object {
        private const val PREF = "detector_settings_v3"
        private const val L = "left"
        private const val T = "top"
        private const val W = "width"
        private const val H = "height"
        private const val TH = "threshold"
        private const val ON = "on_frames"
        private const val OFF = "off_frames"
        private const val ENABLED = "roi_enabled"
        private const val SHOW = "show_roi"
        private const val SQUARE = "square_crop"
        private const val NORM = "norm"
        private const val CAMO_INDEX = "camo_index"

        fun save(context: Context, settings: DetectorSettings) = settings.save(context)

        /**
         * ROI persegi (dalam piksel) tepat di posisi hero. Kamera MLBB selalu
         * menaruh hero di sekitar tengah layar (x ~50%, y ~54%).
         */
        fun centeredOnHero(base: DetectorSettings, screenW: Int, screenH: Int, sideFraction: Float = 0.38f): DetectorSettings {
            val w = maxOf(screenW, screenH).toFloat()
            val h = minOf(screenW, screenH).toFloat()
            val side = h * sideFraction
            val wPer = (side / w * 1000f).toInt().coerceAtLeast(100)
            val hPer = (sideFraction * 1000f).toInt().coerceAtLeast(100)
            return base.copy(
                left = 502 - wPer / 2,
                top = 537 - hPer / 2,
                width = wPer,
                height = hPer,
                roiEnabled = true,
                squareCrop = true,
            ).normalized()
        }

        fun load(context: Context): DetectorSettings {
            val d = DetectorSettings()
            val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            return DetectorSettings(
                left = p.getInt(L, d.left),
                top = p.getInt(T, d.top),
                width = p.getInt(W, d.width),
                height = p.getInt(H, d.height),
                threshold = p.getInt(TH, d.threshold),
                onFrames = p.getInt(ON, d.onFrames),
                offFrames = p.getInt(OFF, d.offFrames),
                roiEnabled = p.getBoolean(ENABLED, d.roiEnabled),
                showRoi = p.getBoolean(SHOW, d.showRoi),
                squareCrop = p.getBoolean(SQUARE, d.squareCrop),
                norm = p.getInt(NORM, d.norm),
                camoIndex = p.getInt(CAMO_INDEX, d.camoIndex),
            ).normalized()
        }
    }

    fun normalized(): DetectorSettings {
        val w = width.coerceIn(100, 1000)
        val h = height.coerceIn(100, 1000)
        return copy(
            left = left.coerceIn(0, 1000 - w),
            top = top.coerceIn(0, 1000 - h),
            width = w,
            height = h,
            threshold = threshold.coerceIn(40, 95),
            onFrames = onFrames.coerceIn(1, 8),
            offFrames = offFrames.coerceIn(1, 10),
            norm = norm.coerceIn(0, 2),
            camoIndex = camoIndex.coerceIn(-1, 1),
        )
    }

    fun save(context: Context) {
        val s = normalized()
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putInt(L, s.left)
            .putInt(T, s.top)
            .putInt(W, s.width)
            .putInt(H, s.height)
            .putInt(TH, s.threshold)
            .putInt(ON, s.onFrames)
            .putInt(OFF, s.offFrames)
            .putBoolean(ENABLED, s.roiEnabled)
            .putBoolean(SHOW, s.showRoi)
            .putBoolean(SQUARE, s.squareCrop)
            .putInt(NORM, s.norm)
            .putInt(CAMO_INDEX, s.camoIndex)
            .apply()
    }
}
