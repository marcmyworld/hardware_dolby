/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.service

import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.lunaris.dolby.DolbyConstants
import org.lunaris.dolby.data.AppProfileManager
import org.lunaris.dolby.data.DolbyRepository

class DolbyNotificationListener : NotificationListenerService() {

    private lateinit var appProfileManager: AppProfileManager
    private lateinit var dolbyRepository: DolbyRepository
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val handler = Handler(Looper.getMainLooper())
    private var lastActivePackage: String? = null

    private val checkRoutingRunnable = Runnable {
        if (::dolbyRepository.isInitialized) {
            dolbyRepository.handleDeviceChange()
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            Log.i(TAG, "Devices added: ${addedDevices.map { it.productName }}")
            if (::dolbyRepository.isInitialized) {
                val addedSink = addedDevices.firstOrNull { it.isSink && !dolbyRepository.isBuiltinOutput(it.type) }
                if (addedSink != null) {
                    dolbyRepository.handleDeviceChange(addedSink, forceReapply = true)
                } else {
                    dolbyRepository.handleDeviceChange()
                }
                handler.removeCallbacks(checkRoutingRunnable)
                handler.postDelayed(checkRoutingRunnable, 500)
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            Log.i(TAG, "Devices removed: ${removedDevices.map { it.productName }}")
            if (::dolbyRepository.isInitialized) {
                dolbyRepository.handleDeviceChange()
                handler.removeCallbacks(checkRoutingRunnable)
                handler.postDelayed(checkRoutingRunnable, 500)
            }
        }
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            if (!::dolbyRepository.isInitialized) return
            val activeDevices = configs?.filter { it.isActive }
                ?.mapNotNull { it.audioDeviceInfo }
                ?.filter { it.isSink }
            val targetDevice = activeDevices?.firstOrNull { !dolbyRepository.isBuiltinOutput(it.type) }
                ?: activeDevices?.firstOrNull()
            if (targetDevice != null) {
                Log.i(TAG, "playbackConfigChanged: active playback device=${targetDevice.productName} (type=${targetDevice.type})")
                dolbyRepository.handleDeviceChange(targetDevice)
            } else if (configs?.any { it.isActive } == true) {
                dolbyRepository.handleDeviceChange(forceReapply = true)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        DolbyConstants.dlog(TAG, "NotificationListener created")
        appProfileManager = AppProfileManager(this)
        dolbyRepository = DolbyRepository.getInstance(this)
        initializeDolbySettings()
        startAppProfileMonitoringIfEnabled()

        try {
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler)
            audioManager.registerAudioPlaybackCallback(playbackCallback, handler)
            dolbyRepository.handleDeviceChange()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register audio callbacks: ${e.message}")
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        DolbyConstants.dlog(TAG, "NotificationListener connected")
        initializeDolbySettings()
        startAppProfileMonitoringIfEnabled()
        if (::dolbyRepository.isInitialized) {
            dolbyRepository.handleDeviceChange()
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        DolbyConstants.dlog(TAG, "NotificationListener disconnected")
        requestRebind(android.content.ComponentName(this, DolbyNotificationListener::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn?.packageName?.let { packageName ->
            if (packageName != lastActivePackage && packageName != this.packageName) {
                lastActivePackage = packageName
                handlePackageChange(packageName)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }

    private fun initializeDolbySettings() {
        try {
            if (dolbyRepository.getDolbyEnabled()) {
                DolbyEffectService.start(this)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Dolby service", e)
        }
    }

    private fun startAppProfileMonitoringIfEnabled() {
        val prefs = getSharedPreferences("dolby_prefs", MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("app_profile_monitoring_enabled", false)
        if (isEnabled) {
            DolbyConstants.dlog(TAG, "Starting app profile monitoring")
            AppProfileMonitorService.startMonitoring(this)
        }
    }

    private fun handlePackageChange(packageName: String) {
        val prefs = getSharedPreferences("dolby_prefs", MODE_PRIVATE)
        val isMonitoringEnabled = prefs.getBoolean("app_profile_monitoring_enabled", false)
        if (!isMonitoringEnabled) return
        try {
            val assignedProfile = appProfileManager.getAppProfile(packageName)
            if (assignedProfile >= 0) {
                DolbyConstants.dlog(TAG, "Package change detected: $packageName -> profile $assignedProfile")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling package change", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
            audioManager.unregisterAudioPlaybackCallback(playbackCallback)
            handler.removeCallbacksAndMessages(null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister audio callbacks: ${e.message}")
        }
        DolbyConstants.dlog(TAG, "NotificationListener destroyed")
    }

    companion object {
        private const val TAG = "DolbyNotificationListener"
    }
}
