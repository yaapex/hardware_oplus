/*
 * Copyright (C) 2021-2023 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.device

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.AudioSystem
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.os.UserHandle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import com.android.internal.os.DeviceKeyHandler
import java.io.File

class KeyHandler(private val context: Context) : DeviceKeyHandler {

    private val audioManager = context.getSystemService(AudioManager::class.java)!!
    private val notificationManager = context.getSystemService(NotificationManager::class.java)!!
    private val vibrator: Vibrator?

    private val packageContext =
        context.createPackageContext(KeyHandler::class.java.getPackage()!!.name, 0)
    private val sharedPreferences
        get() =
            packageContext.getSharedPreferences(
                packageContext.packageName + "_preferences",
                Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS
            )

    private val handlerThread = HandlerThread("KeyHandlerThread").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private var needsRun = false
    private var prevKeyCode = 0

    init {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager 
        vibrator = vibratorManager?.defaultVibrator ?: (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)

        if (vibrator == null || !vibrator.hasVibrator()) {
            println("Vibrator service not available or no vibrator hardware found.")
        }
    }

    private val supportedSliderZenModes: Map<Int, Int> = mapOf(
        KEY_VALUE_TOTAL_SILENCE to Settings.Global.ZEN_MODE_NO_INTERRUPTIONS,
        KEY_VALUE_SILENT to Settings.Global.ZEN_MODE_OFF,
        KEY_VALUE_PRIORITY_ONLY to Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS,
        KEY_VALUE_VIBRATE to Settings.Global.ZEN_MODE_OFF,
        KEY_VALUE_NORMAL to Settings.Global.ZEN_MODE_OFF
    )

    private val supportedSliderRingModes: Map<Int, Int> = mapOf(
        KEY_VALUE_TOTAL_SILENCE to AudioManager.RINGER_MODE_NORMAL,
        KEY_VALUE_SILENT to AudioManager.RINGER_MODE_SILENT,
        KEY_VALUE_PRIORITY_ONLY to AudioManager.RINGER_MODE_NORMAL,
        KEY_VALUE_VIBRATE to AudioManager.RINGER_MODE_VIBRATE,
        KEY_VALUE_NORMAL to AudioManager.RINGER_MODE_NORMAL
    )

    private val supportedSliderHaptics: Map<Int, Int?> = mapOf(
        KEY_VALUE_TOTAL_SILENCE to VibrationEffect.EFFECT_THUD,
        KEY_VALUE_SILENT to VibrationEffect.EFFECT_DOUBLE_CLICK,
        KEY_VALUE_PRIORITY_ONLY to VibrationEffect.EFFECT_POP,
        KEY_VALUE_VIBRATE to VibrationEffect.EFFECT_HEAVY_CLICK,
        KEY_VALUE_NORMAL to null
    )

    private fun hasSetupCompleted(): Boolean {
        return Settings.Secure.getInt(context.contentResolver,
                Settings.Secure.USER_SETUP_COMPLETE, 0) != 0
    }

    override fun handleKeyEvent(event: KeyEvent): KeyEvent? {
        if (!hasSetupCompleted()) {
            return event
        }

        val deviceName = event.device?.name ?: ""

        if (
            deviceName != "oplus,hall_tri_state_key" &&
            deviceName != "oplus,tri-state-key" &&
            deviceName != "oplus_fp_input"
        ) {
            return event
        }

        val scanCode = File("/proc/tristatekey/tri_state").readText().trim().toInt()
        val keyCodeValue = when (scanCode) {
            1 -> sharedPreferences.getString(ALERT_SLIDER_TOP_KEY, "0")!!.toInt()
            2 -> sharedPreferences.getString(ALERT_SLIDER_MIDDLE_KEY, "1")!!.toInt()
            3 -> sharedPreferences.getString(ALERT_SLIDER_BOTTOM_KEY, "2")!!.toInt()
            else -> return event
        }

        if (event.action != KeyEvent.ACTION_UP) { // Only handle ACTION_UP event
            return null
        }

        needsRun = false
        handler.removeCallbacksAndMessages(null)

        val targetRingerMode = supportedSliderRingModes[keyCodeValue] ?: AudioManager.RINGER_MODE_NORMAL
        val targetZenMode = supportedSliderZenModes[keyCodeValue] ?: Settings.Global.ZEN_MODE_OFF
        val targetHaptic = supportedSliderHaptics[keyCodeValue] ?: -1
        if (prevKeyCode == KEY_VALUE_TOTAL_SILENCE && keyCodeValue != prevKeyCode) {
            // if previous was total silence we need to vibrate after setRingerModeInternal
            // for it to actually fire.
            // we also have to exit it before setRingerModeInternal because it sets it internally
            notificationManager.setZenMode(targetZenMode, null, TAG)
            audioManager.ringerModeInternal = targetRingerMode
            doHapticFeedback(targetHaptic)
            needsRun = true
            handler.postDelayed({
                if (audioManager.ringerModeInternal != targetRingerMode && needsRun) {
                    audioManager.ringerModeInternal = targetRingerMode
                }
            }, 200) // 200ms delay to ensure ringer mode is set correctly
        } else {
            // here we have to vibrate before setting anything else.
            // also setRingerModeInternal before setZenMode because it could set the ringer mode
            doHapticFeedback(targetHaptic)
            audioManager.ringerModeInternal = targetRingerMode
            val zenKeepEnabled = sharedPreferences.getBoolean(ZEN_KEEP_ENABLED, false)
            if (!zenKeepEnabled || targetZenMode != Settings.Global.ZEN_MODE_OFF) {
                // if zen keep is enabled we only transition into zen mode, not out of it
                notificationManager.setZenMode(targetZenMode, null, TAG)
            }
        }

        if (sharedPreferences.getBoolean(MUTE_MEDIA_WITH_SILENT, false)) {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (keyCodeValue == KEY_VALUE_SILENT) {
                KeyHandler.setLastMediaLevel(
                    sharedPreferences,
                    Math.round((currentVolume.toFloat() * 100f) / max.toFloat()).toInt()
                )
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
            } else if (prevKeyCode == KEY_VALUE_SILENT && currentVolume == 0) {
                val lastVolume = KeyHandler.getLastMediaLevel(sharedPreferences)
                audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    Math.round((max.toFloat() * lastVolume.toFloat()) / 100f).toInt(),
                    AudioManager.FLAG_SHOW_UI
                )
            }
        }

        if (sharedPreferences.getBoolean(SLIDER_DIALOG_ENABLED, true)) {
            sendNotification(scanCode, keyCodeValue)
        }

        prevKeyCode = keyCodeValue
        return null
    }

    override fun onPocketStateChanged(inPocket: Boolean) {
        // Do nothing
    }


    private fun doHapticFeedback(effect: Int) {
        if (vibrator != null && vibrator.hasVibrator() && effect != -1) {
            vibrator.vibrate(VibrationEffect.get(effect))
        }
    }

    private fun sendNotification(position: Int, mode: Int) {
        val intent =
            Intent(SLIDER_UPDATE_ACTION).apply {
                putExtra("position", position)
                putExtra("mode", mode)
            }
        context.sendBroadcastAsUser(intent, UserHandle(UserHandle.USER_CURRENT))
    }

    companion object {
        private const val TAG = "KeyHandler"

        // Slider key positions
        const val POSITION_TOP = 1
        const val POSITION_MIDDLE = 2
        const val POSITION_BOTTOM = 3

        const val SLIDER_UPDATE_ACTION = "org.lineageos.settings.device.UPDATE_SLIDER"

        // Preference keys
        private const val ALERT_SLIDER_TOP_KEY = "config_top_position"
        private const val ALERT_SLIDER_MIDDLE_KEY = "config_middle_position"
        private const val ALERT_SLIDER_BOTTOM_KEY = "config_bottom_position"
        private const val MUTE_MEDIA_WITH_SILENT = "config_mute_media"
        private const val SLIDER_DIALOG_ENABLED = "config_slider_dialog"
        private const val ZEN_KEEP_ENABLED = "config_zen_keep"

        const val SLIDER_DOZE_ENABLED = "config_slider_doze_enabled"
        const val SLIDER_DOZE_ENABLED_SETTING = "device_settings_" + SLIDER_DOZE_ENABLED

        const val KEY_VALUE_NORMAL = 0
        const val KEY_VALUE_VIBRATE = 1
        const val KEY_VALUE_SILENT = 2
        const val KEY_VALUE_PRIORITY_ONLY = 3
        const val KEY_VALUE_TOTAL_SILENCE = 4

        // Helper functions
        @JvmStatic
        fun setLastMediaLevel(prefs: SharedPreferences, level: Int) {
            prefs.edit().putInt("last_media_level", level).commit()
        }

        @JvmStatic
        fun getLastMediaLevel(prefs: SharedPreferences): Int {
            return prefs.getInt("last_media_level", 50)
        }

        @JvmStatic
        fun isSliderDozeEnabled(context: Context): Boolean {
            return Settings.System.getInt(context.contentResolver,
                SLIDER_DOZE_ENABLED_SETTING, 1) == 1
        }
    }
}
