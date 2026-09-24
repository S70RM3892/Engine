package com.s70rm3892.enginesim

import android.app.Activity
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * 3D ビューポートの生成と描画 API の選択。Vulkan を優先し、非対応/初期化失敗時は GLES 3.0 に切り替える。
 */
class Viewport private constructor(private val activity: Activity, private val host: FrameLayout) {
    private var vk: EngineVkView? = null
    private var gl: EngineGLView? = null
    private var resumed = false

    val view: View get() = vk ?: gl!!

    private fun useGl() {
        vk?.let { host.removeView(it) }
        vk = null
        val g = EngineGLView(activity)
        gl = g
        host.addView(g, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (resumed) g.onResume()
    }

    private fun useVk() {
        val v = EngineVkView(activity) {
            Log.w("EngineSim", "Vulkan init failed, falling back to GLES")
            useGl()
        }
        vk = v
        host.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun onResume() {
        resumed = true
        vk?.onResume()
        gl?.onResume()
    }

    fun onPause() {
        resumed = false
        vk?.onPause()
        gl?.onPause()
    }

    companion object {
        /** 設定で GLES を強制するためのフラグ (デバッグ用) */
        var forceGles = false

        fun create(activity: Activity, host: FrameLayout): Viewport {
            val vp = Viewport(activity, host)
            if (!forceGles && NativeBridge.vkSupported()) vp.useVk() else vp.useGl()
            return vp
        }
    }
}
