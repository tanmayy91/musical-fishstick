package com.vivoios.emojichanger.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vivoios.emojichanger.db.AppDatabase
import com.vivoios.emojichanger.engine.DeviceDetector
import com.vivoios.emojichanger.engine.EmojiEngine
import kotlinx.coroutines.*

/**
 * Boot receiver that auto-reapplies the selected emoji pack after device reboot.
 */
class BootReceiver : BroadcastReceiver() {

    private val TAG = "BootReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val validActions = listOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
        if (intent.action !in validActions) return

        Log.i(TAG, "Received: ${intent.action} — checking for saved emoji pack")

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val db = AppDatabase.getInstance(context)
                val appliedPack = db.emojiPackDao().getAppliedPack()
                if (appliedPack != null && appliedPack.isDownloaded) {
                    Log.i(TAG, "Auto-reapplying pack: ${appliedPack.name}")
                    val deviceInfo = DeviceDetector.detect()
                    val engine = EmojiEngine(context, db.emojiPackDao(), deviceInfo)
                    engine.applyPack(appliedPack)
                    Log.i(TAG, "Pack reapplied successfully on boot")
                } else {
                    Log.d(TAG, "No pack to reapply on boot")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Boot reapply failed: ${e.message}", e)
            }
        }
    }
}
