package com.example.nataliacamo

import android.content.Context

data class DetectorSettings(
    val left: Int = 250,
    val top: Int = 180,
    val width: Int = 500,
    val height: Int = 560,
    val threshold: Int = 65,
    val onFrames: Int = 2,
    val offFrames: Int = 3,
    val roiEnabled: Boolean = true,
    val showRoi: Boolean = true,
) {
    companion object {
        private const val PREF = "detector_settings_v2"
        private const val L = "left"
        private const val T = "top"
        private const val W = "width"
        private const val H = "height"
        private const val TH = "threshold"
        private const val ON = "on_frames"
        private const val OFF = "off_frames"
        private const val ENABLED = "roi_enabled"
        private const val SHOW = "show_roi"

        fun save(context: Context, settings: DetectorSettings) = settings.save(context)

        fun load(context: Context): DetectorSettings {
            val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            return DetectorSettings(
                left = p.getInt(L, 250).coerceIn(0, 900),
                top = p.getInt(T, 180).coerceIn(0, 900),
                width = p.getInt(W, 500).coerceIn(100, 1000),
                height = p.getInt(H, 560).coerceIn(100, 1000),
                threshold = p.getInt(TH, 65).coerceIn(40, 95),
                onFrames = p.getInt(ON, 2).coerceIn(1, 8),
                offFrames = p.getInt(OFF, 3).coerceIn(1, 10),
                roiEnabled = p.getBoolean(ENABLED, true),
                showRoi = p.getBoolean(SHOW, true),
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
            .apply()
    }
}
