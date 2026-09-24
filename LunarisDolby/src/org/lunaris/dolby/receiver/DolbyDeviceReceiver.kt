/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import org.lunaris.dolby.DolbyConstants
import org.lunaris.dolby.data.DolbyRepository
import org.lunaris.dolby.service.DolbyEffectService

class DolbyDeviceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        DolbyConstants.dlog(TAG, "Received broadcast: $action")

        val repository = DolbyRepository.getInstance(context)
        try {
            DolbyEffectService.start(context)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Failed to start DolbyEffectService from receiver: ${e.message}")
        }

        // Handle immediate device routing update
        repository.handleDeviceChange()

        // Also schedule a delayed check as routing negotiation (especially A2DP/LE) may take 200-500ms
        val pendingResult = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                repository.handleDeviceChange()
            } finally {
                pendingResult.finish()
            }
        }, 500)
    }

    companion object {
        private const val TAG = "DolbyDeviceReceiver"
    }
}
