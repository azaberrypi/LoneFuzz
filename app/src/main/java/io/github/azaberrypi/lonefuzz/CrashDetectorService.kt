package io.github.azaberrypi.lonefuzz

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * クラッシュ/ANR ダイアログの出現を検知する AccessibilityService。
 *
 * Android システムがクラッシュダイアログ（AppErrorDialog）を表示すると
 * TYPE_WINDOW_STATE_CHANGED イベントが package="android" で飛ぶ。
 * そのテキストにクラッシュ関連キーワードが含まれていれば CrashWatcher に通知し、
 * 直近の発火と照合して HIT を判定する。
 *
 * ユーザーが「設定 → ユーザー補助」で本サービスを有効化する必要がある。
 */
class CrashDetectorService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        // クラッシュ確定の第一源は ApplicationExitInfo（CrashWatcher.exitCrashSince）。
        // ここはフォールバック: システムのクラッシュ/ANR ダイアログを観測する。
        // （Android 12+ では初回クラッシュはトーストのみでイベントが飛ばない場合があるため補助。）
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
        ) {
            return
        }
        if (pkg != "android" && pkg != "com.android.systemui") return

        val texts = buildString {
            event.text?.forEach { append(it).append(' ') }
            event.contentDescription?.let { append(it) }
        }
        if (texts.isBlank()) return

        if (isCrashText(texts)) {
            val kind = if (isAnrText(texts)) "anr" else "crash"
            CrashWatcher.onCrashDetected(texts, kind)
            // ダイアログはそのまま残す（クラッシュの視覚的証拠として使う）。
            // 閉じるのは ScanEngine が次を撃つ直前（BURST の連射前）に行う。
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: CrashDetectorService? = null
            private set

        val isRunning: Boolean get() = instance != null

        fun pressBack() {
            instance?.performGlobalAction(GLOBAL_ACTION_BACK)
        }

        /**
         * 前面が自アプリか。root が取れないときも true（安全側＝BACK/HOME を撃たない）。
         * ダイアログが既に消えているのに BACK を撃つと、LoneFuzz 自身が閉じてホームに飛ぶ。
         */
        fun isOwnAppForeground(): Boolean {
            val svc = instance ?: return true
            val pkg = svc.rootInActiveWindow?.packageName?.toString() ?: return true
            return pkg == svc.packageName
        }

        fun goHomeAndReturn() {
            val svc = instance ?: return
            // 前面が自アプリなら戻る必要がない（HOME→復帰のちらつきも避ける）。
            if (isOwnAppForeground()) return
            // 自前の home 操作が対象を STOP させる＝UsageStats オラクルの偽陽性になる。
            // 発行時刻を記録し、それに説明される STOP を usageDisappearedSince() から除外する。
            CrashWatcher.noteHomeIssued()
            svc.performGlobalAction(GLOBAL_ACTION_HOME)
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val intent = Intent(svc, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    }
                    svc.startActivity(intent)
                } catch (_: Throwable) {}
            }, 400)
        }

        private val CRASH_KEYWORDS = listOf(
            "has stopped",
            "keeps stopping",
            "isn't responding",
            "not responding",
            "has crashed",
            "停止しました",
            "繰り返し停止",
            "応答していません",
            "問題が発生",
            "強制終了",
            "クラッシュ",
        )

        private val ANR_KEYWORDS = listOf(
            "isn't responding",
            "not responding",
            "応答していません",
        )

        private fun isCrashText(text: String): Boolean =
            CRASH_KEYWORDS.any { text.contains(it, ignoreCase = true) }

        private fun isAnrText(text: String): Boolean =
            ANR_KEYWORDS.any { text.contains(it, ignoreCase = true) }
    }
}
