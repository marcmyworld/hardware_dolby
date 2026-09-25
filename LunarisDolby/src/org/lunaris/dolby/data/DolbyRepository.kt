/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.data

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import org.lunaris.dolby.DolbyConstants
import org.lunaris.dolby.DolbyConstants.DsParam
import org.lunaris.dolby.R
import org.lunaris.dolby.audio.DolbyAudioEffect
import org.lunaris.dolby.domain.models.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DolbyRepository private constructor(private val context: Context) : AutoCloseable {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val defaultPrefs = context.getSharedPreferences("dolby_prefs", Context.MODE_PRIVATE)
    private val presetsPrefs = context.getSharedPreferences(DolbyConstants.PREF_FILE_PRESETS, Context.MODE_PRIVATE)
    private val deviceStateManager = DeviceStateManager(context)
    
    private var dolbyEffect = createDolbyEffect().also {
        val enabled = defaultPrefs.getBoolean(DolbyConstants.PREF_ENABLE, false)
        it.dsOn = enabled
    }
    
    private val _isDolbyEnabled = MutableStateFlow(
        defaultPrefs.getBoolean(DolbyConstants.PREF_ENABLE, false)
    )
    val isDolbyEnabled: StateFlow<Boolean> = _isDolbyEnabled.asStateFlow()
    
    private val _isOnSpeaker = MutableStateFlow(checkIsOnSpeaker())
    val isOnSpeaker: StateFlow<Boolean> = _isOnSpeaker.asStateFlow()
    
    private val _currentProfile = MutableStateFlow(
        defaultPrefs.getString(DolbyConstants.PREF_PROFILE, "0")?.toIntOrNull() ?: 0
    )
    val currentProfile: StateFlow<Int> = _currentProfile.asStateFlow()

    val stereoWideningSupported = context.resources.getBoolean(R.bool.dolby_stereo_widening_supported)
    val volumeLevelerSupported = context.resources.getBoolean(R.bool.dolby_volume_leveler_supported)
    
    private var cachedPresets: List<EqualizerPreset>? = null
    private val presetCacheLock = Any()

    val isDeviceStateMemoryEnabled: Boolean
        get() = defaultPrefs.getBoolean(DolbyConstants.PREF_DEVICE_STATE_MEMORY, true)

    init {
        migrateVolumeLevelerDefault()
        migrateDeviceStateMemoryDefault()
    }

    private fun migrateVolumeLevelerDefault() {
        if (!defaultPrefs.getBoolean("pref_volume_default_migrated_v3", false)) {
            try {
                val profileValues = context.resources.getStringArray(R.array.dolby_profile_values)
                for (pStr in profileValues) {
                    val p = pStr.toIntOrNull() ?: continue
                    val pPrefs = getProfilePrefs(p)
                    pPrefs.edit().putBoolean(DolbyConstants.PREF_VOLUME, false).apply()
                }
                defaultPrefs.edit().putBoolean("pref_volume_default_migrated_v3", true).apply()
                DolbyConstants.dlog(TAG, "Migrated default volume leveler to disabled")
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Failed to migrate volume leveler defaults: ${e.message}")
            }
        }
    }

    private fun migrateDeviceStateMemoryDefault() {
        if (!defaultPrefs.getBoolean("pref_device_state_memory_migrated_v1", false)) {
            try {
                defaultPrefs.edit()
                    .putBoolean(DolbyConstants.PREF_DEVICE_STATE_MEMORY, true)
                    .putBoolean("pref_device_state_memory_migrated_v1", true)
                    .apply()
                DolbyConstants.dlog(TAG, "Migrated default device state memory to enabled")
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Failed to migrate device state memory default: ${e.message}")
            }
        }
    }

    private fun createDolbyEffect(): DolbyAudioEffect {
        return try {
            DolbyAudioEffect(EFFECT_PRIORITY, audioSession = 0)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Failed to create Dolby effect: ${e.message}")
            throw e
        }
    }

    private fun checkEffect() {
        try {
            if (!dolbyEffect.hasControl()) {
                DolbyConstants.dlog(TAG, "Lost audio effect control, recreating")
                try {
                    dolbyEffect.release()
                } catch (e: Exception) {
                    DolbyConstants.dlog(TAG, "Error releasing effect: ${e.message}")
                }
                dolbyEffect = createDolbyEffect()
                val enabled = defaultPrefs.getBoolean(DolbyConstants.PREF_ENABLE, false)
                dolbyEffect.dsOn = enabled
                if (enabled) {
                    if (isDeviceStateMemoryEnabled) {
                        val restored = restoreCurrentDeviceSnapshot()
                        if (!restored) {
                            restoreSavedProfileIfNeeded()
                        }
                    } else {
                        restoreSavedProfileIfNeeded()
                    }
                }
            }
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error checking effect: ${e.message}")
        }
    }

    private fun readSavedProfile(): Int? {
        return defaultPrefs.getString(DolbyConstants.PREF_PROFILE, null)
            ?.toIntOrNull()
    }

    private fun restoreSavedProfileIfNeeded() {
        val savedProfile = readSavedProfile() ?: 0
        try {
            dolbyEffect.profile = savedProfile
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Failed to set profile: ${e.message}")
        }
        restoreProfilePreset(savedProfile)
        applyProfileSettings(savedProfile)
    }

    private fun applyProfileSettings(profile: Int) {
        try {
            val prefs = getProfilePrefs(profile)
            
            if (prefs.contains(DolbyConstants.PREF_IEQ)) {
                val ieqPreset = prefs.getString(DolbyConstants.PREF_IEQ, "0")?.toIntOrNull() ?: 0
                dolbyEffect.setDapParameter(DsParam.IEQ_PRESET, ieqPreset, profile)
            }
            
            if (prefs.contains(DolbyConstants.PREF_HP_VIRTUALIZER)) {
                val hpVirtualizer = prefs.getBoolean(DolbyConstants.PREF_HP_VIRTUALIZER, false)
                dolbyEffect.setDapParameter(DsParam.HEADPHONE_VIRTUALIZER, hpVirtualizer, profile)
            }
            
            if (prefs.contains(DolbyConstants.PREF_SPK_VIRTUALIZER)) {
                val spkVirtualizer = prefs.getBoolean(DolbyConstants.PREF_SPK_VIRTUALIZER, false)
                dolbyEffect.setDapParameter(DsParam.SPEAKER_VIRTUALIZER, spkVirtualizer, profile)
            }
            
            if (stereoWideningSupported && prefs.contains(DolbyConstants.PREF_STEREO_WIDENING)) {
                val stereoWidening = prefs.getInt(DolbyConstants.PREF_STEREO_WIDENING, 32)
                dolbyEffect.setDapParameter(DsParam.STEREO_WIDENING_AMOUNT, stereoWidening, profile)
            }
            
            if (prefs.contains(DolbyConstants.PREF_DIALOGUE)) {
                val dialogueEnabled = prefs.getBoolean(DolbyConstants.PREF_DIALOGUE, false)
                dolbyEffect.setDapParameter(DsParam.DIALOGUE_ENHANCER_ENABLE, dialogueEnabled, profile)
            }
            
            if (prefs.contains(DolbyConstants.PREF_DIALOGUE_AMOUNT)) {
                val dialogueAmount = prefs.getInt(DolbyConstants.PREF_DIALOGUE_AMOUNT, 6)
                dolbyEffect.setDapParameter(DsParam.DIALOGUE_ENHANCER_AMOUNT, dialogueAmount, profile)
            }
            
            if (prefs.contains(DolbyConstants.PREF_BASS)) {
                val bassEnabled = prefs.getBoolean(DolbyConstants.PREF_BASS, false)
                dolbyEffect.setDapParameter(DsParam.BASS_ENHANCER_ENABLE, bassEnabled, profile)
            }
            
            if (volumeLevelerSupported) {
                val volumeLeveler = prefs.getBoolean(DolbyConstants.PREF_VOLUME, false)
                dolbyEffect.setDapParameter(DsParam.VOLUME_LEVELER_ENABLE, volumeLeveler, profile)
            }
            
            DolbyConstants.dlog(TAG, "Successfully applied settings for profile $profile")
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Failed to restore profile settings: ${e.message}")
        }
    }

    @Volatile
    private var cachedActiveDeviceKey: String? = null

    var lastActiveDeviceKey: String?
        get() {
            if (cachedActiveDeviceKey == null) {
                cachedActiveDeviceKey = defaultPrefs.getString(DolbyConstants.PREF_LAST_ACTIVE_DEVICE, null)
            }
            return cachedActiveDeviceKey
        }
        set(value) {
            cachedActiveDeviceKey = value
            try {
                if (value != null) {
                    defaultPrefs.edit().putString(DolbyConstants.PREF_LAST_ACTIVE_DEVICE, value).apply()
                } else {
                    defaultPrefs.edit().remove(DolbyConstants.PREF_LAST_ACTIVE_DEVICE).apply()
                }
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Failed to persist lastActiveDeviceKey: ${e.message}")
            }
        }

    fun isBuiltinOutput(type: Int): Boolean = deviceStateManager.isBuiltinOutput(type)

    fun getActiveOutputDevice(): AudioDeviceInfo? {
        val outputs: Array<AudioDeviceInfo> = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        } catch (e: Exception) {
            emptyArray()
        }

        val routedDevice = try {
            audioManager.getDevicesForAttributes(ATTRIBUTES_MEDIA).firstOrNull()
        } catch (e: Exception) {
            Log.d(TAG, "Failed to get active media route: ${e.message}")
            null
        }

        // If media routing reports an external sink, map to connected output device
        if (routedDevice != null && !isBuiltinOutput(routedDevice.type)) {
            val routedAddress = routedDevice.address.orEmpty()
            val match = outputs.firstOrNull { device ->
                device.type == routedDevice.type &&
                    (routedAddress.isEmpty() || device.address == routedAddress)
            } ?: outputs.firstOrNull { device ->
                device.type == routedDevice.type
            }
            if (match != null) return match
        }

        // When media playback is paused or idle, AOSP getDevicesForAttributes(ATTRIBUTES_MEDIA)
        // frequently reports speaker/earpiece or null because no AudioTrack is actively streaming.
        // If a previously active external device is still physically connected, preserve it!
        val lastKey = lastActiveDeviceKey
        if (!lastKey.isNullOrEmpty() && lastKey != "builtin_speaker") {
            val matchingConnected = outputs.firstOrNull { device ->
                device.isSink && !isBuiltinOutput(device.type) && deviceStateManager.deviceKey(device) == lastKey
            }
            if (matchingConnected != null) {
                return matchingConnected
            }
        }

        // If no prior key or previous device disconnected, check for any connected external sink by priority:
        // Wired > USB > Bluetooth
        val wiredDevice = outputs.firstOrNull {
            it.isSink && (it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_LINE_ANALOG ||
                it.type == AudioDeviceInfo.TYPE_LINE_DIGITAL ||
                it.type == AudioDeviceInfo.TYPE_AUX_LINE)
        }
        if (wiredDevice != null) return wiredDevice

        val usbDevice = outputs.firstOrNull {
            it.isSink && (it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY)
        }
        if (usbDevice != null) return usbDevice

        val bt = outputs.firstOrNull {
            it.isSink && (it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                it.type == AudioDeviceInfo.TYPE_BLE_BROADCAST ||
                it.type == AudioDeviceInfo.TYPE_HEARING_AID ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        }
        if (bt != null) return bt

        // Only fall back to speaker if no external audio devices exist
        return outputs.firstOrNull { it.isSink && it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: outputs.firstOrNull { it.isSink && isBuiltinOutput(it.type) }
    }

    fun getCurrentOutputDevice(): AudioDeviceInfo? = getActiveOutputDevice()

    fun getActiveDeviceKey(): String {
        val dev = getActiveOutputDevice()
        return if (dev != null) deviceStateManager.deviceKey(dev) else "builtin_speaker"
    }

    fun handleDeviceChange(preferredDevice: AudioDeviceInfo? = null, forceReapply: Boolean = false): Boolean {
        synchronized(this) {
            val currentDevice = preferredDevice ?: getActiveOutputDevice()
            val currentKey = if (currentDevice != null) {
                deviceStateManager.deviceKey(currentDevice)
            } else {
                "builtin_speaker"
            }

            val previousKey = lastActiveDeviceKey

            if (!forceReapply && previousKey != null && previousKey == currentKey) {
                Log.d(TAG, "Device unchanged: $currentKey")
                return false
            }

            Log.i(TAG, "Device routing changed: previous=$previousKey, current=$currentKey, forceReapply=$forceReapply")

            if (previousKey != null && previousKey != currentKey && isDeviceStateMemoryEnabled && getDolbyEnabled()) {
                Log.i(TAG, "Saving snapshot for previous device: $previousKey")
                deviceStateManager.saveSnapshot(previousKey, this, force = true)
            }

            lastActiveDeviceKey = currentKey

            if (isDeviceStateMemoryEnabled && getDolbyEnabled()) {
                val isHeadphone = currentDevice?.let { deviceStateManager.isHeadphone(it) } ?: false
                if (deviceStateManager.hasSnapshot(currentKey)) {
                    Log.i(TAG, "Restoring snapshot for device: $currentKey")
                    deviceStateManager.restoreSnapshot(currentKey, this)
                } else {
                    Log.i(TAG, "No snapshot for $currentKey, initializing clean defaults (isHeadphone=$isHeadphone)")
                    deviceStateManager.initDefaultSnapshot(currentKey, this, isHeadphone)
                    deviceStateManager.restoreSnapshot(currentKey, this)
                }
            } else {
                restoreSavedProfileIfNeeded()
            }

            updateSpeakerState()
            return true
        }
    }

    fun saveCurrentDeviceSnapshot() {
        try {
            if (!isDeviceStateMemoryEnabled) return
            val key = getActiveDeviceKey()
            deviceStateManager.saveSnapshot(key, this, force = true)
            Log.i(TAG, "Saved snapshot for current device: $key")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving current device snapshot: ${e.message}")
        }
    }

    fun restoreCurrentDeviceSnapshot(): Boolean {
        return try {
            if (!isDeviceStateMemoryEnabled) return false
            val key = getActiveDeviceKey()
            lastActiveDeviceKey = key
            if (!deviceStateManager.hasSnapshot(key)) {
                Log.i(TAG, "No snapshot exists for current device: $key")
                return false
            }
            val restored = deviceStateManager.restoreSnapshot(key, this)
            Log.i(TAG, "Restored snapshot for current device $key: $restored")
            restored
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring current device snapshot: ${e.message}")
            false
        }
    }

    fun applySavedState() {
        checkEffect()
        val enabled = defaultPrefs.getBoolean(DolbyConstants.PREF_ENABLE, false)
        dolbyEffect.dsOn = enabled
        _isDolbyEnabled.value = enabled
        if (enabled) {
            if (isDeviceStateMemoryEnabled) {
                handleDeviceChange(forceReapply = true)
            } else {
                restoreSavedProfileIfNeeded()
            }
        }
    }

    private fun checkIsOnSpeaker(): Boolean {
        val dev = getActiveOutputDevice()
        return dev == null || isBuiltinOutput(dev.type)
    }

    fun updateSpeakerState() {
        _isOnSpeaker.value = checkIsOnSpeaker()
    }

    fun getDolbyEnabled(): Boolean {
        return defaultPrefs.getBoolean(DolbyConstants.PREF_ENABLE, false)
    }

    fun setDolbyEnabled(enabled: Boolean) {
        try {
            if (!enabled && isDeviceStateMemoryEnabled) {
                saveCurrentDeviceSnapshot()
            }
            checkEffect()
            dolbyEffect.dsOn = enabled
            defaultPrefs.edit().putBoolean(DolbyConstants.PREF_ENABLE, enabled).apply()
            _isDolbyEnabled.value = enabled
            if (enabled) {
                if (isDeviceStateMemoryEnabled) {
                    handleDeviceChange()
                } else {
                    restoreSavedProfileIfNeeded()
                }
            }
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting Dolby enabled: ${e.message}")
        }
    }

    fun getCurrentProfile(): Int {
        return try {
            readSavedProfile() ?: 0
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error getting current profile: ${e.message}")
            0
        }
    }

    fun setCurrentProfile(profile: Int, saveDeviceSnapshot: Boolean = true) {
        try {
            checkEffect()
            dolbyEffect.profile = profile
            defaultPrefs.edit().putString(DolbyConstants.PREF_PROFILE, profile.toString()).apply()
            if (!verifyProfileSaved(profile)) {
                DolbyConstants.dlog(TAG, "WARNING: Profile may not have been saved correctly!")
            }
            restoreProfilePreset(profile)
            applyProfileSettings(profile)
            _currentProfile.value = profile
            DolbyConstants.dlog(TAG, "Profile set to: $profile")
            if (saveDeviceSnapshot && isDeviceStateMemoryEnabled && getDolbyEnabled()) {
                saveCurrentDeviceSnapshot()
            }
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting current profile: ${e.message}")
        }
    }

    private fun restoreProfilePreset(profile: Int) {
        try {
            val prefs = getProfilePrefs(profile)
            val savedPresetGains = prefs.getString(DolbyConstants.PREF_PRESET, null)
            
            if (savedPresetGains != null) {
                val gains = savedPresetGains.split(",").mapNotNull { it.toIntOrNull() }.toIntArray()
                if (gains.size == 20) {
                    dolbyEffect.setDapParameter(DsParam.GEQ_BAND_GAINS, gains, profile)
                    DolbyConstants.dlog(TAG, "Restored preset for profile $profile")
                }
            }
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Failed to restore preset for profile $profile: ${e.message}")
        }
    }

    fun verifyProfileSaved(profile: Int): Boolean {
        val prefs = defaultPrefs.getString(DolbyConstants.PREF_PROFILE, "0")?.toIntOrNull()
        val saved = prefs == profile
        DolbyConstants.dlog(TAG, "Profile verification: requested=$profile, saved=$prefs, match=$saved")
        return saved
    }

    private fun getProfilePrefs(profile: Int): SharedPreferences {
        return context.getSharedPreferences("profile_$profile", Context.MODE_PRIVATE)
    }

    fun getBandMode(): BandMode {
        val mode = defaultPrefs.getString(DolbyConstants.PREF_BAND_MODE, "10")
        return when (mode) {
            "10" -> BandMode.TEN_BAND
            "15" -> BandMode.FIFTEEN_BAND
            "20" -> BandMode.TWENTY_BAND
            else -> BandMode.TEN_BAND
        }
    }

    fun setBandMode(mode: BandMode) {
        defaultPrefs.edit().putString(DolbyConstants.PREF_BAND_MODE, mode.value).apply()
    }

    fun getBassEnhancerEnabled(profile: Int): Boolean {
        val prefs = getProfilePrefs(profile)
        if (prefs.contains(DolbyConstants.PREF_BASS)) {
            return prefs.getBoolean(DolbyConstants.PREF_BASS, false)
        }
        return try {
            dolbyEffect.getDapParameterBool(DsParam.BASS_ENHANCER_ENABLE, profile)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error getting bass enhancer: ${e.message}")
            false
        }
    }

    fun setBassEnhancerEnabled(profile: Int, enabled: Boolean, saveDeviceSnapshot: Boolean = true) {
        try {
            checkEffect()
            dolbyEffect.setDapParameter(DsParam.BASS_ENHANCER_ENABLE, enabled, profile)
            getProfilePrefs(profile).edit().putBoolean(DolbyConstants.PREF_BASS, enabled).apply()
            if (saveDeviceSnapshot && isDeviceStateMemoryEnabled && getDolbyEnabled()) {
                saveCurrentDeviceSnapshot()
            }
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting bass enhancer: ${e.message}")
        }
    }

    fun getBassLevel(profile: Int): Int {
        val prefs = getProfilePrefs(profile)
        return prefs.getInt(DolbyConstants.PREF_BASS_LEVEL, 0)
    }

    fun getBassCurve(profile: Int): Int {
        val prefs = getProfilePrefs(profile)
        return prefs.getInt(DolbyConstants.PREF_BASS_CURVE, 0)
    }

    fun setBassCurve(profile: Int, curve: Int, saveDeviceSnapshot: Boolean = true) {
        try {
            val prefs = getProfilePrefs(profile)
            val previousCurve = prefs.getInt(DolbyConstants.PREF_BASS_CURVE, 0)
            val level = prefs.getInt(DolbyConstants.PREF_BASS_LEVEL, 0)
            if (previousCurve == curve) return

            prefs.edit().putInt(DolbyConstants.PREF_BASS_CURVE, curve).apply()

            if (level <= 0) {
                if (saveDeviceSnapshot && isDeviceStateMemoryEnabled && getDolbyEnabled()) {
                    saveCurrentDeviceSnapshot()
                }
                return
            }
            checkEffect()
            val currentGains = dolbyEffect.getDapParameter(DsParam.GEQ_BAND_GAINS, profile)
            val modifiedGains = currentGains.copyOf()
            applyBassCurve(modifiedGains, level, previousCurve, -1)
            applyBassCurve(modifiedGains, level, curve, 1)
            dolbyEffect.setDapParameter(DsParam.GEQ_BAND_GAINS, modifiedGains, profile)
            
            val gainsString = modifiedGains.joinToString(",")
            prefs.edit().putString(DolbyConstants.PREF_PRESET, gainsString).apply()
            if (saveDeviceSnapshot && isDeviceStateMemoryEnabled && getDolbyEnabled()) {
                saveCurrentDeviceSnapshot()
            }
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting bass curve: ${e.message}")
            throw e
        }
    }

    private fun applyBassCurve(gains: IntArray, level: Int, curve: Int, direction: Int) {
        val weights = BASS_CURVES.getOrElse(curve) { BASS_CURVES[0] }
        val baseGain = level * BASS_GAIN_MULTIPLIER
        for (i in weights.indices) {
            if (i >= gains.size) break
            val weightedGain = (baseGain * weights[i] * direction).toInt()
            gains[i] = (gains[i] + weightedGain).coerceIn(-150, 150)
        }
    }

    fun setBassLevel(profile: Int, level: Int, saveDeviceSnapshot: Boolean = true) {
        DolbyConstants.dlog(TAG, "setBassLevel: profile=$profile level=$level")

        if (level !in 0..100) {
            DolbyConstants.dlog(TAG, "setBassLevel: invalid level $level")
            throw IllegalArgumentException("Bass level must be between 0 and 100")
        }
        
        try {
            val prefs = getProfilePrefs(profile)
            val previousLevel = prefs.getInt(DolbyConstants.PREF_BASS_LEVEL, 0)
            
            prefs.edit().putInt(DolbyConstants.PREF_BASS_LEVEL, level).apply()
            
            setBassEnhancerEnabled(profile, level > 0, saveDeviceSnapshot = false)
            
            checkEffect()
            val currentGains = dolbyEffect.getDapParameter(DsParam.GEQ_BAND_GAINS, profile)
            val modifiedGains = currentGains.copyOf()
            
            val curve = prefs.getInt(DolbyConstants.PREF_BASS_CURVE, 0)
            if (previousLevel > 0) {
                applyBassCurve(modifiedGains, previousLevel, curve, -1)
            }

            if (level > 0) {
                applyBassCurve(modifiedGains, level, curve, 1)
            }
            dolbyEffect.setDapParameter(DsParam.GEQ_BAND_GAINS, modifiedGains, profile)
            
            val gainsString = modifiedGains.joinToString(",")
            prefs.edit().putString(DolbyConstants.PREF_PRESET, gainsString).apply()
            
            if (saveDeviceSnapshot && isDeviceStateMemoryEnabled && getDolbyEnabled()) {
                saveCurrentDeviceSnapshot()
            }
            DolbyConstants.dlog(TAG, "setBassLevel: success")
        } catch (e: IllegalArgumentException) {
            DolbyConstants.dlog(TAG, "setBassLevel: validation error - ${e.message}")
            val prefs = getProfilePrefs(profile)
            prefs.edit().putInt(DolbyConstants.PREF_BASS_LEVEL, 0).apply()
            throw e
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "setBassLevel: unexpected error - ${e.message}")
            throw e
        }
    }

    fun getTrebleEnhancerEnabled(profile: Int): Boolean {
        val prefs = getProfilePrefs(profile)
        return prefs.getBoolean(DolbyConstants.PREF_TREBLE, false)
    }

    fun setTrebleEnhancerEnabled(profile: Int, enabled: Boolean, saveDeviceSnapshot: Boolean = true) {
        getProfilePrefs(profile).edit().putBoolean(DolbyConstants.PREF_TREBLE, enabled).apply()
        if (saveDeviceSnapshot && isDeviceStateMemoryEnabled && getDolbyEnabled()) {
            saveCurrentDeviceSnapshot()
        }
    }

    fun getTrebleLevel(profile: Int): Int {
        val prefs = getProfilePrefs(profile)
        return prefs.getInt(DolbyConstants.PREF_TREBLE_LEVEL, 0)
    }

    fun setTrebleLevel(profile: Int, level: Int, saveDeviceSnapshot: Boolean = true) {
        DolbyConstants.dlog(TAG, "setTrebleLevel: profile=$profile level=$level")

        if (level !in 0..100) {
            DolbyConstants.dlog(TAG, "setTrebleLevel: invalid level $level")
            throw IllegalArgumentException("Treble level must be between 0 and 100")
        }

        try {
            val prefs = getProfilePrefs(profile)
            val previousLevel = prefs.getInt(DolbyConstants.PREF_TREBLE_LEVEL, 0)

            prefs.edit().putInt(DolbyConstants.PREF_TREBLE_LEVEL, level).apply()
            setTrebleEnhancerEnabled(profile, level > 0, saveDeviceSnapshot = false)

            checkEffect()
            val currentGains = dolbyEffect.getDapParameter(DsParam.GEQ_BAND_GAINS, profile)
            val modifiedGains = currentGains.copyOf()

            if (previousLevel > 0) {
                val previousGain = (previousLevel * TREBLE_GAIN_MULTIPLIER).toInt()
                for (i in 14..19) {
                    if (i < modifiedGains.size) {
                        modifiedGains[i] = (modifiedGains[i] - previousGain).coerceIn(-150, 150)
                    }
                }
            }

            if (level > 0) {
                val trebleGain = (level * TREBLE_GAIN_MULTIPLIER).toInt()
                for (i in 14..19) {
                    if (i < modifiedGains.size) {
                        modifiedGains[i] = (modifiedGains[i] + trebleGain).coerceIn(-150, 150)
                    }
                }
            }

            dolbyEffect.setDapParameter(DsParam.GEQ_BAND_GAINS, modifiedGains, profile)
            
            val gainsString = modifiedGains.joinToString(",")
            prefs.edit().putString(DolbyConstants.PREF_PRESET, gainsString).apply()
            
            if (saveDeviceSnapshot && isDeviceStateMemoryEnabled && getDolbyEnabled()) {
                saveCurrentDeviceSnapshot()
            }
            DolbyConstants.dlog(TAG, "setTrebleLevel: success")
        } catch (e: IllegalArgumentException) {
            DolbyConstants.dlog(TAG, "setTrebleLevel: validation error - ${e.message}")
            val prefs = getProfilePrefs(profile)
            prefs.edit().putInt(DolbyConstants.PREF_TREBLE_LEVEL, 0).apply()
            throw e
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "setTrebleLevel: unexpected error - ${e.message}")
            throw e
        }
    }

    fun getVolumeLevelerEnabled(profile: Int): Boolean {
        if (!volumeLevelerSupported) return false
        val prefs = getProfilePrefs(profile)
        if (prefs.contains(DolbyConstants.PREF_VOLUME)) {
            return prefs.getBoolean(DolbyConstants.PREF_VOLUME, false)
        }
        return try {
            dolbyEffect.getDapParameterBool(DsParam.VOLUME_LEVELER_ENABLE, profile)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error getting volume leveler: ${e.message}")
            false
        }
    }

    fun setVolumeLevelerEnabled(profile: Int, enabled: Boolean, saveDeviceSnapshot: Boolean = true) {
        if (!volumeLevelerSupported) return
        
        try {
            checkEffect()
            dolbyEffect.setDapParameter(DsParam.VOLUME_LEVELER_ENABLE, enabled, profile)
            getProfilePrefs(profile).edit().putBoolean(DolbyConstants.PREF_VOLUME, enabled).apply()
            if (saveDeviceSnapshot && isDeviceStateMemoryEnabled && getDolbyEnabled()) {
                saveCurrentDeviceSnapshot()
            }
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting volume leveler: ${e.message}")
        }
    }

    fun getIeqPreset(profile: Int): Int {
        val prefs = getProfilePrefs(profile)
        if (prefs.contains(DolbyConstants.PREF_IEQ)) {
            return prefs.getString(DolbyConstants.PREF_IEQ, "0")?.toIntOrNull() ?: 0
        }
        return try {
            dolbyEffect.getDapParameterInt(DsParam.IEQ_PRESET, profile)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error getting IEQ preset: ${e.message}")
            0
        }
    }

    fun setIeqPreset(profile: Int, preset: Int, saveDeviceSnapshot: Boolean = true) {
        try {
            checkEffect()
            dolbyEffect.setDapParameter(DsParam.IEQ_PRESET, preset, profile)
            getProfilePrefs(profile).edit().putString(DolbyConstants.PREF_IEQ, preset.toString()).apply()
            if (saveDeviceSnapshot && isDeviceStateMemoryEnabled && getDolbyEnabled()) {
                saveCurrentDeviceSnapshot()
            }
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting IEQ preset: ${e.message}")
        }
    }

    fun getHeadphoneVirtualizerEnabled(profile: Int): Boolean {
        val prefs = getProfilePrefs(profile)
        if (prefs.contains(DolbyConstants.PREF_HP_VIRTUALIZER)) {
            return prefs.getBoolean(DolbyConstants.PREF_HP_VIRTUALIZER, false)
        }
        return try {
            dolbyEffect.getDapParameterBool(DsParam.HEADPHONE_VIRTUALIZER, profile)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error getting headphone virtualizer: ${e.message}")
            false
        }
    }

    fun setHeadphoneVirtualizerEnabled(profile: Int, enabled: Boolean, saveDeviceSnapshot: Boolean = true) {
        try {
            checkEffect()
            dolbyEffect.setDapParameter(DsParam.HEADPHONE_VIRTUALIZER, enabled, profile)
            getProfilePrefs(profile).edit().putBoolean(DolbyConstants.PREF_HP_VIRTUALIZER, enabled).apply()
            if (saveDeviceSnapshot && isDeviceStateMemoryEnabled && getDolbyEnabled()) {
                saveCurrentDeviceSnapshot()
            }
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting headphone virtualizer: ${e.message}")
        }
    }

    fun getSpeakerVirtualizerEnabled(profile: Int): Boolean {
        val prefs = getProfilePrefs(profile)
        if (prefs.contains(DolbyConstants.PREF_SPK_VIRTUALIZER)) {
            return prefs.getBoolean(DolbyConstants.PREF_SPK_VIRTUALIZER, false)
        }
        return try {
            dolbyEffect.getDapParameterBool(DsParam.SPEAKER_VIRTUALIZER, profile)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error getting speaker virtualizer: ${e.message}")
            false
        }
    }

    fun setSpeakerVirtualizerEnabled(profile: Int, enabled: Boolean, saveDeviceSnapshot: Boolean = true) {
        try {
            checkEffect()
            dolbyEffect.setDapParameter(DsParam.SPEAKER_VIRTUALIZER, enabled, profile)
            getProfilePrefs(profile).edit().putBoolean(DolbyConstants.PREF_SPK_VIRTUALIZER, enabled).apply()
            if (saveDeviceSnapshot && isDeviceStateMemoryEnabled && getDolbyEnabled()) {
                saveCurrentDeviceSnapshot()
            }
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting speaker virtualizer: ${e.message}")
        }
    }

    fun getStereoWideningAmount(profile: Int): Int {
        if (!stereoWideningSupported) return 0
        val prefs = getProfilePrefs(profile)
        if (prefs.contains(DolbyConstants.PREF_STEREO_WIDENING)) {
            return prefs.getInt(DolbyConstants.PREF_STEREO_WIDENING, 32)
        }
        return try {
            dolbyEffect.getDapParameterInt(DsParam.STEREO_WIDENING_AMOUNT, profile)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error getting stereo widening: ${e.message}")
            32
        }
    }

    fun setStereoWideningAmount(profile: Int, amount: Int, saveDeviceSnapshot: Boolean = true) {
        if (!stereoWideningSupported) return
        
        try {
            checkEffect()
            dolbyEffect.setDapParameter(DsParam.STEREO_WIDENING_AMOUNT, amount, profile)
            getProfilePrefs(profile).edit().putInt(DolbyConstants.PREF_STEREO_WIDENING, amount).apply()
            if (saveDeviceSnapshot && isDeviceStateMemoryEnabled && getDolbyEnabled()) {
                saveCurrentDeviceSnapshot()
            }
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting stereo widening: ${e.message}")
        }
    }

    fun getDialogueEnhancerEnabled(profile: Int): Boolean {
        val prefs = getProfilePrefs(profile)
        if (prefs.contains(DolbyConstants.PREF_DIALOGUE)) {
            return prefs.getBoolean(DolbyConstants.PREF_DIALOGUE, false)
        }
        return try {
            dolbyEffect.getDapParameterBool(DsParam.DIALOGUE_ENHANCER_ENABLE, profile)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error getting dialogue enhancer: ${e.message}")
            false
        }
    }

    fun setDialogueEnhancerEnabled(profile: Int, enabled: Boolean, saveDeviceSnapshot: Boolean = true) {
        try {
            checkEffect()
            dolbyEffect.setDapParameter(DsParam.DIALOGUE_ENHANCER_ENABLE, enabled, profile)
            getProfilePrefs(profile).edit().putBoolean(DolbyConstants.PREF_DIALOGUE, enabled).apply()
            if (saveDeviceSnapshot && isDeviceStateMemoryEnabled && getDolbyEnabled()) {
                saveCurrentDeviceSnapshot()
            }
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting dialogue enhancer: ${e.message}")
        }
    }

    fun getDialogueEnhancerAmount(profile: Int): Int {
        val prefs = getProfilePrefs(profile)
        if (prefs.contains(DolbyConstants.PREF_DIALOGUE_AMOUNT)) {
            return prefs.getInt(DolbyConstants.PREF_DIALOGUE_AMOUNT, 6)
        }
        return try {
            dolbyEffect.getDapParameterInt(DsParam.DIALOGUE_ENHANCER_AMOUNT, profile)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error getting dialogue enhancer amount: ${e.message}")
            6
        }
    }

    fun setDialogueEnhancerAmount(profile: Int, amount: Int, saveDeviceSnapshot: Boolean = true) {
        try {
            checkEffect()
            dolbyEffect.setDapParameter(DsParam.DIALOGUE_ENHANCER_AMOUNT, amount, profile)
            getProfilePrefs(profile).edit().putInt(DolbyConstants.PREF_DIALOGUE_AMOUNT, amount).apply()
            if (saveDeviceSnapshot && isDeviceStateMemoryEnabled && getDolbyEnabled()) {
                saveCurrentDeviceSnapshot()
            }
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting dialogue enhancer amount: ${e.message}")
        }
    }

    fun getEqualizerGains(profile: Int, bandMode: BandMode): List<BandGain> {
        return try {
            val gains = dolbyEffect.getDapParameter(DsParam.GEQ_BAND_GAINS, profile)
            deserializeGains(gains, bandMode)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error getting equalizer gains: ${e.message}")
            val frequencies = when (bandMode) {
                BandMode.TEN_BAND -> BAND_FREQUENCIES_10
                BandMode.FIFTEEN_BAND -> BAND_FREQUENCIES_15
                BandMode.TWENTY_BAND -> BAND_FREQUENCIES_20
            }
            frequencies.map { BandGain(frequency = it, gain = 0) }
        }
    }

    fun setEqualizerGains(profile: Int, bandGains: List<BandGain>, bandMode: BandMode, saveDeviceSnapshot: Boolean = true) {
        try {
            checkEffect()
            val gains = serializeGains(bandGains, bandMode)
            dolbyEffect.setDapParameter(DsParam.GEQ_BAND_GAINS, gains, profile)
            val gainsString = gains.joinToString(",")
            getProfilePrefs(profile).edit().putString(DolbyConstants.PREF_PRESET, gainsString).apply()
            if (saveDeviceSnapshot && isDeviceStateMemoryEnabled && getDolbyEnabled()) {
                saveCurrentDeviceSnapshot()
            }
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting equalizer gains: ${e.message}")
        }
    }

    fun getPresetName(profile: Int): String {
        return try {
            val gains = dolbyEffect.getDapParameter(DsParam.GEQ_BAND_GAINS, profile)
            
            val tenBandGains = gains.filterIndexed { index, _ -> index % 2 == 0 }
            val currentGainsString = tenBandGains.joinToString(",")
            
            val presetValues = context.resources.getStringArray(R.array.dolby_preset_values)
            val presetNames = context.resources.getStringArray(R.array.dolby_preset_entries)
            
            presetValues.forEachIndexed { index, preset ->
                val presetTenBand = convertTo10Band(preset)
                if (gainsMatch(presetTenBand, currentGainsString)) {
                    return presetNames[index]
                }
            }
            
            presetsPrefs.all.forEach { (name, value) ->
                val presetTenBand = convertTo10Band(value.toString())
                if (gainsMatch(presetTenBand, currentGainsString)) {
                    return name
                }
            }
            
            context.getString(R.string.dolby_preset_custom)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error getting preset name: ${e.message}")
            context.getString(R.string.dolby_preset_custom)
        }
    }

    private fun convertTo10Band(gainsString: String): String {
        val gains = gainsString.split(",").map { it.trim().toIntOrNull() ?: 0 }
        
        if (gains.size == 10) {
            return gains.joinToString(",")
        }
        
        if (gains.size == 20) {
            val tenBand = gains.filterIndexed { index, _ -> index % 2 == 0 }
            return tenBand.joinToString(",")
        }
        
        return gainsString
    }

    private fun gainsMatch(gains1: String, gains2: String): Boolean {
        val g1 = gains1.split(",").map { it.trim().toIntOrNull() ?: 0 }
        val g2 = gains2.split(",").map { it.trim().toIntOrNull() ?: 0 }
        
        if (g1.size != g2.size) return false
        
        return g1.zip(g2).all { (a, b) -> kotlin.math.abs(a - b) <= 1 }
    }

    fun getUserPresets(): List<EqualizerPreset> {
        synchronized(presetCacheLock) {
            cachedPresets?.let { return it }
            
            val bandMode = getBandMode()
            val presets = presetsPrefs.all.mapNotNull { (name, value) ->
                try {
                    val valueStr = value as? String ?: return@mapNotNull null
                    parsePreset(name, valueStr)
                } catch (e: Exception) {
                    DolbyConstants.dlog(TAG, "Error parsing preset $name: ${e.message}")
                    null
                }
            }
            
            cachedPresets = presets
            return presets
        }
    }

    private fun parsePreset(name: String, valueStr: String): EqualizerPreset? {
        return try {
            if (valueStr.contains("|")) {
                val parts = valueStr.split("|")
                val presetBandMode = BandMode.fromValue(parts[1])
                val gains = parts[0].split(",").map { it.toInt() }.toIntArray()
                EqualizerPreset(
                    name = name,
                    bandGains = deserializeGains(gains, presetBandMode),
                    isUserDefined = true,
                    bandMode = presetBandMode
                )
            } else {
                val gains = valueStr.split(",").map { it.toInt() }.toIntArray()
                EqualizerPreset(
                    name = name,
                    bandGains = deserializeGains(gains, BandMode.TEN_BAND),
                    isUserDefined = true,
                    bandMode = BandMode.TEN_BAND
                )
            }
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error parsing preset value: ${e.message}")
            null
        }
    }

    fun addUserPreset(name: String, bandGains: List<BandGain>, bandMode: BandMode) {
        try {
            val gains = serializeGains(bandGains, bandMode).joinToString(",")
            val value = "$gains|${bandMode.value}"
            presetsPrefs.edit().putString(name, value).apply()
            
            synchronized(presetCacheLock) {
                cachedPresets = null
            }
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error adding user preset: ${e.message}")
        }
    }

    fun deleteUserPreset(name: String) {
        try {
            presetsPrefs.edit().remove(name).apply()
            
            synchronized(presetCacheLock) {
                cachedPresets = null
            }
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error deleting user preset: ${e.message}")
        }
    }

    fun resetProfile(profile: Int) {
        
        try {
            checkEffect()
            dolbyEffect.resetProfileSpecificSettings(profile)
            context.deleteSharedPreferences("profile_$profile")
            if (volumeLevelerSupported) {
                getProfilePrefs(profile).edit().putBoolean(DolbyConstants.PREF_VOLUME, false).apply()
            }
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error resetting profile: ${e.message}")
        }
    }

    fun resetAllProfiles() {
        
        try {
            checkEffect()
            context.resources.getStringArray(R.array.dolby_profile_values)
                .map { it.toInt() }
                .forEach { resetProfile(it) }
            setCurrentProfile(0)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error resetting all profiles: ${e.message}")
        }
    }

    private fun deserializeGains(gains: IntArray, bandMode: BandMode): List<BandGain> {
        val frequencies = when (bandMode) {
            BandMode.TEN_BAND -> BAND_FREQUENCIES_10
            BandMode.FIFTEEN_BAND -> BAND_FREQUENCIES_15
            BandMode.TWENTY_BAND -> BAND_FREQUENCIES_20
        }
        
        val indices = when (bandMode) {
            BandMode.TEN_BAND -> TEN_BAND_INDICES
            BandMode.FIFTEEN_BAND -> FIFTEEN_BAND_INDICES
            BandMode.TWENTY_BAND -> (0..19).toList()
        }
        
        return frequencies.mapIndexed { index, freq ->
            val gainIndex = indices.getOrNull(index) ?: index
            BandGain(frequency = freq, gain = gains.getOrElse(gainIndex) { 0 })
        }
    }

    private fun serializeGains(bandGains: List<BandGain>, bandMode: BandMode): IntArray {
        val result = IntArray(20) { 0 }
        
        when (bandMode) {
            BandMode.TEN_BAND -> {
                TEN_BAND_INDICES.forEachIndexed { index, targetIndex ->
                    if (index < bandGains.size && targetIndex < 20) {
                        result[targetIndex] = bandGains[index].gain
                    }
                }
                for (i in 0 until 19 step 2) {
                    if (i + 2 < 20) {
                        result[i + 1] = (result[i] + result[i + 2]) / 2
                    }
                }   
                result[19] = result[18]
            }
            BandMode.FIFTEEN_BAND -> {
                FIFTEEN_BAND_INDICES.forEachIndexed { index, targetIndex ->
                    if (index < bandGains.size && targetIndex < 20) {
                        result[targetIndex] = bandGains[index].gain
                    }
                }
                val missing = (0..19).filter { it !in FIFTEEN_BAND_INDICES }
                missing.forEach { idx ->
                    val prev = FIFTEEN_BAND_INDICES.filter { it < idx }.maxOrNull() ?: 0
                    val next = FIFTEEN_BAND_INDICES.filter { it > idx }.minOrNull() ?: 19
                    
                    if (prev < idx && next > idx && prev < 20 && next < 20) {
                        val prevValue = result[prev]
                        val nextValue = result[next]
                        val ratio = (idx - prev).toFloat() / (next - prev)
                        result[idx] = (prevValue + ratio * (nextValue - prevValue)).toInt()
                    } else if (prev < 20) {
                        result[idx] = result[prev]
                    }
                }
            }
            BandMode.TWENTY_BAND -> {
                bandGains.forEachIndexed { index, bandGain ->
                    if (index < 20) {
                        result[index] = bandGain.gain
                    }
                }
            }
        }
        
        return result
    }

    fun getMidEnhancerEnabled(profile: Int): Boolean {
        val prefs = getProfilePrefs(profile)
        return prefs.getBoolean(DolbyConstants.PREF_MID, false)
    }

    fun setMidEnhancerEnabled(profile: Int, enabled: Boolean, saveDeviceSnapshot: Boolean = true) {
        getProfilePrefs(profile).edit().putBoolean(DolbyConstants.PREF_MID, enabled).apply()
        if (saveDeviceSnapshot && isDeviceStateMemoryEnabled && getDolbyEnabled()) {
            saveCurrentDeviceSnapshot()
        }
    }

    fun getMidLevel(profile: Int): Int {
        val prefs = getProfilePrefs(profile)
        return prefs.getInt(DolbyConstants.PREF_MID_LEVEL, 0)
    }

    fun setMidLevel(profile: Int, level: Int, saveDeviceSnapshot: Boolean = true) {
        DolbyConstants.dlog(TAG, "setMidLevel: profile=$profile level=$level")

        if (level !in 0..100) {
            DolbyConstants.dlog(TAG, "setMidLevel: invalid level $level")
            throw IllegalArgumentException("Mid level must be between 0 and 100")
        }

        try {
            val prefs = getProfilePrefs(profile)
            val previousLevel = prefs.getInt(DolbyConstants.PREF_MID_LEVEL, 0)

            prefs.edit().putInt(DolbyConstants.PREF_MID_LEVEL, level).apply()
            setMidEnhancerEnabled(profile, level > 0, saveDeviceSnapshot = false)

            checkEffect()
            val currentGains = dolbyEffect.getDapParameter(DsParam.GEQ_BAND_GAINS, profile)
            val modifiedGains = currentGains.copyOf()

            if (previousLevel > 0) {
                val previousGain = (previousLevel * MID_GAIN_MULTIPLIER).toInt()
                for (i in 5..13) {
                    if (i < modifiedGains.size) {
                        modifiedGains[i] = (modifiedGains[i] - previousGain).coerceIn(-150, 150)
                    }
                }
            }

            if (level > 0) {
                val midGain = (level * MID_GAIN_MULTIPLIER).toInt()
                for (i in 5..13) {
                    if (i < modifiedGains.size) {
                        modifiedGains[i] = (modifiedGains[i] + midGain).coerceIn(-150, 150)
                    }
                }
            }

            dolbyEffect.setDapParameter(DsParam.GEQ_BAND_GAINS, modifiedGains, profile)
            
            val gainsString = modifiedGains.joinToString(",")
            prefs.edit().putString(DolbyConstants.PREF_PRESET, gainsString).apply()
            
            if (saveDeviceSnapshot && isDeviceStateMemoryEnabled && getDolbyEnabled()) {
                saveCurrentDeviceSnapshot()
            }
            DolbyConstants.dlog(TAG, "setMidLevel: success")
        } catch (e: IllegalArgumentException) {
            DolbyConstants.dlog(TAG, "setMidLevel: validation error - ${e.message}")
            val prefs = getProfilePrefs(profile)
            prefs.edit().putInt(DolbyConstants.PREF_MID_LEVEL, 0).apply()
            throw e
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "setMidLevel: unexpected error - ${e.message}")
            throw e
        }
    }
    
    fun release() {
        DolbyConstants.dlog(TAG, "Releasing repository resources")
        try {
            dolbyEffect.release()
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error releasing effect: ${e.message}")
        }
    }
    
    override fun close() {
        // No-op for singleton repository to prevent transient callers from breaking application state
    }

    companion object {
        private const val TAG = "DolbyRepository"
        private const val EFFECT_PRIORITY = 100

        @Volatile
        private var instance: DolbyRepository? = null

        fun getInstance(context: Context): DolbyRepository {
            return instance ?: synchronized(this) {
                instance ?: DolbyRepository(context.applicationContext).also {
                    instance = it
                }
            }
        }
        
        private const val BASS_GAIN_MULTIPLIER = 1.4f
        private const val MID_GAIN_MULTIPLIER = 1.3f
        private const val TREBLE_GAIN_MULTIPLIER = 1.5f
        
        private val ATTRIBUTES_MEDIA = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()

        val BAND_FREQUENCIES_10 = listOf(32, 64, 125, 250, 500, 1000, 2250, 5000, 10000, 19688)
        
        val BAND_FREQUENCIES_15 = listOf(
            32, 47, 94, 141, 234, 469, 844, 1313, 2250, 3750, 5813, 9000, 11250, 13875, 19688
        )
        
        val BAND_FREQUENCIES_20 = listOf(
            32, 47, 141, 234, 328, 469, 656, 844, 1031, 1313,
            1688, 2250, 3000, 3750, 4688, 5813, 7125, 9000, 11250, 19688
        )
        
        private val TEN_BAND_INDICES = listOf(0, 2, 4, 6, 8, 10, 12, 14, 16, 18)
        
        private val FIFTEEN_BAND_INDICES = listOf(0, 1, 2, 3, 4, 5, 6, 8, 11, 12, 14, 15, 17, 18, 19)
        
        private val BASS_CURVES = listOf(
            floatArrayOf(
                1.00f, 1.00f, 0.95f, 0.90f, 0.80f, 0.70f, 0.55f, 0.40f, 0.25f, 0.15f,
                0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f
            ),
            floatArrayOf(
                1.20f, 1.15f, 1.05f, 0.90f, 0.70f, 0.55f, 0.40f, 0.25f, 0.10f, 0.05f,
                0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f
            ),
            floatArrayOf(
                0.90f, 0.95f, 1.00f, 1.00f, 0.90f, 0.75f, 0.60f, 0.45f, 0.30f, 0.20f,
                0.10f, 0.05f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f
            )
        )
    }
}
