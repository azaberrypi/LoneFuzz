package io.github.azaberrypi.lonefuzz

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 通知バーの「停止」または通知タップから BURST を止める。
 * Activity の再生成に依存せず、実行中 ScanEngine へ [AttackControl] 経由で停止を伝える。
 */
class StopBurstReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AttackControl.requestStop()
        context.stopService(Intent(context, AttackForegroundService::class.java))
    }
}
