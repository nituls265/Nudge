package com.example.nudgev0

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
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

    // Filter state (Issue 1)
    private var lastWindowStateChangeTime = 0L
    private var lastKeyboardTransitionTime = 0L
    private var lastEditTextFocusTime = 0L
    private var isEditTextFocused = false
    private var lastTextChangeTime = 0L
    private var lastScrollTime = 0L
    private var lastCascadeTime = 0L
    private var lastItemCount = -1

    private val keyboardPackageHints = listOf("inputmethod", "keyboard", "gboard")

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

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

        // Issue 5: Restore last saved position instead of always defaulting
        val prefs = getSharedPreferences("nudge_prefs", Context.MODE_PRIVATE)
        val savedX = prefs.getInt("bubble_x", 50)
        val savedY = prefs.getInt("bubble_y", 200)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = savedX
            y = savedY
        }

        bubbleView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private val CLICK_THRESHOLD = 10
            private var isClick = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isClick = true
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = (event.rawX - initialTouchX).toInt()
                        val deltaY = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(deltaX) > CLICK_THRESHOLD || Math.abs(deltaY) > CLICK_THRESHOLD) {
                            isClick = false
                        }
                        params.x = initialX - deltaX
                        params.y = initialY - deltaY
                        windowManager.updateViewLayout(bubbleView, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isClick) {
                            performHeartbeatAnimation(v)
                        } else {
                            // Issue 5: Persist position after every drag
                            getSharedPreferences("nudge_prefs", Context.MODE_PRIVATE)
                                .edit()
                                .putInt("bubble_x", params.x)
                                .putInt("bubble_y", params.y)
                                .apply()
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

    private fun performHeartbeatAnimation(view: View) {
        view.animate()
            .scaleX(1.2f)
            .scaleY(1.2f)
            .setDuration(100)
            .withEndAction {
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
        bubbleTextView?.text = _scrollCount.value.toString()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (_isPaused.value) return
        event ?: return

        val now = System.currentTimeMillis()

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val isEditText = event.className?.contains("EditText") == true
                if (isEditText) {
                    // Case 2 fix: EditText just gained focus → keyboard is about to open.
                    // Record the time so filter B3 can block the imminent reposition scroll.
                    isEditTextFocused = true
                    lastEditTextFocusTime = now
                } else {
                    isEditTextFocused = false
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: ""
                val isKeyboard = keyboardPackageHints.any { pkg.contains(it) } ||
                    event.className?.toString()?.let {
                        it.contains("InputMethod") || it.contains("SoftInput")
                    } == true
                if (isKeyboard) {
                    // Keyboard dismissed via its own window event
                    isEditTextFocused = false
                    lastKeyboardTransitionTime = now
                    // Case 1 fix: the reposition scroll fires BEFORE this event due to event
                    // ordering — retroactively undo it if one slipped through within 500ms
                    if (now - lastScrollTime < 500 && _scrollCount.value > 0) {
                        _scrollCount.update { maxOf(0, it - 1) }
                        _scrollTimestamps.update { if (it.isNotEmpty()) it.dropLast(1) else it }
                        lastScrollTime = 0L
                    }
                } else if (isEditTextFocused && now - lastScrollTime < 300) {
                    // Case 1 fix (non-keyboard package path): keyboard is known to be open
                    // (EditText focused) and a window state change fired very close to a scroll.
                    // Covers dismissal via back-gesture or other paths that don't report as a
                    // keyboard package (e.g., the event comes from com.whatsapp instead of GBoard).
                    isEditTextFocused = false
                    lastKeyboardTransitionTime = now
                    _scrollCount.update { maxOf(0, it - 1) }
                    _scrollTimestamps.update { if (it.isNotEmpty()) it.dropLast(1) else it }
                    lastScrollTime = 0L
                }
                lastWindowStateChangeTime = now
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                lastTextChangeTime = now
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val pkg = event.packageName?.toString() ?: ""

                // A: Keyboard skip
                if (pkg.contains("inputmethod") || pkg.contains("keyboard") || pkg.contains("gboard")) return

                // B: Window-state shield — ignore scrolls within 500ms of a screen/keyboard transition
                if (now - lastWindowStateChangeTime < 500) return

                // B2: Keyboard-transition shield — forward guard for scrolls that fire AFTER
                //     the keyboard toggles (complements the retroactive undo in TYPE_WINDOW_STATE_CHANGED)
                if (now - lastKeyboardTransitionTime < 500) return

                // B3: EditText-focus shield — keyboard is in the process of opening;
                //     the content reposition scroll fires within ~200ms of the EditText gaining focus
                if (now - lastEditTextFocusTime < 800) return

                // C: Typing shield — ignore scrolls within 500ms of a text-change event
                if (now - lastTextChangeTime < 500) return

                // D: EditText skip — scrolling inside a text field is not a content scroll
                if (event.className?.contains("EditText") == true) return

                // E & F: Item-count cascade — a changing adapter count means programmatic scroll;
                //         F then debounces for 500ms after such a cascade
                val currentItemCount = event.itemCount
                if (lastItemCount != -1 && currentItemCount != lastItemCount) {
                    lastCascadeTime = now
                    lastItemCount = currentItemCount
                    return
                }
                lastItemCount = currentItemCount
                if (now - lastCascadeTime < 500) return

                // G: Continuous-motion grouper — fling frames within 400ms of the last counted
                //    scroll belong to the same swipe gesture; only the first event counts
                if (now - lastScrollTime < 400) return

                lastScrollTime = now
                _scrollCount.update { it + 1 }
                _scrollTimestamps.update { it + now }

                if (_isBubbleVisible.value) {
                    updateBubbleText()
                }
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

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
