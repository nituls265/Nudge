package com.example.nudgev0

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.DisplayMetrics
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import com.example.nudgev0.data.AppScrollDay
import com.example.nudgev0.data.ScrollDatabase
import com.example.nudgev0.data.ScrollDay
import com.example.nudgev0.data.ScrollHour
import com.example.nudgev0.data.UnlockDay
import com.example.nudgev0.data.UnlockHour
import org.json.JSONObject
import java.util.Calendar
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MyAccessibilityService : AccessibilityService() {

    private var bubbleView: View? = null
    private lateinit var windowManager: WindowManager
    private var bubbleTextView: TextView? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentDayString: String = ""

    // Intervention state
    private var interventionScrollBase = 0
    private var cooldownJob: Job? = null
    private var pulseAnimator: ValueAnimator? = null

    // Scroll heuristic state
    private var lastSaveTime = 0L
    private var lastWindowStateChangeTime = 0L
    private var lastTextChangeTime = 0L
    private var lastProgrammaticScrollTime = 0L
    private var previousItemCount = -1
    private var lastScrollEventTime = 0L

    // Unlock tracking state
    private var lastUnlockSaveTime = 0L
    private val hourlyUnlockCounts = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_USER_PRESENT -> onPhoneUnlocked()
                Intent.ACTION_SCREEN_OFF   -> onScreenOff()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        currentDayString = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

        val prefs = getSharedPreferences("NudgePrefs", Context.MODE_PRIVATE)

        val lastSavedDate = prefs.getString("LAST_SCROLL_DATE", "") ?: ""
        val isStale = lastSavedDate.isNotEmpty() && lastSavedDate != currentDayString

        if (isStale) {
            prefs.edit()
                .putInt("CURRENT_SCROLL_COUNT", 0)
                .putInt("CURRENT_UNLOCK_COUNT", 0)
                .putLong("SESSION_START_MS", 0L)
                .putLong("TODAY_FIRST_UNLOCK_MS", 0L)
                .putLong("TODAY_LAST_UNLOCK_MS", 0L)
                .putLong("TODAY_TOTAL_SESSION_MS", 0L)
                .putInt("TODAY_COMPLETED_SESSIONS", 0)
                .putFloat("TODAY_AVG_SESSION_MIN", 0f)
                .putInt("TODAY_LONGEST_SESSION_MIN", 0)
                .putString("APP_SCROLL_COUNTS", "{}")
                .apply()
        }

        val savedScrollCount = if (isStale) 0 else prefs.getInt("CURRENT_SCROLL_COUNT", 0)
        _scrollCount.value = savedScrollCount

        if (!isStale) {
            val json = JSONObject(prefs.getString("APP_SCROLL_COUNTS", "{}") ?: "{}")
            val map = mutableMapOf<String, Int>()
            json.keys().forEach { key -> map[key] = json.getInt(key) }
            _appScrollCounts.value = map
        }

        _unlockCount.value        = if (isStale) 0 else prefs.getInt("CURRENT_UNLOCK_COUNT", 0)
        _firstUnlockMs.value      = if (isStale) 0L else prefs.getLong("TODAY_FIRST_UNLOCK_MS", 0L)
        _lastUnlockMs.value       = if (isStale) 0L else prefs.getLong("TODAY_LAST_UNLOCK_MS", 0L)
        _avgSessionMin.value      = prefs.getFloat("TODAY_AVG_SESSION_MIN", 0f)
        _longestSessionMin.value  = prefs.getInt("TODAY_LONGEST_SESSION_MIN", 0)

        serviceScope.launch(Dispatchers.IO) {
            try {
                val dao = ScrollDatabase.getDatabase(applicationContext)
                dao.scrollDao().getDay(currentDayString)?.let { today ->
                    if (today.count > savedScrollCount) _scrollCount.value = today.count
                }
                dao.scrollDao().getHoursForDate(currentDayString)
                    .forEach { hourlyScrollCounts[it.hour] = it.count }
                dao.unlockDao().getHoursForDate(currentDayString)
                    .forEach { hourlyUnlockCounts[it.hour] = it.count }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(unlockReceiver, filter)

        serviceScope.launch {
            isBubbleVisible.collect { if (it) showBubble() else removeBubble() }
        }

        // Observe intervention state: drive cooldown timer and bubble visuals
        serviceScope.launch {
            interventionState.collect { state ->
                when (state) {
                    InterventionState.Cooldown -> {
                        cooldownJob?.cancel()
                        cooldownJob = serviceScope.launch {
                            delay(45 * 60_000L)
                            _interventionState.value = InterventionState.Idle
                        }
                    }
                    else -> {
                        cooldownJob?.cancel()
                        cooldownJob = null
                    }
                }
                when (state) {
                    InterventionState.Level1 -> if (isBubbleVisible.value) startAmberPulse()
                    else -> stopAmberPulse()
                }
            }
        }
    }

    // ── Unlock event handlers ─────────────────────────────────────────────────

    private fun onPhoneUnlocked() {
        val now = System.currentTimeMillis()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

        if (currentDayString.isNotEmpty() && currentDayString != today) {
            resetUnlockCount()
            hourlyUnlockCounts.clear()
        }
        currentDayString = today

        val prefs = getSharedPreferences("NudgePrefs", Context.MODE_PRIVATE)

        val newCount = _unlockCount.value + 1
        _unlockCount.value = newCount

        prefs.edit().putLong("SESSION_START_MS", now).apply()

        if (_firstUnlockMs.value == 0L) {
            _firstUnlockMs.value = now
            prefs.edit().putLong("TODAY_FIRST_UNLOCK_MS", now).apply()
        }

        _lastUnlockMs.value = now
        prefs.edit()
            .putInt("CURRENT_UNLOCK_COUNT", newCount)
            .putLong("TODAY_LAST_UNLOCK_MS", now)
            .apply()

        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        hourlyUnlockCounts[hour] = (hourlyUnlockCounts[hour] ?: 0) + 1

        saveUnlockToDatabase(today)
    }

    private fun onScreenOff() {
        val prefs = getSharedPreferences("NudgePrefs", Context.MODE_PRIVATE)
        val sessionStart = prefs.getLong("SESSION_START_MS", 0L)
        if (sessionStart == 0L) return

        val durationMs   = System.currentTimeMillis() - sessionStart
        val durationMin  = (durationMs / 60_000f)

        val totalMs     = prefs.getLong("TODAY_TOTAL_SESSION_MS", 0L) + durationMs
        val completedSessions = prefs.getInt("TODAY_COMPLETED_SESSIONS", 0) + 1
        val newAvgMin   = (totalMs / completedSessions / 60_000f)

        val newLongest = maxOf(_longestSessionMin.value, durationMin.toInt())

        _avgSessionMin.value     = newAvgMin
        _longestSessionMin.value = newLongest

        prefs.edit()
            .putLong("SESSION_START_MS", 0L)
            .putLong("TODAY_TOTAL_SESSION_MS", totalMs)
            .putInt("TODAY_COMPLETED_SESSIONS", completedSessions)
            .putFloat("TODAY_AVG_SESSION_MIN", newAvgMin)
            .putInt("TODAY_LONGEST_SESSION_MIN", newLongest)
            .apply()

        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        saveUnlockToDatabase(today)
    }

    private fun saveUnlockToDatabase(date: String) {
        val now = System.currentTimeMillis()
        if (now - lastUnlockSaveTime < 2000) return
        lastUnlockSaveTime = now

        val snapshot = UnlockDay(
            date               = date,
            count              = _unlockCount.value,
            firstUnlockMs      = _firstUnlockMs.value,
            lastUnlockMs       = _lastUnlockMs.value,
            avgSessionMin      = _avgSessionMin.value,
            longestSessionMin  = _longestSessionMin.value
        )
        val hourSnapshot = hourlyUnlockCounts.toMap()

        serviceScope.launch(Dispatchers.IO) {
            try {
                val dao = ScrollDatabase.getDatabase(applicationContext).unlockDao()
                dao.insertOrUpdate(snapshot)
                hourSnapshot.forEach { (hour, count) ->
                    dao.insertOrUpdateHour(UnlockHour(date, hour, count))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ── Bubble UI ─────────────────────────────────────────────────────────────

    private fun showBubble() {
        if (bubbleView != null) return
        val inflater = LayoutInflater.from(this)
        bubbleView = inflater.inflate(R.layout.bubble_layout, null)
        bubbleTextView = bubbleView?.findViewById(R.id.bubble_text_view)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 300
        }

        bubbleView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0; private var initialY = 0
            private var initialTouchX = 0f; private var initialTouchY = 0f
            private var isDragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x; initialY = params.y
                        initialTouchX = event.rawX; initialTouchY = event.rawY
                        isDragging = false; return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX; val dy = event.rawY - initialTouchY
                        if (abs(dx) > 10 || abs(dy) > 10) isDragging = true
                        if (isDragging) {
                            params.x = initialX + dx.toInt(); params.y = initialY + dy.toInt()
                            windowManager.updateViewLayout(bubbleView, params)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> { if (isDragging) snapToEdge(params); return true }
                }
                return false
            }
        })

        try {
            windowManager.addView(bubbleView, params)
            updateBubbleText()
            if (_interventionState.value == InterventionState.Level1) startAmberPulse()
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun snapToEdge(params: WindowManager.LayoutParams) {
        val dm = DisplayMetrics().also { windowManager.defaultDisplay.getMetrics(it) }
        val targetX = if (params.x + (bubbleView?.width ?: 0) / 2 < dm.widthPixels / 2) 0
                      else dm.widthPixels - (bubbleView?.width ?: 0)
        ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 200; interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                params.x = animation.animatedValue as Int
                if (bubbleView?.isAttachedToWindow == true) windowManager.updateViewLayout(bubbleView, params)
            }
            start()
        }
    }

    private fun removeBubble() {
        stopAmberPulse()
        bubbleView?.let { view ->
            if (view.isAttachedToWindow) try { windowManager.removeView(view) } catch (e: IllegalArgumentException) { e.printStackTrace() }
            bubbleView = null; bubbleTextView = null
        }
    }

    private fun performHeartbeatAnimation(view: View) {
        view.animate().cancel()
        view.animate().scaleX(1.2f).scaleY(1.2f).setDuration(100).withEndAction {
            view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
        }.start()
    }

    private fun updateBubbleText() {
        bubbleTextView?.let { tv ->
            tv.text = _scrollCount.value.toString()
            bubbleView?.let { performHeartbeatAnimation(it) }
        }
    }

    private fun startAmberPulse() {
        bubbleTextView?.setTextColor(Color.parseColor("#FFAA00"))
        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofFloat(1f, 1.25f, 1f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener { anim ->
                val s = anim.animatedValue as Float
                bubbleView?.scaleX = s
                bubbleView?.scaleY = s
            }
            start()
        }
    }

    private fun stopAmberPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        bubbleView?.scaleX = 1f
        bubbleView?.scaleY = 1f
        bubbleTextView?.setTextColor(Color.WHITE)
    }

    // ── Intervention check ────────────────────────────────────────────────────

    private fun checkIntervention(count: Int) {
        // Only intervene if the user has opted in by enabling the bubble
        if (!_isBubbleVisible.value) return

        val velocity = _scrollTimestamps.value.size

        when (_interventionState.value) {
            InterventionState.Idle -> {
                if (velocity >= VELOCITY_THRESHOLD) {
                    _interventionState.value = InterventionState.Level1
                    interventionScrollBase = count
                    AnalyticsHelper.logInterventionTriggered(1)
                }
            }
            InterventionState.Level1 -> {
                if (count - interventionScrollBase >= ESCALATION_THRESHOLD) {
                    _interventionState.value = InterventionState.Level2
                    interventionScrollBase = count
                    NotificationHelper.sendLevel2Notification(applicationContext)
                    AnalyticsHelper.logInterventionTriggered(2)
                }
            }
            InterventionState.Level2 -> {
                if (count - interventionScrollBase >= ESCALATION_THRESHOLD) {
                    _interventionState.value = InterventionState.Level3
                    try {
                        val intent = Intent(applicationContext, InterventionActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        applicationContext.startActivity(intent)
                        AnalyticsHelper.logInterventionTriggered(3)
                    } catch (e: Exception) {
                        // Activity launch failed (background launch restriction on Android 12+)
                        // Fall back gracefully — stay at Level3 state without the overlay
                        e.printStackTrace()
                    }
                }
            }
            else -> {}
        }
    }

    // ── Scroll event handling ─────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (_isPaused.value || event == null) return
        val packageName = event.packageName?.toString() ?: ""
        val now = System.currentTimeMillis()

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastWindowStateChangeTime = now; return
        }
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            lastTextChangeTime = now; return
        }
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return

        if (packageName.contains("inputmethod") || packageName.contains("keyboard") || packageName.contains("gboard")) return
        if (now - lastWindowStateChangeTime < 500) return
        if (now - lastTextChangeTime < 500) return

        val className = event.className?.toString() ?: ""
        if (className.contains("EditText", ignoreCase = true)) return

        // API 28+: filter out zero-delta scroll events (programmatic reflows, auto-scroll, etc.)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            if (event.scrollDeltaX == 0 && event.scrollDeltaY == 0) return
        }

        // Pre-API 28: apply a stricter debounce for WebView (web pages auto-scroll constantly)
        val isWebView = className.contains("WebView", ignoreCase = true)
        if (isWebView && android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) {
            if (now - lastScrollEventTime < 800) return
        }

        val currentItemCount = event.itemCount
        if (currentItemCount > 0) {
            if (previousItemCount > 0 && currentItemCount != previousItemCount) {
                previousItemCount = currentItemCount; lastProgrammaticScrollTime = now; return
            }
            previousItemCount = currentItemCount
        }
        if (now - lastProgrammaticScrollTime < 500) return

        val timeSinceLastScroll = now - lastScrollEventTime
        lastScrollEventTime = now
        if (timeSinceLastScroll < 400) return

        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        if (currentDayString.isNotEmpty() && currentDayString != today) {
            val finalDate  = currentDayString
            val finalCount = _scrollCount.value
            val finalHours = hourlyScrollCounts.toMap()
            val finalApps  = _appScrollCounts.value.toMap()
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val db = ScrollDatabase.getDatabase(applicationContext)
                    db.scrollDao().insertOrUpdate(ScrollDay(finalDate, finalCount))
                    finalHours.forEach { (h, c) ->
                        db.scrollDao().insertOrUpdateHour(ScrollHour(finalDate, h, c))
                    }
                    finalApps.forEach { (pkg, c) ->
                        db.appScrollDao().insertOrUpdate(AppScrollDay(finalDate, pkg, c))
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
            resetScrollCount(); hourlyScrollCounts.clear()
        }
        currentDayString = today

        val newCount = _scrollCount.value + 1
        _scrollCount.value = newCount

        // Trim the rolling 5-min window and append current timestamp atomically
        _scrollTimestamps.update { list -> list.filter { it > now - 5 * 60_000L } + now }

        try { checkIntervention(newCount) } catch (e: Exception) { e.printStackTrace() }

        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        hourlyScrollCounts[currentHour] = (hourlyScrollCounts[currentHour] ?: 0) + 1

        val pkg = packageName.ifEmpty { "unknown" }
        _appScrollCounts.update { current ->
            current.toMutableMap().also { it[pkg] = (it[pkg] ?: 0) + 1 }
        }

        val appJson = JSONObject(_appScrollCounts.value as Map<*, *>).toString()
        getSharedPreferences("NudgePrefs", Context.MODE_PRIVATE)
            .edit()
            .putInt("CURRENT_SCROLL_COUNT", newCount)
            .putString("LAST_SCROLL_DATE", today)
            .putString("APP_SCROLL_COUNTS", appJson)
            .apply()

        if (_isBubbleVisible.value) updateBubbleText()
        saveScrollToDatabase(today, _scrollCount.value)
    }

    private fun saveScrollToDatabase(date: String, count: Int) {
        val now = System.currentTimeMillis()
        if (now - lastSaveTime < 2000) return
        lastSaveTime = now
        val hourSnapshot = hourlyScrollCounts.toMap()
        val appSnapshot  = _appScrollCounts.value.toMap()
        serviceScope.launch(Dispatchers.IO) {
            try {
                val db = ScrollDatabase.getDatabase(applicationContext)
                db.scrollDao().insertOrUpdate(ScrollDay(date, count))
                hourSnapshot.forEach { (hour, hourCount) ->
                    db.scrollDao().insertOrUpdateHour(ScrollHour(date, hour, hourCount))
                }
                appSnapshot.forEach { (pkg, appCount) ->
                    db.appScrollDao().insertOrUpdate(AppScrollDay(date, pkg, appCount))
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        cooldownJob?.cancel()
        try { unregisterReceiver(unlockReceiver) } catch (e: Exception) { e.printStackTrace() }
        serviceScope.cancel()
    }

    companion object {
        // Intervention thresholds
        private const val VELOCITY_THRESHOLD   = 100 // scrolls in 5 min to trigger Level 1
        private const val ESCALATION_THRESHOLD = 50  // additional scrolls per level escalation

        // Intervention state
        internal val _interventionState = MutableStateFlow<InterventionState>(InterventionState.Idle)
        val interventionState = _interventionState.asStateFlow()

        fun resetIntervention() {
            _interventionState.value = InterventionState.Idle
        }

        fun startInterventionCooldown() {
            _interventionState.value = InterventionState.Cooldown
        }

        // Scroll state
        private val _scrollCount = MutableStateFlow(0)
        val scrollCount = _scrollCount.asStateFlow()

        private val _scrollTimestamps = MutableStateFlow<List<Long>>(emptyList())
        val scrollTimestamps = _scrollTimestamps.asStateFlow()

        internal val hourlyScrollCounts = java.util.concurrent.ConcurrentHashMap<Int, Int>()

        // Unlock state
        private val _unlockCount       = MutableStateFlow(0)
        val unlockCount                = _unlockCount.asStateFlow()

        private val _firstUnlockMs     = MutableStateFlow(0L)
        val firstUnlockMs              = _firstUnlockMs.asStateFlow()

        private val _lastUnlockMs      = MutableStateFlow(0L)
        val lastUnlockMs               = _lastUnlockMs.asStateFlow()

        private val _avgSessionMin     = MutableStateFlow(0f)
        val avgSessionMin              = _avgSessionMin.asStateFlow()

        private val _longestSessionMin = MutableStateFlow(0)
        val longestSessionMin          = _longestSessionMin.asStateFlow()

        // Per-app scroll state
        internal val _appScrollCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
        val appScrollCounts = _appScrollCounts.asStateFlow()

        // Shared UI state
        private val _isBubbleVisible = MutableStateFlow(false)
        val isBubbleVisible          = _isBubbleVisible.asStateFlow()

        private val _isPaused = MutableStateFlow(false)
        val isPaused          = _isPaused.asStateFlow()

        fun togglePause()            { _isPaused.update { !it } }
        fun toggleBubbleVisibility() { _isBubbleVisible.update { !it } }

        fun resetScrollCount() {
            _scrollCount.value = 0
            _scrollTimestamps.value = emptyList()
            hourlyScrollCounts.clear()
            _appScrollCounts.value = emptyMap()
            _interventionState.value = InterventionState.Idle
        }

        fun resetUnlockCount() {
            _unlockCount.value       = 0
            _firstUnlockMs.value     = 0L
            _lastUnlockMs.value      = 0L
            _avgSessionMin.value     = 0f
            _longestSessionMin.value = 0
        }
    }
}
