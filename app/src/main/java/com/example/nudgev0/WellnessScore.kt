package com.example.nudgev0

import java.util.Calendar

// ── Tier ─────────────────────────────────────────────────────────────────────

enum class WellnessTier(
    val emoji: String,
    val label: String,
    val colorHex: Long   // ARGB for Color(Long)
) {
    MINDFUL   ("🌿", "Mindful",   0xFF34D399),
    BALANCED  ("✨", "Balanced",  0xFF60A5FA),
    DRIFTING  ("🌊", "Drifting",  0xFFFBBF24),
    HEAVY_USE ("⚡", "Heavy Use", 0xFFF97316),
    OVERLOADED("🔴", "Overloaded",0xFFEF4444);

    companion object {
        fun from(score: Int) = when {
            score >= 85 -> MINDFUL
            score >= 70 -> BALANCED
            score >= 50 -> DRIFTING
            score >= 30 -> HEAVY_USE
            else        -> OVERLOADED
        }
    }
}

// ── Score data ────────────────────────────────────────────────────────────────

data class WellnessScore(
    val total: Int,                    // 0–100
    val tier: WellnessTier,
    val scrollVolume: Int,             // 0–30
    val sessionBehaviour: Int,         // 0–20
    val unlockFrequency: Int,          // 0–15
    val timeHygiene: Int,              // 0–20
    val appQuality: Int,               // 0–15
    val flaggedApps: List<String>,     // display names of risky apps in the top-3 sources
    val todayScrolls: Int,             // total scrolls used to compute scroll volume
    val baselineScrolls: Int,          // 7-day avg used as reference (0 = calibrating)
    val bedtimeScore: Int = -1,        // 0–10 — Time Hygiene sub-component (-1 = unavailable)
    val gapScore: Int = -1,            // 0–6  — Time Hygiene sub-component (-1 = unavailable)
    val consistencyScore: Int = -1     // 0–4  — Time Hygiene sub-component (-1 = unavailable)
)

// ── Risky app registry ────────────────────────────────────────────────────────

object RiskyApps {
    /** Package names that lower App Quality score */
    val packages: Set<String> = setOf(
        "com.zhiliaoapp.musically",       // TikTok (global)
        "com.ss.android.ugc.trill",       // TikTok (some regions)
        "com.instagram.android",          // Instagram (Reels)
        "com.twitter.android",            // Twitter / X
        "com.x.android",                  // X (new package)
        "com.reddit.frontpage",           // Reddit
        "com.snapchat.android"            // Snapchat
    )

    /** Human-readable display names */
    val displayNames: Map<String, String> = mapOf(
        "com.zhiliaoapp.musically"  to "TikTok",
        "com.ss.android.ugc.trill" to "TikTok",
        "com.instagram.android"    to "Instagram",
        "com.twitter.android"      to "Twitter / X",
        "com.x.android"            to "Twitter / X",
        "com.reddit.frontpage"     to "Reddit",
        "com.snapchat.android"     to "Snapchat"
    )

    /** Short-form video apps get 0 pts; others get 4 (passive social) */
    val shortFormVideo: Set<String> = setOf(
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill"
    )

    /** Productive apps: give max app-quality points */
    private val productivePrefixes = listOf(
        "com.google.android.gm",          // Gmail
        "com.google.android.apps.docs",   // Google Docs
        "com.google.android.apps.sheets", // Sheets
        "com.google.android.apps.slides", // Slides
        "com.microsoft.office",           // Office suite
        "com.microsoft.teams",            // Teams
        "com.slack",                       // Slack
        "com.notion",                      // Notion
        "com.linkedin.android"            // LinkedIn
    )

    fun classify(pkg: String): AppCategory = when {
        pkg.isEmpty() || pkg == "unknown" -> AppCategory.MIXED
        pkg == "laptop"                   -> AppCategory.LONG_FORM
        packages.contains(pkg) && shortFormVideo.contains(pkg) -> AppCategory.SHORT_FORM
        packages.contains(pkg)            -> AppCategory.PASSIVE_SOCIAL
        productivePrefixes.any { pkg.startsWith(it) }          -> AppCategory.PRODUCTIVE
        pkg == "com.google.android.youtube"                     -> AppCategory.PASSIVE_SOCIAL
        else                              -> AppCategory.MIXED
    }
}

enum class AppCategory(val pts: Int, val label: String) {
    PRODUCTIVE    (15, "Productive"),
    LONG_FORM     (12, "Long-form"),
    MIXED         ( 8, "Mixed"),
    PASSIVE_SOCIAL( 4, "Passive social"),
    SHORT_FORM    ( 0, "Short-form video")
}

// ── Calculator ────────────────────────────────────────────────────────────────

object WellnessCalculator {

    /**
     * @param todayScrolls      combined phone + laptop count for today
     * @param sevenDayAvg       average daily scrolls over the past 7 days (excl. today)
     * @param unlockCount       phone unlocks today
     * @param avgSessionMin     average phone session length in minutes
     * @param longestSessionMin longest single session today in minutes
     * @param firstUnlockMs     epoch ms of first unlock; 0 if none
     * @param lastUnlockMs      epoch ms of last unlock; 0 if none
     * @param topApps           top scroll sources (pkg → count), sorted descending
     * @param previousDayLastUnlockMs epoch ms of the PREVIOUS day's last unlock; 0 if
     *   unknown (e.g. first tracked day). Used to measure the overnight gap for the
     *   morning-hygiene component instead of the literal wall-clock hour.
     * @param personalAvgFirstUnlockMinute the user's own rolling-average first-unlock
     *   time, as minutes since midnight (e.g. 6:15 AM = 375); null if not enough
     *   history yet. Used to reward a consistent daily rhythm — see [averageFirstUnlockMinute].
     */
    fun calculate(
        todayScrolls: Int,
        sevenDayAvg: Float,
        unlockCount: Int,
        avgSessionMin: Float,
        longestSessionMin: Int,
        firstUnlockMs: Long,
        lastUnlockMs: Long,
        topApps: List<Pair<String, Int>>,
        previousDayLastUnlockMs: Long = 0L,
        personalAvgFirstUnlockMinute: Int? = null
    ): WellnessScore {

        // ── A: Scroll Volume (0–30) ───────────────────────────────────────────
        // Relative scoring only makes sense once there is a real baseline.
        // If the 7-day avg is below 30 the history is too sparse (new install,
        // manually-edited days, or genuinely very light past usage) — fall back
        // to the neutral "right on average" score so sparse data never gives 0.
        val scrollVolume = if (sevenDayAvg < 30f) {
            25  // not enough baseline yet → neutral
        } else {
            val pct = todayScrolls.toFloat() / sevenDayAvg
            when {
                pct <= 0.70f -> 30
                pct <= 1.00f -> 25
                pct <= 1.30f -> 15
                pct <= 1.70f ->  8
                else         ->  0
            }
        }

        // ── B: Session Behaviour (0–20) ───────────────────────────────────────
        val avgPts = when {
            avgSessionMin < 5f  -> 10
            avgSessionMin < 10f ->  7
            avgSessionMin < 20f ->  4
            else                ->  0
        }
        val longPts = when {
            longestSessionMin < 15 -> 10
            longestSessionMin < 30 ->  7
            longestSessionMin < 45 ->  4
            else                   ->  0
        }
        val sessionBehaviour = avgPts + longPts

        // ── C: Unlock Frequency (0–15) ────────────────────────────────────────
        val unlockFrequency = when {
            unlockCount < 25 -> 15
            unlockCount < 45 -> 10
            unlockCount < 65 ->  5
            else             ->  0
        }

        // ── D: Time Hygiene (0–20) ────────────────────────────────────────────
        val lastHour = if (lastUnlockMs > 0L)
            Calendar.getInstance().apply { timeInMillis = lastUnlockMs }.get(Calendar.HOUR_OF_DAY)
        else -1

        // Graduated bedtime hygiene:
        //   6–21  (6 AM – 9:59 PM) → full 10 pts  (healthy window)
        //   22–23 (10 PM – 11:59 PM) → 5 pts        (a little late)
        //   0–5   (midnight – 5:59 AM) → 0 pts      (scrolling past midnight)
        //   -1    (no unlock yet today) → 10 pts     (still early, full credit)
        val bedtimePts = when {
            lastHour < 0         -> 10   // no unlock yet — not penalised
            lastHour in 6..21    -> 10
            lastHour in 22..23   ->  5
            else                 ->  0   // 0–5 AM (after midnight)
        }

        // Morning hygiene is two independent 0–10-split signals, not one:
        //   • GAP (0–6): how long the phone went untouched between yesterday's last
        //     unlock and today's first — a rest/sleep-duration proxy. A wall-clock
        //     cutoff can't tell "checked at 6 AM after sleeping since 9 PM" (a long
        //     healthy gap) apart from "checked at 6 AM after being up until 5 AM"
        //     (no gap at all) — both have the same first-unlock hour. It also can't
        //     tell a 6 AM unlock might be to start a morning meditation session, not
        //     doom-scrolling. Measuring the gap scores the actual rest, regardless of
        //     what hour it happens to start or end at.
        //   • CONSISTENCY (0–4): how close today's first unlock is to the user's OWN
        //     rolling-average first-unlock time — a routine/rhythm proxy, independent
        //     of rest. Only checking EARLIER than your personal norm costs points;
        //     checking later (sleeping in, or just going longer without the phone)
        //     is always fine or better, never penalised.
        // Each half degrades gracefully to full credit on its own when the data it
        // needs isn't available yet, rather than one shared all-or-nothing gate.
        val gapPts = when {
            firstUnlockMs <= 0L           -> 6   // no unlock yet today — not penalised
            previousDayLastUnlockMs <= 0L -> 6   // no prior-day data — don't penalise
            else -> {
                val gapHours = (firstUnlockMs - previousDayLastUnlockMs) / 3_600_000f
                when {
                    gapHours >= 8f -> 6
                    gapHours >= 6f -> 4
                    gapHours >= 4f -> 2
                    else           -> 0
                }
            }
        }
        val consistencyPts = when {
            firstUnlockMs <= 0L                  -> 4   // no unlock yet today — not penalised
            personalAvgFirstUnlockMinute == null -> 4   // baseline still calibrating — don't penalise
            else -> {
                val todayMinute = Calendar.getInstance().apply { timeInMillis = firstUnlockMs }
                    .let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
                val deviation = todayMinute - personalAvgFirstUnlockMinute   // negative = earlier than usual
                when {
                    deviation >= -15 -> 4   // on time, or later — always fine
                    deviation >= -60 -> 2   // somewhat earlier than usual
                    else              -> 0   // much earlier than usual
                }
            }
        }
        val morningPts   = gapPts + consistencyPts
        val timeHygiene  = bedtimePts + morningPts

        // ── E: App Quality (0–15) ─────────────────────────────────────────────
        // Judge based on the single top scroll source.
        // If no scrolls have happened yet today, give full credit rather than
        // defaulting to MIXED (8 pts) just because topApps is empty.
        val topPkg = topApps.firstOrNull()?.first ?: ""
        val appQuality = if (topPkg.isEmpty()) 15 else RiskyApps.classify(topPkg).pts

        // Collect flagged app names from top-5 sources
        val flaggedApps = topApps.take(5)
            .map { it.first }
            .filter { RiskyApps.packages.contains(it) }
            .mapNotNull { RiskyApps.displayNames[it] }
            .distinct()

        val total = (scrollVolume + sessionBehaviour + unlockFrequency + timeHygiene + appQuality)
            .coerceIn(0, 100)
        val tier  = WellnessTier.from(total)

        return WellnessScore(
            total            = total,
            tier             = tier,
            scrollVolume     = scrollVolume,
            sessionBehaviour = sessionBehaviour,
            unlockFrequency  = unlockFrequency,
            timeHygiene      = timeHygiene,
            appQuality       = appQuality,
            flaggedApps      = flaggedApps,
            todayScrolls     = todayScrolls,
            baselineScrolls  = sevenDayAvg.toInt(),
            bedtimeScore     = bedtimePts,
            gapScore         = gapPts,
            consistencyScore = consistencyPts
        )
    }

    /**
     * The user's personal baseline first-unlock time, as minutes since midnight —
     * feeds [calculate]'s `personalAvgFirstUnlockMinute` param.
     *
     * @param firstUnlockTimestamps epoch-ms first-unlock times from PAST days only
     *   (caller excludes the day being scored and any zero-unlock days — a day with
     *   no unlock has no time-of-day to average in, and including it as midnight
     *   would corrupt the baseline).
     * @return null if fewer than 3 data points — not enough history to be meaningful,
     *   so callers should treat that as "still calibrating" and not penalise.
     */
    fun averageFirstUnlockMinute(firstUnlockTimestamps: List<Long>): Int? {
        if (firstUnlockTimestamps.size < 3) return null
        val minutesOfDay = firstUnlockTimestamps.map {
            Calendar.getInstance().apply { timeInMillis = it }
                .let { c -> c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) }
        }
        return minutesOfDay.average().toInt()
    }
}
