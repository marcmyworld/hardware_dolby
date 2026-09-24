/*
 * SPDX-FileCopyrightText: 2026 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import org.lunaris.dolby.data.DolbyRepository

class DolbyEffectService : Service() {

    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var repository: DolbyRepository

    private val checkRoutingRunnable = Runnable {
        repository.handleDeviceChange()
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            Log.d(TAG, "Devices added: ${addedDevices.map { it.debugString() }}")
            val addedSink = addedDevices.firstOrNull { it.isSink }
            repository.handleDeviceChange(addedSink)
            handler.removeCallbacks(checkRoutingRunnable)
            handler.postDelayed(checkRoutingRunnable, 300)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            Log.d(TAG, "Devices removed: ${removedDevices.map { it.debugString() }}")
            repository.handleDeviceChange()
            handler.removeCallbacks(checkRoutingRunnable)
            handler.postDelayed(checkRoutingRunnable, 300)
        }
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            val isActive = configs?.any { it.isActive } == true
            if (isActive) {
                repository.handleDeviceChange(forceReapply = true)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = DolbyRepository.getInstance(this)
        repository.handleDeviceChange()

        audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler)
        audioManager.registerAudioPlaybackCallback(playbackCallback, handler)
        Log.d(TAG, "Dolby effect service created")
    }

    private fun AudioDeviceInfo.debugString(): String =
        "name=$productName,type=$type,id=$id,address=$address,isSink=$isSink"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (::repository.isInitialized) {
            repository.handleDeviceChange()
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "onTaskRemoved: restarting DolbyEffectService")
        val restartIntent = Intent(applicationContext, DolbyEffectService::class.java)
        restartIntent.setPackage(packageName)
        try {
            startService(restartIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart DolbyEffectService onTaskRemoved", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        audioManager.unregisterAudioPlaybackCallback(playbackCallback)
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Dolby effect service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "DolbyEffectService"

        fun start(context: Context) {
            val intent = Intent(context, DolbyEffectService::class.java)
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, DolbyEffectService::class.java)
            context.stopService(intent)
        }
    }
}
