package com.s70rm3892.enginesim

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * Vulkan 用 3D ビューポート。SurfaceView の Surface に専用描画スレッドから描く
 * (FIFO 表示なので vkQueuePresent が垂直同期で待つ)。タッチは GL 版と同様にすべて無視する。
 * Vulkan の初期化に失敗した場合は [onVulkanFailed] で GLES へ切り替えてもらう。
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class EngineVkView(context: Context, private val onVulkanFailed: () -> Unit) : SurfaceView(context), SurfaceHolder.Callback {
    private val lock = Object()
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var paused = false
    private var surfaceReady = false
    private var width = 1
    private var height = 1
    private var sizeChanged = false

    init {
        holder.addCallback(this)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setOnTouchListener { _, _ -> true }
    }

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean = true
    override fun onTouchEvent(event: MotionEvent?): Boolean = true
    override fun onGenericMotionEvent(event: MotionEvent?): Boolean = true

    override fun surfaceCreated(h: SurfaceHolder) {
        synchronized(lock) {
            surfaceReady = true
            running = true
            val t = Thread({ renderLoop(h) }, "VkRender")
            thread = t
            t.start()
        }
    }

    override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hh: Int) {
        synchronized(lock) {
            width = w
            height = hh
            sizeChanged = true
        }
    }

    override fun surfaceDestroyed(h: SurfaceHolder) {
        // Surface を返す前に描画スレッドを止めて Vulkan 資源を解放させる
        val t: Thread?
        synchronized(lock) {
            running = false
            surfaceReady = false
            t = thread
            thread = null
            lock.notifyAll()
        }
        t?.join(2000)
    }

    fun onResume() { synchronized(lock) { paused = false; lock.notifyAll() } }
    fun onPause() { synchronized(lock) { paused = true } }

    private fun renderLoop(h: SurfaceHolder) {
        val gen = NativeBridge.vkSurfaceCreated(h.surface)
        if (gen == 0) {
            post { onVulkanFailed() }
            return
        }
        var last = System.nanoTime()
        try {
            while (true) {
                synchronized(lock) {
                    while (running && paused) lock.wait()
                    if (!running) return
                    if (sizeChanged) {
                        NativeBridge.surfaceChanged(width, height, gen)
                        sizeChanged = false
                    }
                }
                val now = System.nanoTime()
                val dt = ((now - last) / 1e9f).coerceIn(0.001f, 0.05f)
                last = now
                NativeBridge.drawFrame(dt, gen)
            }
        } finally {
            NativeBridge.vkSurfaceDestroyed(gen)
        }
    }
}
