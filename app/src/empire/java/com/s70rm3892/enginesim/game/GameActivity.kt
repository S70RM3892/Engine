package com.s70rm3892.enginesim.game

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import com.s70rm3892.enginesim.NativeBridge
import com.s70rm3892.enginesim.Viewport
import org.json.JSONObject

/**
 * インクリメンタルゲーム「ENGINE EMPIRE」(empire フレーバー = シミュレータとは別アプリ)。
 * 3D ビュー (Vulkan/GLES) は全面に置き、ゲーム演出 (炎・火花・閃光・カメラシェイク・自動周回) を有効にする。
 */
class GameActivity : Activity() {
    private lateinit var viewport: Viewport
    private lateinit var game: GameController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this)
        val viewportHost = FrameLayout(this)
        viewport = Viewport.create(this, viewportHost)
        val source = object : GameController.EngineSource {
            override fun catalogJson(id: String): String =
                assets.open("engines/$id.json").bufferedReader(Charsets.UTF_8).use { it.readText() }

            override fun customJson(params: JSONObject): String = NativeBridge.buildCustomEngine(params.toString())
        }
        game = GameController(this, NativeBridge, source, GameController.prefsStore(this))
        game.onAutoOrbit = { NativeBridge.setAutoOrbit(it) }
        game.onEffects = { NativeBridge.setEffects(it) }
        game.buildInto(root, viewportHost)
        setContentView(root)
        hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        NativeBridge.setEffects(1f)
        viewport.onResume()
        NativeBridge.setVolume(0.9f)
        NativeBridge.startAudio()
        game.start()
    }

    override fun onPause() {
        game.stop()
        NativeBridge.stopAudio()
        viewport.onPause()
        super.onPause()
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
