package com.s70rm3892.enginesim

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.s70rm3892.enginesim.ui.Pal

/**
 * 画面分割。
 *  横画面 (基準): 左 = 操作コンソール (幅50%), 右上 = 3D ビューポート (50%×50%), 右下 = テレメトリ。
 *  縦長画面 (大画面で向き固定が無視された場合など): 上段にビューポート|テレメトリ、下段にコンソール。
 */
class ScreenLayout(
    context: Context,
    private val viewport: View,
    private val telemetry: View,
    private val console: View,
) {
    val root = LinearLayout(context).apply { setBackgroundColor(Pal.BG) }
    private val monitors = LinearLayout(context)

    fun apply(landscape: Boolean) {
        root.removeAllViews()
        monitors.removeAllViews()
        (console.parent as? ViewGroup)?.removeView(console)
        if (landscape) {
            root.orientation = LinearLayout.HORIZONTAL
            monitors.orientation = LinearLayout.VERTICAL
            monitors.addView(viewport, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            monitors.addView(telemetry, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            root.addView(console, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            root.addView(monitors, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        } else {
            root.orientation = LinearLayout.VERTICAL
            monitors.orientation = LinearLayout.HORIZONTAL
            monitors.addView(viewport, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            monitors.addView(telemetry, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            root.addView(monitors, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            root.addView(console, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }
}
