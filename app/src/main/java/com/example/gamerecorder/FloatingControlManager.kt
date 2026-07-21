package com.example.gamerecorder

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button

class FloatingControlManager(
    context: Context,
    private val onStart: () -> Unit,
    private val onPauseResume: () -> Unit,
    private val onStop: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val floatingView: View = LayoutInflater.from(context).inflate(R.layout.layout_floating_control, null)
    private lateinit var layoutParams: WindowManager.LayoutParams
    private val fadeHandler = Handler(Looper.getMainLooper())
    private var isRecording = false
    private var isPaused = false

    private val fadeRunnable = Runnable {
        val anim = ValueAnimator.ofFloat(1.0f, 0.08f)
        anim.duration = 500
        anim.addUpdateListener {
            floatingView.alpha = it.animatedValue as Float
        }
        anim.start()
    }

    init {
        setupLayoutParams()
        setupListeners()
        windowManager.addView(floatingView, layoutParams)
        resetFadeTimer()
    }

    private fun setupLayoutParams() {
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }
    }

    private fun setupListeners() {
        val btnAction = floatingView.findViewById<Button>(R.id.btnAction)
        val btnStop = floatingView.findViewById<Button>(R.id.btnStop)

        floatingView.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        resetFadeTimer()
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        resetFadeTimer()
                        layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                        layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(floatingView, layoutParams)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val diffX = Math.abs(event.rawX - initialTouchX)
                        val diffY = Math.abs(event.rawY - initialTouchY)
                        if (diffX < 10 && diffY < 10) {
                            resetFadeTimer()
                        }
                        return true
                    }
                }
                return false
            }
        })

        btnAction.setOnClickListener {
            resetFadeTimer()
            if (!isRecording) {
                isRecording = true
                btnAction.text = "Pause"
                btnStop.visibility = View.VISIBLE
                onStart()
            } else {
                isPaused = !isPaused
                btnAction.text = if (isPaused) "Resume" else "Pause"
                onPauseResume()
            }
        }

        btnStop.setOnClickListener {
            onStop()
            destroy()
        }
    }

    private fun resetFadeTimer() {
        floatingView.alpha = 1.0f
        fadeHandler.removeCallbacks(fadeRunnable)
        fadeHandler.postDelayed(fadeRunnable, 5000)
    }

    fun destroy() {
        try {
            windowManager.removeView(floatingView)
            fadeHandler.removeCallbacks(fadeRunnable)
        } catch (e: Exception) {}
    }
}
