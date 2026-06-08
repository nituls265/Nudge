package com.example.nudgev0

// In new file: AccessibilityServiceUtils.kt

import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import kotlin.io.path.name

fun isAccessibilityServiceEnabled(context: Context, service: Class<*>): Boolean {
    val serviceId = "${context.packageName}/${service.name}"
    try {
        val accessibilityEnabled = Settings.Secure.getInt(
            context.applicationContext.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED
        )
        if (accessibilityEnabled == 1) {
            val settingValue = Settings.Secure.getString(
                context.applicationContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            if (settingValue != null) {
                val splitter = TextUtils.SimpleStringSplitter(':')
                splitter.setString(settingValue)
                while (splitter.hasNext()) {
                    if (splitter.next().equals(serviceId, ignoreCase = true)) {
                        return true
                    }
                }
            }
        }
    } catch (e: Settings.SettingNotFoundException) {
        // This can happen, so we just log it and return false.
        e.printStackTrace()
    }
    return false
}