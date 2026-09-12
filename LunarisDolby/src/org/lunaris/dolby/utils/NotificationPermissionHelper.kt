/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.utils

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import org.lunaris.dolby.DolbyConstants
import org.lunaris.dolby.service.DolbyNotificationListener

object NotificationPermissionHelper {

    private const val TAG = "NotificationPermissionHelper"

    fun isNotificationListenerEnabled(context: Context): Boolean {
        return try {
            val cn = ComponentName(context, DolbyNotificationListener::class.java)
            val flat = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_NOTIFICATION_LISTENERS)
            flat?.contains(cn.flattenToString()) == true
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error checking notification listener: ${e.message}")
            false
        }
    }

    fun ensureNotificationListenerEnabled(context: Context): Boolean {
        return try {
            val cn = ComponentName(context, DolbyNotificationListener::class.java)
            val flat = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_NOTIFICATION_LISTENERS)
            val componentStr = cn.flattenToString()
            if (flat == null || !flat.contains(componentStr)) {
                val newFlat = if (flat.isNullOrEmpty()) componentStr else "$flat:$componentStr"
                Settings.Secure.putString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_NOTIFICATION_LISTENERS,
                    newFlat
                )
                DolbyConstants.dlog(TAG, "Auto-enabled notification listener: $newFlat")
            }
            true
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Failed to auto-enable notification listener: ${e.message}")
            false
        }
    }
}
