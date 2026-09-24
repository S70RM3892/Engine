package com.s70rm3892.enginesim

import android.app.Activity
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import com.s70rm3892.enginesim.ui.TelemetryView

/**
 * 横画面レイアウト:
 *  ┌──────────────────────┬───────────────┐
 *  │                      │ 3D ビューポート │ ← 幅50% × 高さ50% (タッチ無効)
 *  │   操作コンソール     ├───────────────┤
 *  │   (左半分)           │ テレメトリ      │
 *  └──────────────────────┴───────────────┘
 */
class MainActivity : Activity() {
    private lateinit var glView: EngineGLView
    private lateinit var console: ConsoleUIController
    private lateinit var screen: ScreenLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val consoleHost = FrameLayout(this)
        glView = EngineGLView(this)
        val telemetry = TelemetryView(this)
        screen = ScreenLayout(this, glView, telemetry, consoleHost)
        screen.apply(isLandscape())
        setContentView(screen.root)

        console = ConsoleUIController(this, telemetry, EngineCatalog(this))
        console.buildInto(consoleHost)
        console.loadDefault()
        hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        NativeBridge.startAudio()
        console.start()
    }

    override fun onPause() {
        console.stop()
        NativeBridge.stopAudio()
        glView.onPause()
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        screen.apply(isLandscape())
    }

    private fun isLandscape(): Boolean {
        val m = resources.displayMetrics
        return m.widthPixels >= m.heightPixels
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }
}
