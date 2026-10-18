package io.github.azaberrypi.lonefuzz

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import java.util.ArrayDeque

/**
 * 発火（AttackEngine）とクラッシュ検知（CrashDetectorService）を相関付けるシングルトン。
 * 発火ごとに recordFire() で記録し、クラッシュダイアログ検知時に onCrashDetected() で
 * 直近の発火と照合して HIT を報告する。
 */
object CrashWatcher {

    data class FiredShot(
        val packageName: String,
        val appLabel: String,
        val componentShort: String,
        val recipe: String,
        val time: Long = System.currentTimeMillis(),
    )

    data class Hit(
        val packageName: String,
        val appLabel: String,
        val componentShort: String,
        val recipe: String,
        val dialogText: String,
        /** "crash"（未処理例外でダイアログ） / "anr"（応答なし） / "rapid return"（即戻りのみ）。 */
        val kind: String = "crash",
    )

    fun interface HitListener {
        fun onHit(hit: Hit)
    }

    private const val WINDOW_MS = 8_000L
    private const val MAX_BUFFER = 200

    private val lock = Any()
    private val buffer = ArrayDeque<FiredShot>()
    private var listener: HitListener? = null

    /**
     * パッケージ別の「確定クラッシュ/ANRを最後に観測した時刻」。
     * Accessibility のダイアログ検知を唯一の確定ソースとして記録し、ScanEngine が
     * [confirmedCrashSince] で発火後の確定を照会する（＝即戻りだけの SUSPECTED と区別するため）。
     */
    private val lastConfirmed = HashMap<String, Long>()

    /**
     * クラッシュ/ANR ダイアログを観測し、まだ閉じていないか。
     * 残留ダイアログは次の発火を覆い隠し、後続テストの検知と混線する（一括sprayの取りこぼしの主因）。
     * ScanEngine が次を撃つ直前に [consumeDialogPendingDismiss] で取り出して閉じる。
     */
    @Volatile
    private var dialogPendingDismiss = false

    fun setListener(l: HitListener?) {
        synchronized(lock) { listener = l }
    }

    fun recordFire(shot: FiredShot) {
        synchronized(lock) {
            buffer.addLast(shot)
            while (buffer.size > MAX_BUFFER) buffer.removeFirst()
        }
    }

    fun onCrashDetected(dialogText: String, kind: String = "crash") {
        val now = System.currentTimeMillis()
        dialogPendingDismiss = true
        android.util.Log.i("LoneFuzz", "dialog detected (kind=$kind): ${dialogText.take(160)}")
        val hit: Hit?
        synchronized(lock) {
            val recent = buffer.filter { now - it.time < WINDOW_MS }
            val textMatch = recent.lastOrNull {
                it.appLabel.length >= 2 && dialogText.contains(it.appLabel, ignoreCase = true)
            }
            val match = textMatch ?: recent.lastOrNull()
            hit = match?.let {
                Hit(it.packageName, it.appLabel, it.componentShort, it.recipe, dialogText, kind)
            }
            if (match != null) {
                // 確定ソースとして記録。ScanEngine が発火後の確定を照会する。
                lastConfirmed[match.packageName] = now
                buffer.removeAll { it.packageName == match.packageName }
            }
        }
        if (hit != null) {
            synchronized(lock) { listener }?.onHit(hit)
        }
    }

    /** [pkg] に対し [since] 以降に確定クラッシュ/ANR（ダイアログ観測）があったか。 */
    fun confirmedCrashSince(pkg: String, since: Long): Boolean = synchronized(lock) {
        (lastConfirmed[pkg] ?: return false) >= since
    }

    /** 未処理のクラッシュダイアログがあるか取り出し、フラグを下ろす。 */
    fun consumeDialogPendingDismiss(): Boolean {
        val v = dialogPendingDismiss
        dialogPendingDismiss = false
        return v
    }

    @Volatile
    private var lastActivityShot: FiredShot? = null

    fun recordActivityFire(shot: FiredShot) {
        lastActivityShot = shot
    }

    fun checkRapidReturn(): Hit? {
        val shot = lastActivityShot ?: return null
        val delta = System.currentTimeMillis() - shot.time
        if (delta > RAPID_RETURN_MS) {
            lastActivityShot = null
            return null
        }
        lastActivityShot = null
        synchronized(lock) {
            buffer.removeAll { it.packageName == shot.packageName }
        }
        return Hit(shot.packageName, shot.appLabel, shot.componentShort, shot.recipe, "rapid return", kind = "rapid return")
    }

    // ---- ApplicationExitInfo オラクル（DUMP 権限。ダイアログ非依存の確定検知）----

    /** クラッシュ系のプロセス終了理由。 */
    private val crashReasons = setOf(
        android.app.ApplicationExitInfo.REASON_CRASH,
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE,
        android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
    )

    /**
     * [pkg] が [since] 以降にプロセスクラッシュで死んだか。`ApplicationExitInfo`
     * （API30+）を照会する。他パッケージの照会には DUMP 権限が必要（未付与なら false）。
     * クラッシュ通知のダイアログ/トースト表示やレート制限に依存しないため、
     * 初回クラッシュ（トーストのみ）でも確定的に検知できる。
     */
    fun exitCrashSince(context: Context, pkg: String, since: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            ?: return false
        val infos = runCatching { am.getHistoricalProcessExitReasons(pkg, 0, 0) }
            .onFailure { android.util.Log.w("LoneFuzz", "exit-info[$pkg] query failed: ${it.message}") }
            .getOrNull() ?: return false
        return infos.any { it.timestamp >= since && crashReasons.contains(it.reason) }
    }

    // ---- UsageStats オラクル（「静かなクラッシュ」補足）----

    /**
     * 自分から GLOBAL_ACTION_HOME を発行した時刻。自分の home 操作は対象の STOP を
     * 必ず引き起こすため、これ以降の STOP は「説明可能」になり HIT 判定から除外する。
     * テスト毎に発火時刻が基準（since）を超えて入れ替わるため、値のクリアは不要。
     */
    @Volatile
    private var lastHomeIssuedAt: Long = 0L

    fun noteHomeIssued() {
        lastHomeIssuedAt = System.currentTimeMillis()
    }

    /** DUMP 権限（他パッケージの ApplicationExitInfo を読むための development 権限）。 */
    fun hasDumpPermission(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.DUMP) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** UsageStats ステータス（PACKAGE_USAGE_STATS は AppOps 経由でユーザー付与）。 */
    @Suppress("DEPRECATION")
    fun hasUsageAccess(context: Context): Boolean = runCatching {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return@runCatching false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
            )
        }
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /**
     * [pkg] が [since] 以降に (a) フォアグラウンドに現れ（RESUMED）、その後
     * (b) ユーザー操作や自前 HOME の説明がつかないタイミングで消えた（STOP した）場合 true。
     *
     * 「ダイアログの出ない静かなクラッシュ」の第2信号。トランポリン Activity の即 finish と
     * は区別できないため、SUSPECTED と同等の強度で扱う（ScanEngine 側で合流）。
     * なお onCreate 中に死んだ場合（RESUMED が発生しない）は証拠が残らず捕捉不可。
     */
    fun usageDisappearedSince(pkg: String, since: Long, context: Context): Boolean {
        if (!hasUsageAccess(context)) return false
        val um = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return false
        val now = System.currentTimeMillis()
        if (now <= since) return false
        val events = um.queryEvents(since, now)
        val e = UsageEvents.Event()
        var resumedAt = 0L
        var stoppedAt = 0L
        while (events.hasNextEvent()) {
            if (!events.getNextEvent(e)) break
            if (e.packageName != pkg) continue
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED ->
                    resumedAt = e.timeStamp
                // ACTIVITY_PAUSED(2) と ACTIVITY_STOPPED(23) の双方を「消失」として拾う。
                // （API 29+ のプロセス死でどちらが記録されるかは端末依存のため両対応。
                //   値 2 は旧 MOVE_TO_BACKGROUND と同一。）
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED ->
                    stoppedAt = e.timeStamp
            }
        }
        // (a) 発火後に現れた、(b) 現れた後に消えた、の両方が必要。
        if (resumedAt < since || stoppedAt <= resumedAt) return false
        // 自前 HOME が発行されている場合にのみ、それに説明される STOP を除外する。
        val homeAt = lastHomeIssuedAt
        if (homeAt > since && stoppedAt >= homeAt) return false
        return true
    }

    private const val RAPID_RETURN_MS = 3_000L
}
