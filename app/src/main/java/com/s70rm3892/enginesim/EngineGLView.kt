package com.s70rm3892.enginesim

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10

/**
 * 右上 1/4 の 3D ビューポート。
 * 仕様: ビューポート上の直接タッチ/ドラッグは無効 (すべて消費して何もしない)。視点はコンソールでのみ操作する。
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class EngineGLView(context: Context) : GLSurfaceView(context) {

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(MsaaConfigChooser())
        setRenderer(FrameRenderer())
        renderMode = RENDERMODE_CONTINUOUSLY
        preserveEGLContextOnPause = true
        isClickable = false
        isFocusable = false
        isLongClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setOnTouchListener { _, _ -> true }
    }

    // 念のため dispatch 段階でも握りつぶす
    override fun dispatchTouchEvent(event: MotionEvent?): Boolean = true
    override fun onTouchEvent(event: MotionEvent?): Boolean = true
    override fun onGenericMotionEvent(event: MotionEvent?): Boolean = true

    private class FrameRenderer : Renderer {
        private var last = 0L

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            NativeBridge.surfaceCreated()
            last = System.nanoTime()
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            NativeBridge.surfaceChanged(width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            val now = System.nanoTime()
            val dt = ((now - last) / 1e9f).coerceIn(0.001f, 0.05f)
            last = now
            NativeBridge.drawFrame(dt)
        }
    }

    /** 4xMSAA + depth24 + stencil8 (断面キャップに必須)。無ければ MSAA なしへフォールバック。 */
    private class MsaaConfigChooser : EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            val es3 = 0x40 // EGL_OPENGL_ES3_BIT_KHR
            fun attempt(samples: Int): EGLConfig? {
                val attrs = intArrayOf(
                    EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 24, EGL10.EGL_STENCIL_SIZE, 8,
                    EGL10.EGL_RENDERABLE_TYPE, es3,
                    EGL10.EGL_SAMPLE_BUFFERS, if (samples > 0) 1 else 0,
                    EGL10.EGL_SAMPLES, samples,
                    EGL10.EGL_NONE,
                )
                val num = IntArray(1)
                val configs = arrayOfNulls<EGLConfig>(1)
                return if (egl.eglChooseConfig(display, attrs, configs, 1, num) && num[0] > 0) configs[0] else null
            }
            return attempt(4) ?: attempt(0)
                ?: throw IllegalStateException("No EGL config with depth/stencil")
        }
    }
}
