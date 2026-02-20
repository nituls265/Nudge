package com.example.nudgev0

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class MyAccessibilityService : AccessibilityService() {

    private var bubbleView: View? = null
    private lateinit var windowManager: WindowManager
    private var bubbleTextView: TextView? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Listen for the toggle signal from the UI
        serviceScope.launch {
            isBubbleVisible.collect { shouldShow ->
                if (shouldShow) showBubble() else removeBubble()
            }
        }
    }

    private fun showBubble() {
        if (bubbleView != null) return

        val inflater = LayoutInflater.from(this)
        bubbleView = inflater.inflate(R.layout.bubble_layout, null)
        bubbleTextView = bubbleView?.findViewById(R.id.bubble_text_view)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 50
            y = 200
        }

        // --- TOUCH LISTENER WITH ANIMATION LOGIC ---
        bubbleView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            // Variables to detect a "Click"
            private val CLICK_THRESHOLD = 10
            private var isClick = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY

                        // Assume it's a click until the user moves their finger
                        isClick = true
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = (event.rawX - initialTouchX).toInt()
                        val deltaY = (event.rawY - initialTouchY).toInt()

                        // If user moves finger more than 10 pixels, it's a Drag, not a Click
                        if (Math.abs(deltaX) > CLICK_THRESHOLD || Math.abs(deltaY) > CLICK_THRESHOLD) {
                            isClick = false
                        }

                        params.x = initialX - deltaX
                        params.y = initialY - deltaY
                        windowManager.updateViewLayout(bubbleView, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        // If it was a click, play the Heartbeat Animation!
                        if (isClick) {
                            performHeartbeatAnimation(v)
                        }
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(bubbleView, params)
        updateBubbleText()
    }

    // --- THE HEARTBEAT ANIMATION ---
    private fun performHeartbeatAnimation(view: View) {
        view.animate()
            .scaleX(1.2f) // Grow to 120%
            .scaleY(1.2f)
            .setDuration(100) // Fast expansion (0.1s)
            .withEndAction {
                // Shrink back to normal
                view.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(100)
                    .start()
            }
            .start()
    }

    private fun removeBubble() {
        bubbleView?.let {
            windowManager.removeView(it)
            bubbleView = null
            bubbleTextView = null
        }
    }

    private fun updateBubbleText() {
        // Now this can see _scrollCount because they are in the same file
        bubbleTextView?.text = _scrollCount.value.toString()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {

        // If paused, do NOTHING. Just return.
        if (_isPaused.value) return

        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            _scrollCount.update { it + 1 }
            _scrollTimestamps.update { it + System.currentTimeMillis() }

            if (_isBubbleVisible.value) {
                updateBubbleText()
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    // --- ONLY ONE COMPANION OBJECT ALLOWED ---
    companion object {
        private val _scrollCount = MutableStateFlow(0)
        val scrollCount = _scrollCount.asStateFlow()

        private val _scrollTimestamps = MutableStateFlow<List<Long>>(emptyList())
        val scrollTimestamps = _scrollTimestamps.asStateFlow()

        private val _isBubbleVisible = MutableStateFlow(false)
        val isBubbleVisible = _isBubbleVisible.asStateFlow()

        private val _isPaused = MutableStateFlow(false)
        val isPaused = _isPaused.asStateFlow()

        fun togglePause() {
            _isPaused.update { !it }
        }

        fun resetScrollCount() {
            _scrollCount.value = 0
            _scrollTimestamps.value = emptyList()
        }

        fun toggleBubbleVisibility() {
            _isBubbleVisible.update { !it }
        }
    }
}