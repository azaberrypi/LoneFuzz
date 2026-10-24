package io.github.azaberrypi.lonefuzz

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.azaberrypi.lonefuzz.model.ComponentKind
import io.github.azaberrypi.lonefuzz.model.TargetComponent
import java.util.concurrent.atomic.AtomicInteger

/**
 * 選択された exported コンポーネント × 選択レシピを、START が押されている間だけ
 * 総当たりで連射するエンジン。発火はワーカースレッドで行い、結果を [Listener] に流す。
 *
 * 注意: クラッシュは「相手プロセス」で非同期に起きるため、こちらの startXxx() は
 * 多くの場合に例外を投げない（＝送信成功としてログする）。呼び出し側で捕捉できる例外
 * （TransactionTooLarge / Security / ActivityNotFound 等）だけがローカルに出る。
 */
class AttackEngine(context: Context) {

    interface Listener {
        fun onLog(line: String)
        fun onStats(sent: Int, errors: Int)
    }

    private val appContext = context.applicationContext
    private val sent = AtomicInteger(0)
    private val errors = AtomicInteger(0)

    @Volatile private var running = false
    @Volatile private var shotRunning = false
    private var worker: Thread? = null

    /** 連射開始時の言語。ログはこの言語で固定する。 */
    @Volatile private var logText: LogText? = null

    private fun text(id: Int, vararg args: Any): String {
        val lt = logText ?: LogText(appContext, LogText.currentLocale(appContext)).also { logText = it }
        return lt.get(id, *args)
    }

    private fun header(): String {
        val lt = logText ?: LogText(appContext, LogText.currentLocale(appContext)).also { logText = it }
        return lt.runHeader()
    }

    fun start(targets: List<TargetComponent>, recipes: List<RecipeType>, listener: Listener) {
        if (running) return
        logText = LogText(appContext, LogText.currentLocale(appContext))
        if (targets.isEmpty()) {
            listener.onLog(text(R.string.log_no_targets))
            return
        }
        if (recipes.isEmpty()) {
            listener.onLog(text(R.string.log_no_recipes))
            return
        }
        running = true
        sent.set(0)
        errors.set(0)
        listener.onStats(0, 0)
        listener.onLog(header())
        listener.onLog(text(R.string.log_hold_start, targets.size, recipes.size))

        worker = Thread {
            try {
                while (running) {
                    for (tc in targets) {
                        if (!running) break
                        for (recipe in recipes) {
                            if (!running) break
                            fireOnce(tc, recipe, listener)
                            // 連射しつつ UI/システムを詰まらせない程度のウェイト。
                            try {
                                Thread.sleep(30)
                            } catch (ie: InterruptedException) {
                                return@Thread
                            }
                        }
                    }
                }
            } finally {
                listener.onLog(text(R.string.log_hold_stop, sent.get(), errors.get()))
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }

    /**
     * 単発 burst: 選択対象 × レシピを 1 周だけ送る。可視クラッシュ用。
     * exported Activity を単発で撃つと、前面に起動して即落ちるのが見える
     * （前景からの最初の startActivity は BAL 制限を通るため）。
     */
    fun fireBurst(targets: List<TargetComponent>, recipes: List<RecipeType>, listener: Listener) {
        logText = LogText(appContext, LogText.currentLocale(appContext))
        if (running) return
        if (shotRunning) {
            listener.onLog(text(R.string.log_shot_busy))
            return
        }
        if (targets.isEmpty()) {
            listener.onLog(text(R.string.log_no_targets))
            return
        }
        if (recipes.isEmpty()) {
            listener.onLog(text(R.string.log_no_recipes))
            return
        }
        sent.set(0)
        errors.set(0)
        listener.onStats(0, 0)
        shotRunning = true
        Thread {
            try {
                listener.onLog(header())
                listener.onLog(text(R.string.log_shot_start, targets.size, recipes.size))
                for (tc in targets) {
                    for (recipe in recipes) {
                        fireOnce(tc, recipe, listener)
                        try {
                            Thread.sleep(50)
                        } catch (ie: InterruptedException) {
                            return@Thread
                        }
                    }
                }
                listener.onLog(text(R.string.log_shot_done, sent.get(), errors.get()))
            } finally {
                shotRunning = false
            }
        }.apply { isDaemon = true }.start()
    }

    private fun fireOnce(tc: TargetComponent, recipe: RecipeType, listener: Listener) {
        // FGS レシピは Service 以外では無意味なのでスキップ。
        if (recipe == RecipeType.FOREGROUND_SERVICE_5S && tc.kind != ComponentKind.SERVICE) return

        val recipeLabel = text(recipe.labelRes)
        val tag = "${tc.kind.name.take(3)} ${short(tc.className)} [$recipeLabel]"
        try {
            when (tc.kind) {
                ComponentKind.ACTIVITY -> {
                    val i = baseIntent(tc)
                    AttackRecipes.applyPayload(i, recipe)
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    appContext.startActivity(i)
                    val shot = CrashWatcher.FiredShot(
                        tc.packageName, tc.appLabel, tc.shortName(), recipeLabel,
                    )
                    CrashWatcher.recordFire(shot)
                    CrashWatcher.recordActivityFire(shot)
                    ok(tag, listener)
                }

                ComponentKind.RECEIVER -> {
                    val i = baseIntent(tc)
                    AttackRecipes.applyPayload(i, recipe)
                    appContext.sendBroadcast(i)
                    recordShot(tc, recipeLabel)
                    ok(tag, listener)
                }

                ComponentKind.SERVICE -> {
                    val i = baseIntent(tc)
                    AttackRecipes.applyPayload(i, recipe)
                    if (recipe == RecipeType.FOREGROUND_SERVICE_5S &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ) {
                        appContext.startForegroundService(i)
                    } else {
                        appContext.startService(i)
                    }
                    recordShot(tc, recipeLabel)
                    ok(tag, listener)
                }

                ComponentKind.PROVIDER -> fireProvider(tc, tag, listener)
            }
        } catch (t: Throwable) {
            // 呼び出し側に跳ね返った例外（TooLarge/Security 等）。これも有効な観測結果。
            fail("$tag -> ${t.javaClass.simpleName}: ${t.message}", listener)
        }
    }

    private fun fireProvider(tc: TargetComponent, tag: String, listener: Listener) {
        val authority = tc.authority
        if (authority.isNullOrEmpty()) {
            fail("$tag -> no authority", listener)
            return
        }
        val uri = AttackRecipes.providerUri(authority)
        val cr = appContext.contentResolver
        // query / insert の双方を叩き、未処理例外や SecurityException を観測する。
        try {
            cr.query(uri, null, null, null, null)?.close()
        } catch (t: Throwable) {
            fail("$tag query -> ${t.javaClass.simpleName}", listener)
            return
        }
        try {
            cr.insert(uri, ContentValues())
        } catch (t: Throwable) {
            fail("$tag insert -> ${t.javaClass.simpleName}", listener)
            return
        }
        ok("$tag ($authority)", listener)
    }

    private fun baseIntent(tc: TargetComponent): Intent =
        Intent().setComponent(ComponentName(tc.packageName, tc.className))

    private fun recordShot(tc: TargetComponent, recipeLabel: String) {
        CrashWatcher.recordFire(
            CrashWatcher.FiredShot(
                packageName = tc.packageName,
                appLabel = tc.appLabel,
                componentShort = tc.shortName(),
                recipe = recipeLabel,
            ),
        )
    }

    private fun ok(tag: String, listener: Listener) {
        val s = sent.incrementAndGet()
        listener.onLog("[send] $tag")
        listener.onStats(s, errors.get())
    }

    private fun fail(msg: String, listener: Listener) {
        sent.incrementAndGet()
        val e = errors.incrementAndGet()
        listener.onLog("[err ] $msg")
        listener.onStats(sent.get(), e)
    }

    private fun short(className: String): String = className.substringAfterLast('.')
}
