package io.github.azaberrypi.lonefuzz

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import io.github.azaberrypi.lonefuzz.model.ComponentKind
import io.github.azaberrypi.lonefuzz.model.TargetComponent
import java.util.ArrayDeque

class ScanEngine(private val activity: Activity) {

    /**
     * HIT の確からしさ。
     * - CONFIRMED: 対象プロセスのクラッシュ死を観測（ApplicationExitInfo の
     *   REASON_CRASH、または Accessibility のクラッシュ/ANR ダイアログ）。信頼できる。
     * - SUSPECTED: 即戻りのみ（対象 Activity が <1.5s で消えた）。トランポリンや自己 finish との
     *   区別がつかないため、レポートでは要再現確認。
     * - NONE: 非 HIT。
     */
    enum class HitConfidence { CONFIRMED, SUSPECTED, NONE }

    data class Result(
        val appLabel: String,
        val packageName: String,
        val component: String,
        val className: String,
        val appVersion: String,
        val recipeType: RecipeType,
        val recipe: String,
        val hit: Boolean,
        val confidence: HitConfidence = HitConfidence.NONE,
        val kind: ComponentKind = ComponentKind.ACTIVITY,
        val authority: String? = null,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val results = mutableListOf<Result>()
    private val queue = ArrayDeque<Pair<TargetComponent, RecipeType>>()
    private var current: Pair<TargetComponent, RecipeType>? = null
    private var fireTime = 0L
    private var totalCount = 0

    @Volatile
    var scanning = false
        private set

    var bursting = false
        private set
    private var cycleCount = 0
    private var burstHits = emptyList<Pair<TargetComponent, RecipeType>>()

    /** 走査開始時の言語。ログはこの言語で固定する。 */
    private var logText: LogText? = null

    private fun text(id: Int, vararg args: Any): String {
        val lt = logText ?: LogText(activity, LogText.currentLocale(activity)).also { logText = it }
        return lt.get(id, *args)
    }

    var onProgress: ((done: Int, total: Int, hits: Int) -> Unit)? = null
    var onHit: ((Result) -> Unit)? = null
    var onComplete: ((List<Result>) -> Unit)? = null
    var onCycleComplete: ((cycle: Int) -> Unit)? = null

    /** 発射/結果の逐次ログ（何に対してどのレシピを撃ち、どう判定したか＝証跡・デバッグ用）。 */
    var onLog: ((String) -> Unit)? = null

    /**
     * BURST 中に「以前は効いていた HIT が連続で効かなくなった」時に呼ばれる（効果ベースの再適応）。
     * 保存済みペイロードが対象アプリの仕様変更で死んだと判断し、呼び出し側が再探索（再spray）する。
     */
    var onStaleDetected: ((Result) -> Unit)? = null

    /** BURST における HIT ごとの連続未命中回数（命中でリセット）。 */
    private val burstMisses = HashMap<String, Int>()

    /** spray モード: 全コンポーネント × レシピを1周スキャン */
    fun start(components: List<TargetComponent>, recipes: List<RecipeType>, strings: LogText) {
        if (scanning) return
        if (!CrashDetectorService.isRunning) {
            onComplete?.invoke(emptyList())
            return
        }
        logText = strings
        scanning = true
        bursting = false
        results.clear()
        queue.clear()
        burstMisses.clear()

        val targets = components.filter { !isSystemPackage(it.packageName) }
        var skipped = 0
        for (comp in targets) {
            for (recipe in recipes) {
                if (!isRecipeApplicable(comp.kind, recipe)) {
                    skipped++
                    continue
                }
                queue.addLast(comp to recipe)
            }
        }
        if (skipped > 0) {
            onLog?.invoke(text(R.string.log_skip_pairs, skipped))
        }
        totalCount = queue.size
        if (totalCount == 0) {
            scanning = false
            onComplete?.invoke(emptyList())
            return
        }
        fireNext()
    }

    /** BURST モード: Activity 以外の確定HITを繰り返し連射 */
    fun startBurst(hits: List<Result>, strings: LogText) {
        if (scanning) return
        if (!CrashDetectorService.isRunning) {
            onComplete?.invoke(emptyList())
            return
        }
        logText = strings
        scanning = true
        bursting = true
        cycleCount = 0
        results.clear()
        queue.clear()
        burstMisses.clear()

        burstHits = hits.map { h ->
            TargetComponent(
                packageName = h.packageName,
                className = h.className,
                kind = h.kind,
                authority = h.authority,
                appLabel = h.appLabel,
                appVersion = h.appVersion,
            ) to h.recipeType
        }

        refillBurstQueue()
        totalCount = burstHits.size
        if (totalCount == 0) {
            scanning = false
            bursting = false
            onComplete?.invoke(emptyList())
            return
        }
        fireNext()
    }

    private fun refillBurstQueue() {
        for (pair in burstHits) {
            queue.addLast(pair)
        }
    }

    private fun fireNext() {
        if (!scanning) {
            finishRun()
            return
        }

        if (queue.isEmpty()) {
            if (bursting && scanning) {
                cycleCount++
                onCycleComplete?.invoke(cycleCount)
                results.clear()
                refillBurstQueue()
                handler.postDelayed({ fireNext() }, 400)
                return
            }
            finishRun()
            return
        }

        // 前のテストで観測したクラッシュ/ANR ダイアログが残っていれば、次を撃つ前に閉じる。
        // 残留ダイアログは次の対象を覆い隠し、検知を混線させる（一括sprayの取りこぼしの主因）。
        if (CrashWatcher.consumeDialogPendingDismiss()) {
            // ダイアログが既に消えて自アプリが前面のときに BACK を撃つと、
            // LoneFuzz 自身が閉じてホームに飛ぶ。前面が自アプリなら撃たない。
            if (!CrashDetectorService.isOwnAppForeground()) {
                CrashDetectorService.pressBack()
            }
            handler.postDelayed({ fireNext() }, DIALOG_DISMISS_WAIT_MS)
            return
        }

        val pair = queue.removeFirst()
        current = pair
        val (comp, recipe) = pair
        val recipeLabel = text(recipe.labelRes)

        try {
            val shot = CrashWatcher.FiredShot(
                comp.packageName, comp.appLabel, comp.shortName(), recipeLabel,
            )
            CrashWatcher.recordFire(shot)
            if (comp.kind == ComponentKind.ACTIVITY) CrashWatcher.recordActivityFire(shot)

            onLog?.invoke(
                text(
                    R.string.log_fire, comp.appLabel, comp.shortName(),
                    kindTag(comp.kind), recipeLabel, comp.packageName,
                ),
            )
            fireTime = System.currentTimeMillis()

            when (comp.kind) {
                ComponentKind.ACTIVITY -> {
                    val intent = Intent().apply {
                        component = ComponentName(comp.packageName, comp.className)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    AttackRecipes.applyPayload(intent, recipe)
                    activity.startActivity(intent)
                    // 前面に出た Activity が戻ってこない場合の保険（HOME 経由で自アプリへ戻す）。
                    handler.postDelayed(backPress, TIMEOUT_MS)
                }

                ComponentKind.SERVICE -> {
                    val intent = intentFor(comp, recipe)
                    if (recipe == RecipeType.FOREGROUND_SERVICE_5S &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ) {
                        activity.startForegroundService(intent)
                        scheduleNonActivityCheck(pair, recipe)
                    } else {
                        startServiceOrBind(pair, intent)
                    }
                }

                ComponentKind.RECEIVER -> {
                    activity.sendBroadcast(intentFor(comp, recipe))
                    scheduleNonActivityCheck(pair, recipe)
                }

                ComponentKind.PROVIDER -> {
                    fireProvider(comp)
                    scheduleNonActivityCheck(pair, recipe)
                }
            }
        } catch (t: Throwable) {
            onLog?.invoke(
                text(
                    R.string.log_fire_failed, comp.appLabel, comp.shortName(),
                    kindTag(comp.kind), recipeLabel, t.javaClass.simpleName,
                ),
            )
            results.add(
                Result(
                    comp.appLabel, comp.packageName, comp.shortName(), comp.className,
                    comp.appVersion, recipe, recipeLabel, hit = false,
                    confidence = HitConfidence.NONE,
                    kind = comp.kind, authority = comp.authority,
                ),
            )
            reportProgress()
            handler.post { fireNext() }
        }
    }

    /** Activity 以外へ送る Intent（extras を付与）。 */
    private fun intentFor(comp: TargetComponent, recipe: RecipeType): Intent =
        Intent().apply {
            component = ComponentName(comp.packageName, comp.className)
            AttackRecipes.applyPayload(this, recipe)
        }

    /**
     * Provider は別プロセス（`:probe`）から撃つ。
     *
     * 対象 Provider のプロセスが死ぬと、query したクライアントプロセスも
     * DEPENDENCY DIED（reason=12）で殺される。本体から撃つと LoneFuzz 自身が落ちて
     * 観測できないため、隔離したプローブに投げる。判定は本体が対象パッケージの
     * 終了理由で行う（query が返す例外自体は HIT にしない）。
     */
    private fun fireProvider(comp: TargetComponent) {
        val authority = comp.authority
        if (authority.isNullOrEmpty()) {
            onLog?.invoke(text(R.string.log_probe_no_authority, comp.appLabel, comp.shortName()))
            return
        }
        val intent = Intent(activity, ProviderProbeService::class.java).apply {
            putExtra(ProviderProbeService.EXTRA_AUTHORITY, authority)
            putExtra(ProviderProbeService.EXTRA_PACKAGE, comp.packageName)
        }
        try {
            activity.startService(intent)
        } catch (t: Throwable) {
            onLog?.invoke(
                text(R.string.log_probe_start_failed, comp.shortName(), t.javaClass.simpleName),
            )
        }
    }

    /**
     * Android 8+ は「バックグラウンドのアプリの Service」を startService で起動できない
     * （対象アプリ基準の制限。`BackgroundServiceStartNotAllowedException`）。
     * 先に bindService で対象プロセスを前面扱い（BOUND_TOP）にしてから startService を
     * 再試行すると通ることがあるため、その経路を試す。bind 自体が拒否された場合は
     * onStartCommand を叩けないので NONE として続行する（onCreate/onBind の死は検知できる）。
     */
    private fun startServiceOrBind(pair: Pair<TargetComponent, RecipeType>, intent: Intent) {
        try {
            activity.startService(intent)
            scheduleNonActivityCheck(pair, pair.second)
            return
        } catch (t: Throwable) {
            if (!isBackgroundServiceStartBlocked(t)) throw t
            onLog?.invoke(text(R.string.log_bg_service_blocked, pair.first.shortName()))
        }

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) = Unit
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val bound = try {
            activity.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        } catch (t: Throwable) {
            onLog?.invoke(text(R.string.log_bind_failed, t.javaClass.simpleName))
            false
        }
        handler.postDelayed({
            if (!scanning || current !== pair) {
                if (bound) runCatching { activity.unbindService(conn) }
                return@postDelayed
            }
            if (bound) {
                try {
                    activity.startService(intent)
                    onLog?.invoke(text(R.string.log_bind_start_ok))
                } catch (t: Throwable) {
                    onLog?.invoke(text(R.string.log_bind_start_denied, t.javaClass.simpleName))
                }
                runCatching { activity.unbindService(conn) }
            } else {
                onLog?.invoke(text(R.string.log_bind_unavailable))
            }
            scheduleNonActivityCheck(pair, pair.second)
        }, SERVICE_BIND_WAIT_MS)
    }

    private fun isBackgroundServiceStartBlocked(t: Throwable): Boolean =
        t.javaClass.simpleName == "BackgroundServiceStartNotAllowedException"

    /**
     * Activity 以外は onResume が来ないため、固定時間後に終了理由/ダイアログだけで判定する。
     * FGS は 30 秒後の RemoteServiceException まで待つ必要があるので長めに取る。
     */
    private fun scheduleNonActivityCheck(pair: Pair<TargetComponent, RecipeType>, recipe: RecipeType) {
        // まず即死系（onCreate/onStartCommand での例外）を早期に拾う。
        handler.postDelayed({
            if (!scanning || current !== pair) return@postDelayed
            if (confirmedNow(pair.first)) {
                current = null
                resolve(pair, hit = true, confidence = HitConfidence.CONFIRMED)
                return@postDelayed
            }
            // 即死していなければ、FGS は約 30 秒後の RemoteServiceException まで待つ
            // （ダイアログは出ないため終了理由でのみ確定できる）。
            // それ以外は終了理由の記録遅延を吸収する程度でもう一段待って最終判定する。
            val extra = if (recipe == RecipeType.FOREGROUND_SERVICE_5S) {
                (FGS_WAIT_MS - NON_ACTIVITY_WAIT_MS).coerceAtLeast(USAGE_VERIFY_DELAY_MS)
            } else {
                USAGE_VERIFY_DELAY_MS
            }
            handler.postDelayed({
                if (!scanning || current !== pair) return@postDelayed
                current = null
                val confirmed = confirmedNow(pair.first)
                resolve(
                    pair,
                    hit = confirmed,
                    confidence = if (confirmed) HitConfidence.CONFIRMED else HitConfidence.NONE,
                )
            }, extra)
        }, NON_ACTIVITY_WAIT_MS)
    }

    private fun kindTag(kind: ComponentKind): String = when (kind) {
        ComponentKind.ACTIVITY -> "A"
        ComponentKind.SERVICE -> "S"
        ComponentKind.RECEIVER -> "R"
        ComponentKind.PROVIDER -> "P"
    }

    /** レシピがそのコンポーネント種別で意味を持つか。 */
    private fun isRecipeApplicable(kind: ComponentKind, recipe: RecipeType): Boolean = when (recipe) {
        RecipeType.FOREGROUND_SERVICE_5S -> kind == ComponentKind.SERVICE
        // Provider は ContentResolver 経由の query になるため、どのレシピも同じ撃ち方になる。
        // 重複を避けて空Intent の 1 回だけ撃つ。
        else -> kind != ComponentKind.PROVIDER || recipe == RecipeType.EMPTY
    }

    private fun finishRun() {
        scanning = false
        bursting = false
        onComplete?.invoke(results.toList())
    }

    fun onResumed() {
        if (!scanning) return
        handler.removeCallbacks(backPress)

        val pair = current ?: return
        // Activity 以外の判定は scheduleNonActivityCheck が行う（onResume では判定しない）。
        if (pair.first.kind != ComponentKind.ACTIVITY) return
        current = null
        val (comp, recipe) = pair

        // 3 系統の信号を統合して HIT と信頼度を決める:
        //  - confirmed: 発火後にクラッシュ死を観測。ApplicationExitInfo(=REASON_CRASH, DUMP) を
        //    第一とし、Accessibility のクラッシュ/ANR ダイアログも同格の確定ソースとする。
        //  - usage: DUMP が無く使用アクセスがある場合の補助信号（RESUMED→説明不能な STOP）。
        //  - rapidReturn: 上記いずれも使えない場合のみの最終手段（即戻り）。
        //
        // 誤検知抑制のため、判定は「確度の高い信号から順に採用し、下位信号は上位が
        // 使えないときだけ使う」。特に DUMP 付与時は終了理由が地面の真実なので、
        // クラッシュ未観測なら SUSPECTED を出さず NONE とする（トランポリン等の誤検知を排除）。
        val elapsed = System.currentTimeMillis() - fireTime
        val rapidReturn = elapsed < HIT_THRESHOLD_MS
        CrashWatcher.checkRapidReturn() // lastActivityShot を消費（状態クリア用）

        // onResume の時点では、クラッシュの確定証拠が **まだ届いていない**ことが多い
        // （プロセス死の記録やダイアログの Accessibility イベントは数百ms遅れる）。
        // ここで即断すると全部が「即戻り SUSPECTED」になり確定を取り損ねるため、
        // 少し待ってから確定を再確認し、観測できていれば CONFIRMED に昇格する。
        handler.postDelayed({
            if (!scanning) return@postDelayed
            if (confirmedNow(comp)) {
                resolve(pair, hit = true, confidence = HitConfidence.CONFIRMED)
                return@postDelayed
            }
            // 終了理由の記録遅延を吸収するため、もう一段待ってから最終判定する。
            handler.postDelayed({
                if (!scanning) return@postDelayed
                when {
                    confirmedNow(comp) ->
                        resolve(pair, hit = true, confidence = HitConfidence.CONFIRMED)

                    // DUMP 付与時: 終了理由が真実。クラッシュ死が無い＝誤検知として捨てる。
                    CrashWatcher.hasExitOracle(activity) ->
                        resolve(pair, hit = false, confidence = HitConfidence.NONE)

                    // 使用アクセスがある場合: 前景に出てから説明なく消えた場合のみ疑い。
                    CrashWatcher.hasUsageAccess(activity) -> {
                        val usage = CrashWatcher.usageDisappearedSince(comp.packageName, fireTime, activity)
                        resolve(
                            pair,
                            hit = usage,
                            confidence = if (usage) HitConfidence.SUSPECTED else HitConfidence.NONE,
                        )
                    }

                    // どちらの oracle も無い場合のみ、即戻りを最終手段として使う。
                    rapidReturn ->
                        resolve(pair, hit = true, confidence = HitConfidence.SUSPECTED)

                    else ->
                        resolve(pair, hit = false, confidence = HitConfidence.NONE)
                }
            }, USAGE_VERIFY_DELAY_MS)
        }, CONFIRM_WAIT_MS)
    }

    /** 発火以降にクラッシュ死（終了理由 or ダイアログ）を観測できているか。 */
    private fun confirmedNow(comp: TargetComponent): Boolean =
        CrashWatcher.exitCrashSince(activity, comp.packageName, fireTime) ||
            CrashWatcher.confirmedCrashSince(comp.packageName, fireTime)

    /** 1 テストの結果確定と締め切り処理（記録/通報/次の発火）。 */
    private fun resolve(pair: Pair<TargetComponent, RecipeType>, hit: Boolean, confidence: HitConfidence) {
        val (comp, recipe) = pair

        val recipeLabel = text(recipe.labelRes)
        val result = Result(
            comp.appLabel, comp.packageName, comp.shortName(), comp.className,
            comp.appVersion, recipe, recipeLabel, hit, confidence,
            kind = comp.kind, authority = comp.authority,
        )
        results.add(result)
        if (hit) onHit?.invoke(result)
        val mark = when {
            confidence == HitConfidence.CONFIRMED -> text(R.string.mark_confirmed)
            hit -> text(R.string.mark_suspected)
            else -> "----"
        }
        onLog?.invoke(
            text(
                R.string.log_result, comp.appLabel, comp.shortName(),
                recipeLabel, mark,
            ),
        )
        reportProgress()

        // BURST: 以前は効いた HIT が連続で効かなくなったら「効かなくなった」とみなす。
        // ただし連射は止めない（「止めるまで継続」を厳守）。武器庫からの無効化＋ログのみ行い、
        // 再探索は手動 spray に委ねる。カウンタは通知後にリセットして再通知を抑制する。
        if (bursting) {
            val k = missKey(comp, recipe)
            if (hit) {
                burstMisses[k] = 0
            } else {
                val miss = (burstMisses[k] ?: 0) + 1
                if (miss >= MISS_THRESHOLD) {
                    burstMisses[k] = 0
                    onStaleDetected?.invoke(result)
                } else {
                    burstMisses[k] = miss
                }
            }
        }

        // spray モードではHIT時に同コンポーネントの残りレシピをスキップ
        // BURST モードでは全て撃ち続ける
        if (hit && !bursting) {
            queue.removeAll { (c, _) -> c.packageName == comp.packageName && c.className == comp.className }
        }

        try { am.killBackgroundProcesses(comp.packageName) } catch (_: Throwable) {}

        handler.postDelayed({ fireNext() }, 400)
    }

    private fun missKey(comp: TargetComponent, recipe: RecipeType) =
        "${comp.packageName}|${comp.className}|${recipe.name}"

    private val backPress = Runnable {
        CrashDetectorService.goHomeAndReturn()
    }

    private fun reportProgress() {
        val done = results.size
        val hits = results.count { it.hit }
        onProgress?.invoke(done, totalCount, hits)
    }

    fun stop() {
        scanning = false
        bursting = false
        handler.removeCallbacksAndMessages(null)
        current = null
        queue.clear()
        burstMisses.clear()
    }

    fun getHits(): List<Result> = results.filter { it.hit }

    /** 手動停止時の途中経過（実行済み, 総数）。 */
    fun progress(): Pair<Int, Int> = results.size to totalCount

    fun exportResults(): String {
        val hits = results.filter { it.hit }
        val confirmed = hits.filter { it.confidence == HitConfidence.CONFIRMED }
        val suspected = hits.filter { it.confidence == HitConfidence.SUSPECTED }
        val testedApps = results.map { it.packageName }.distinct()
        val vulnApps = hits.map { it.packageName }.distinct()

        return buildString {
            appendLine("LoneFuzz スキャン結果")
            appendLine("=======================")
            appendLine("テスト数: ${results.size}")
            appendLine("対象アプリ数: ${testedApps.size}")
            appendLine("HIT: ${hits.size} (${vulnApps.size} アプリ)")
            appendLine("  CONFIRMED(クラッシュ確定): ${confirmed.size}")
            appendLine("  SUSPECTED(即戻り/UsageStats・要再現確認): ${suspected.size}")
            appendLine()
            if (hits.isNotEmpty()) {
                appendLine("--- HIT 一覧（再現用）---")
                var lastPkg = ""
                for (h in hits) {
                    if (h.packageName != lastPkg) {
                        appendLine()
                        appendLine("${h.appLabel} (${h.packageName}) v${h.appVersion}")
                        lastPkg = h.packageName
                    }
                    val tag = if (h.confidence == HitConfidence.CONFIRMED) "CONFIRMED" else "SUSPECTED"
                    appendLine("  → [$tag] ${h.className} [${h.recipe}]")
                }
                appendLine()
            }
            appendLine("--- 全結果 ---")
            for (r in results) {
                val mark = when {
                    r.confidence == HitConfidence.CONFIRMED -> "HIT*"
                    r.hit -> "HIT?"
                    else -> "----"
                }
                appendLine("[${kindTag(r.kind)}][$mark] ${r.appLabel} / ${r.component} [${r.recipe}]")
            }
            appendLine()
            appendLine("凡例: HIT*=クラッシュ確定(crash死/dlg) / HIT?=即戻りのみ(要確認)")
        }
    }

    companion object {
        private const val TIMEOUT_MS = 3000L
        private const val HIT_THRESHOLD_MS = 1500L

        /** UsageStats の確認を遅らせる時間（usage event の記録遅延を吸収）。 */
        private const val USAGE_VERIFY_DELAY_MS = 800L

        /** onResume 後、クラッシュダイアログの観測を待つ猶予（これを経て CONFIRMED へ昇格）。 */
        private const val CONFIRM_WAIT_MS = 700L

        /** BURST で「効かなくなった」と判定する連続未命中のしきい値。 */
        private const val MISS_THRESHOLD = 3

        /** 残留クラッシュダイアログを閉じてから次を撃つまでの待ち。 */
        private const val DIALOG_DISMISS_WAIT_MS = 500L

        /** Activity 以外（Service/Receiver/Provider）を撃ってから判定するまでの待ち。 */
        private const val NON_ACTIVITY_WAIT_MS = 2_000L

        /**
         * FGS 5秒ルール違反は ANR ダイアログではなく、約 30 秒後に
         * RemoteServiceException(ForegroundServiceDidNotStartInTimeException) で
         * プロセスがクラッシュする（実測: Pixel 7a / Android 17, anrInfo=null）。
         * 終了理由でのみ確定できるため、30 秒 + 余裕を待つ。FGS レシピは既定オフ。
         */
        private const val FGS_WAIT_MS = 31_000L

        /** bindService で対象を前面扱いにしてから startService を再試行するまでの待ち。 */
        private const val SERVICE_BIND_WAIT_MS = 600L

        private val SKIP_PREFIXES = listOf(
            "com.google.",
            "com.android.",
            "android",
            "com.qualcomm.",
            "com.samsung.",
            "com.sec.",
            "com.lge.",
            "com.huawei.",
            "com.oppo.",
            "com.vivo.",
            "com.xiaomi.",
            "com.miui.",
            "com.coloros.",
            "com.oneplus.",
            "com.motorola.",
            "com.sonyericsson.",
            "com.sony.",
            "jp.co.sharp.",
        )

        fun isSystemPackage(pkg: String): Boolean =
            SKIP_PREFIXES.any { pkg.startsWith(it) }
    }
}
