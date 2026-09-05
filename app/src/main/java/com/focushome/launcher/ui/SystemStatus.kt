package com.focushome.launcher.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import java.time.LocalDateTime

data class BatteryStatus(
    val percent: Int?,
    val isCharging: Boolean,
)

/**
 * Wall-clock time that follows the system.
 *
 * Driven by ACTION_TIME_TICK rather than a coroutine delay loop: the system
 * broadcasts it once a minute to registered receivers only, and it also fires
 * when the screen comes back on, so the clock is never stale after the phone
 * has been asleep. TIME_CHANGED / TIMEZONE_CHANGED cover manual clock edits and
 * travel.
 */
@Composable
fun rememberCurrentTime(): LocalDateTime {
    val context = LocalContext.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                now = LocalDateTime.now()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        // ContextCompat picks the right registerReceiver overload for the API
        // level. These are protected system broadcasts, so NOT_EXPORTED is
        // correct and satisfies the API 34+ requirement to state the flag.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        now = LocalDateTime.now()
        onDispose { context.unregisterReceiver(receiver) }
    }
    return now
}

/**
 * Battery level and charging state.
 *
 * ACTION_BATTERY_CHANGED is sticky, so registerReceiver hands back the current
 * value straight away and the header never flashes an empty slot.
 */
@Composable
fun rememberBatteryStatus(): BatteryStatus {
    val context = LocalContext.current
    var status by remember { mutableStateOf(BatteryStatus(percent = null, isCharging = false)) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                intent?.let { status = it.toBatteryStatus() }
            }
        }
        val sticky = ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        sticky?.let { status = it.toBatteryStatus() }
        onDispose { context.unregisterReceiver(receiver) }
    }
    return status
}

private fun Intent.toBatteryStatus(): BatteryStatus {
    val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    val state = getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    return BatteryStatus(
        // Scale is not guaranteed to be 100, so the percentage is derived.
        percent = if (level >= 0 && scale > 0) level * 100 / scale else null,
        isCharging = state == BatteryManager.BATTERY_STATUS_CHARGING ||
            state == BatteryManager.BATTERY_STATUS_FULL,
    )
}
