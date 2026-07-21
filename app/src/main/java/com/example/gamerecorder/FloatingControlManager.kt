package com.example.gamerecorder

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import kotlin.math.abs

class FloatingControlManager(
    private val context: Context,
    private val onStart: () -> Unit,
    private val onPauseResume: () -> Boolean,
    private val onStop: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val themedContext = ContextThemeWrapper(context, R.style.Theme_GameRecorder)
    private val rootView: View = LayoutInflater.from(themedContext).inflate(R.layout.layout_floating_control, null)

    private val btnMainCircle: FrameLayout = rootView.findViewById(R.id.btnMainCircle)
    private val layoutExpandedControls: LinearLayout = rootView.findViewById(R.id.layoutExpandedControls)
    private val btnStart: View = rootView.findViewById(R.id.btnStart)
    private val btnPauseResume: ImageButton = rootView.findViewById(R.id.btnPauseResume)
    private val btnStop: View = rootView.findViewById(R.id.btnStop)

    private val layoutParams: WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        },
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 100
        y = 200
    }

    private var isExpanded = false

    init {
        windowManager.addView(rootView, layoutParams)
        setupTouchAndDrag()
        setupClickListeners()
    }

    private fun setupTouchAndDrag() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        btnMainCircle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - initialTouchX).toInt()
                    val deltaY = (event.rawY - initialTouchY).toInt()

                    if (abs(deltaX) > 5 || abs(deltaY) > 5) {
                        isDragging = true
                    }

                    if (isDragging) {
                        layoutParams.x = initialX + deltaX
                        layoutParams.y = initialY + deltaY
                        windowManager.updateViewLayout(rootView, layoutParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        toggleExpandedState()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleExpandedState() {
        isExpanded = !isExpanded
        if (isExpanded) {
            btnMainCircle.visibility = View.GONE
            layoutExpandedControls.visibility = View.VISIBLE
        } else {
            layoutExpandedControls.visibility = View.GONE
            btnMainCircle.visibility = View.VISIBLE
        }
    }

    private fun setupClickListeners() {
        btnStart.setOnClickListener {
            onStart()
            toggleExpandedState()
        }

        btnPauseResume.setOnClickListener {
            val isPaused = onPauseResume()
            if (isPaused) {
                btnPauseResume.setImageResource(R.drawable.ic_play)
            } else {
                btnPauseResume.setImageResource(R.drawable.ic_pause)
            }
        }

        btnStop.setOnClickListener {
            onStop()
            destroy()
        }
    }

    fun destroy() {
        try {
            windowManager.removeView(rootView)
        } catch (_: Exception) {}
    }
}
